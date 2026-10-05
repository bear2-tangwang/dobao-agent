import {
  uploadInterviewAudio,
  streamInterview,
  getInterviewStatus,
  getInterviewReport,
  getInterviewDownloadUrl,
  retryInterview
} from '@/api'
import { INTERVIEW_STATUS, MAX_AUDIO_BYTES, AUDIO_EXTENSIONS } from '@/utils/constants'
import { formatFileSize } from '@/utils/format'
import type {
  InterviewSession,
  InterviewStep,
  InterviewStepKey,
  InterviewReport,
  InterviewStatusVo,
  InterviewUploadVo
} from '@/types'

/**
 * 面试总结 · 上传 + 流式进度。
 *
 * <h3>两条通道，各管一件事</h3>
 * <ul>
 *   <li><b>SSE 流（`GET /interview/{id}/stream`）</b>：把"已经发生的事"实时推给页面，
 *       让用户在数分钟的处理期里看到进度。</li>
 *   <li><b>轮询兜底（`GET /interview/{id}/status`）</b>：流断掉（切标签页、锁屏、代理超时）时接管。</li>
 * </ul>
 *
 * <p><b>为什么兜底不能省</b>：真正的事实来源是数据库 + MinIO（后端是 `@Async` 后台任务，
 * 断线照跑），流只是通知；而任何长连接都撑不住整场处理时长。反过来只做轮询又会留下
 * 处理期的空白。两条都要。
 *
 * <p><b>去重</b>：流和兜底可能报同一件事，因此每个阶段有稳定的语义 key
 * （{@link InterviewStepKey}），相同 key 只更新文案，不重复记一条。
 *
 * <h3>为什么 session 由调用方传入，而不是在 composable 内部持有一个</h3>
 * <p>`pushStep()` 必须改到**消息气泡真正渲染的那个对象**上，否则界面要等整场跑完才刷新
 * （拷贝会切断响应式）。同时"整个应用只保留一个 session"也不行：一个会话里做第二场面试时，
 * `Object.assign` 会把第一场的报告就地清空。所以约定是**每条面试消息各自拥有一个 session**
 * （`Message.interview`），本 composable 只**就地修改**、从不替换对象。
 */

/**
 * 兜底轮询参数。
 *
 * <p>SSE 是主通道，轮询只在"流重连也失败"时才启动；间隔取 5 秒起步，
 * 因为单个阶段可能只有十几秒，间隔太长会整段错过。
 */
const POLL_INITIAL_MS = 5000
const POLL_MAX_MS = 30000
const POLL_BACKOFF = 1.4

/**
 * 断流后的重连退避（毫秒）。
 *
 * <p>长连接被代理/tab 挂起掐断是常态，而重连是零成本的：后端每次连接都会先补一帧
 * snapshot（当前状态、句数、说话人数都在），所以重连不会丢阶段、也不会重复记步骤。
 * 用尽这些退避仍拿不到终态，才降级为轮询。
 */
const STREAM_RETRY_DELAYS_MS = [1000, 3000, 6000, 10000]

/** 退避数组取值的类型兜底（实际上不会用到：走到这里时 attempt 一定在范围内） */
const STREAM_RETRY_FALLBACK_MS = 10000

/** 各状态在兜底时对应哪个步骤 key（做去重用） */
const STATUS_STEP_KEY: Record<string, InterviewStepKey> = {
  [INTERVIEW_STATUS.UPLOADED]: 'uploaded',
  [INTERVIEW_STATUS.TRANSCRIBING]: 'transcribing',
  [INTERVIEW_STATUS.TRANSCRIBED]: 'transcribed',
  [INTERVIEW_STATUS.ANALYZING]: 'analyzing',
  [INTERVIEW_STATUS.READY]: 'ready',
  [INTERVIEW_STATUS.FAILED]: 'error'
}

const TERMINAL_STATUSES: string[] = [INTERVIEW_STATUS.READY, INTERVIEW_STATUS.FAILED]

/**
 * 是不是终态（READY / FAILED）。
 *
 * <p>历史回放时用它决定"要不要再订阅进度流"：已经跑完或已经失败的场次
 * 只需要一次状态拉取，不该为每条历史消息都开一条 SSE。
 */
export const isTerminalInterviewStatus = (status: string): boolean => TERMINAL_STATUSES.includes(status)

/**
 * 一场面试的初始 session。
 *
 * <p>每条面试消息在创建时各自调用一次，得到的是**互相独立**的对象；
 * 重置时用 `Object.assign(session, createInterviewSession())` 就地覆盖字段
 * （而不是换新对象），消息气泡的引用才不会断。
 */
export const createInterviewSession = (): InterviewSession => ({
  interviewId: null,
  status: '',
  fileName: null,
  fileSize: null,
  steps: [],
  qaCount: 0,
  reportUrl: null,
  downloadUrl: null,
  report: null,
  errorMsg: null,
  degraded: false,
  uploading: false,
  stopped: false
})

export function useInterview() {
  /** 流 + 轮询共用的取消句柄：stop 时一起断掉 */
  let abortController: AbortController | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 某一场面试是否还在处理中。
   *
   * <p>是普通函数而不是 computed：界面上同时可能存在多条面试消息，谁在跑要看各自的
   * session。在模板里调用它同样会被依赖收集到（渲染副作用里读到了 session 的字段）。
   */
  const isProcessing = (session: InterviewSession | null | undefined): boolean => {
    if (!session) {
      return false
    }
    const status = session.status
    return (
      !!session.interviewId &&
      status !== '' &&
      !TERMINAL_STATUSES.includes(status) &&
      !session.errorMsg &&
      !session.stopped
    )
  }

  /**
   * 就地重置一场面试（**不换对象**，见文件头）。
   * 用 `Object.assign` 一次性覆盖全部字段，避免上一场的残留。
   */
  const resetSession = (session: InterviewSession) => {
    Object.assign(session, createInterviewSession())
  }

  /** 记一条进度。同 key 只保留一条（更新文案），避免流与兜底重复记录。 */
  const pushStep = (session: InterviewSession, key: InterviewStepKey, text: string, done = false) => {
    const steps = session.steps as InterviewStep[]
    const existing = steps.find(step => step.key === key)
    if (existing) {
      existing.text = text
      if (done) {
        existing.done = true
      }
      return
    }
    steps.push({ key, text, done })
  }

  /**
   * 上传音频并开始处理，进度写进调用方给的 session。
   *
   * @param session 这条面试消息自己的 session（就地修改，不替换）
   * @param conversationId 当前会话ID：后端据此把这场面试写进 ai_session（历史可见/可还原）
   * @throws Error 前端校验不通过、或上传失败（调用方负责提示用户）
   */
  const start = async (file: File, session: InterviewSession, conversationId?: string | null) => {
    validateAudio(file)
    stop()

    resetSession(session)
    session.fileName = file.name
    session.fileSize = file.size
    // 上传期间还没有 interviewId：面板据此显示"提交中"，而不是又露出一张上传卡
    session.uploading = true

    abortController = new AbortController()
    let uploaded: InterviewUploadVo
    try {
      uploaded = await uploadInterviewAudio(file, conversationId, abortController.signal)
    } finally {
      session.uploading = false
    }

    session.interviewId = uploaded.interviewId
    session.status = uploaded.status
    session.fileName = uploaded.fileName || file.name
    session.fileSize = uploaded.fileSize ?? file.size
    persistId(uploaded.interviewId)

    pushStep(
      session,
      'uploaded',
      uploaded.reused
        ? '这段音频此前已上传过，直接复用已有记录（不会重复转写、不重复计费）'
        : `已接收录音《${session.fileName}》（${formatFileSize(file.size)}），正在提交转写…`,
      true
    )

    await runProgress(session)
  }

  /** 直接对某个 interviewId 开始接收进度（重试、或从历史记录恢复时用）。 */
  const startById = async (interviewId: string, session: InterviewSession, fileName: string | null = null) => {
    stop()
    resetSession(session)
    session.interviewId = interviewId
    session.fileName = fileName
    persistId(interviewId)
    await runProgress(session)
  }

  /** 处理失败后重试：有文字稿只重跑分析，否则重新提交转写（后端 `retry` 里判断）。 */
  const retry = async (session: InterviewSession) => {
    const id = session.interviewId
    if (!id) {
      return
    }
    session.errorMsg = null
    session.degraded = false
    await retryInterview(id)
    pushStep(session, 'analyzing', '已触发重试，正在继续处理…')
    await runProgress(session)
  }

  /** 停止接收（不影响后端任务，它仍在后台跑；重新进入时靠兜底轮询续上） */
  const stop = () => {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
  }

  const runProgress = async (session: InterviewSession) => {
    if (!session.interviewId) {
      return
    }
    let streamError: unknown = null
    try {
      await runStreamWithReconnect(session)
    } catch (error) {
      streamError = error
    }
    if (session.status === INTERVIEW_STATUS.READY || session.errorMsg || session.stopped) {
      return
    }
    // 走到这里说明流没能把这场面试带到终态。**正常结束与报错都算降级**：
    // 流被中间层静默掐断时页面会冻在最后一条步骤上，用户看不出发生了什么。
    const detail = streamError instanceof Error ? streamError.message : String(streamError ?? '进度流已结束')
    console.warn('面试进度流中断，降级为轮询:', detail)
    session.degraded = true
    await startPolling(session)
  }

  /**
   * 读流 + 断线重连，直到终态、用户停止、或重试次数用尽。
   *
   * <p>不是"断了就直接轮询"：轮询最快也要 5 秒一轮，而重连是即时的，
   * 且后端连接时会补 snapshot，两者配合最不容易让用户看到"卡住"。
   */
  const runStreamWithReconnect = async (session: InterviewSession) => {
    let lastError: unknown = null
    for (let attempt = 0; ; attempt++) {
      if (session.stopped || session.errorMsg) {
        return
      }
      try {
        await runStream(session)
      } catch (error) {
        lastError = error
      }
      if (session.status === INTERVIEW_STATUS.READY || session.stopped || session.errorMsg) {
        return
      }
      if (attempt >= STREAM_RETRY_DELAYS_MS.length) {
        throw lastError ?? new Error('进度流多次中断')
      }
      await sleep(STREAM_RETRY_DELAYS_MS[attempt] ?? STREAM_RETRY_FALLBACK_MS)
    }
  }

  /** 重连退避用的等待（不走 pollTimer：那个句柄归轮询与 stop 管理） */
  const sleep = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms))

  /**
   * 读 SSE 流，直到终态或流结束。
   *
   * 后端发的是**规范 SSE**：`event: progress` + `data: {json}` + 空行，心跳是注释行 `:ping`；
   * 与对话接口的 `{"type": ...}` 载荷不同，所以这里单独一套解析。
   *
   * <p>只按规范解析：遇到解析不了的内容记一条 warn 即可，不要试图猜格式。
   */
  const runStream = async (session: InterviewSession) => {
    const id = session.interviewId
    if (!id) {
      return
    }
    const controller = abortController
    const { reader } = await streamInterview(id, controller?.signal)

    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }
        buffer += decoder.decode(value, { stream: true })

        let sep = findEventSeparator(buffer)
        while (sep) {
          const block = buffer.slice(0, sep.index)
          buffer = buffer.slice(sep.index + sep.length)
          handleEventBlock(session, block)
          if (session.status === INTERVIEW_STATUS.READY) {
            return
          }
          sep = findEventSeparator(buffer)
        }
      }
      // 流正常结束但没收到 complete：可能是后端在终态后主动关闭
      if (buffer.trim()) {
        handleEventBlock(session, buffer)
      }
    } finally {
      try {
        reader.cancel()
      } catch {
        // 忽略：流已结束时 cancel 可能报错，无影响
      }
    }
  }

  /** 找到事件分隔符（支持 \n\n 与 \r\n\r\n） */
  const findEventSeparator = (text: string): { index: number; length: number } | null => {
    const nn = text.indexOf('\n\n')
    const rnrn = text.indexOf('\r\n\r\n')
    if (nn === -1 && rnrn === -1) {
      return null
    }
    if (rnrn !== -1 && (nn === -1 || rnrn < nn)) {
      return { index: rnrn, length: 4 }
    }
    return { index: nn, length: 2 }
  }

  /** 解析一个 SSE 事件块并更新会话 */
  const handleEventBlock = (session: InterviewSession, block: string) => {
    let eventName = 'message'
    const dataLines: string[] = []

    for (const rawLine of block.split(/\r?\n/)) {
      const line = rawLine.trimEnd()
      if (!line || line.startsWith(':')) {
        continue
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }
    if (dataLines.length === 0) {
      return
    }

    const raw = dataLines.join('\n')
    if (raw === '[DONE]') {
      return
    }

    let payload: Record<string, unknown>
    try {
      payload = JSON.parse(raw) as Record<string, unknown>
    } catch {
      console.warn('面试进度事件不是合法 JSON，已忽略:', raw)
      return
    }
    applyEvent(session, eventName, payload)
  }

  /**
   * 把"一份状态"落到界面上：SSE 的 snapshot 事件与兜底的 status 接口共用同一套口径。
   *
   * <p>文案一律取后端给的 `text` / `stageDescription` —— 状态措辞只有后端
   * `InterviewStatus.description()` 一份，前端不再维护第二张表（那样两条通道迟早漂移）。
   */
  const applyStatusFields = (
    session: InterviewSession,
    fields: {
      status: string | null
      text: string | null
      sentenceCount?: number
      speakerCount?: number | null
      reportReady?: boolean
    }
  ) => {
    if (fields.status) {
      session.status = fields.status
    }
    const sentenceText =
      typeof fields.sentenceCount === 'number' && fields.sentenceCount > 0
        ? `转写完成：${fields.sentenceCount} 句 / ${fields.speakerCount ?? '?'} 位说话人，文字稿已就绪`
        : null
    const key = STATUS_STEP_KEY[session.status]

    // 状态已经越过 TRANSCRIBED 时补记一条明细，保持步骤的时间顺序
    if (sentenceText && key !== 'transcribed') {
      pushStep(session, 'transcribed', sentenceText, true)
    }
    if (key && key !== 'error') {
      // TRANSCRIBED 这一步直接用明细文案：同 key 再 push 一次会覆盖 text，泛化措辞会把句数冲掉
      pushStep(
        session,
        key,
        key === 'transcribed' && sentenceText ? sentenceText : fields.text || '处理中…',
        session.status === INTERVIEW_STATUS.READY
      )
    }
    if (fields.reportReady) {
      pushStep(session, 'ready', '报告已生成', true)
    }
  }

  /** 把后端事件映射成界面状态 */
  const applyEvent = (session: InterviewSession, eventName: string, payload: Record<string, unknown>) => {
    if (eventName === 'snapshot') {
      // 重连 / 刷新后的当前态：字段与 status 接口对齐
      applyStatusFields(session, {
        status: str(payload.status),
        text: str(payload.text),
        sentenceCount: num(payload.sentenceCount),
        speakerCount: num(payload.speakerCount) ?? null,
        reportReady: bool(payload.reportReady) || !!str(payload.reportUrl)
      })
      return
    }

    const status = str(payload.status)
    if (status) {
      session.status = status
      if (status === INTERVIEW_STATUS.READY) {
        pushStep(session, 'ready', '报告已生成', true)
      }
    }

    if (eventName === 'progress') {
      const stage = str(payload.stage)
      const qaCount = num(payload.qaCount)
      if (typeof qaCount === 'number') {
        session.qaCount = qaCount
      }
      const key = (stage || 'analyzing') as InterviewStepKey
      pushStep(session, key, str(payload.text) || '处理中…', stage === 'transcribed')
      return
    }

    if (eventName === 'complete') {
      session.status = str(payload.status) || INTERVIEW_STATUS.READY
      session.reportUrl = str(payload.reportUrl)
      session.downloadUrl =
        str(payload.downloadUrl) || getInterviewDownloadUrl(session.interviewId || '')
      const report = payload.report as InterviewReport | undefined
      if (report) {
        session.report = report
        session.qaCount = report.qaList?.length ?? session.qaCount
      }
      pushStep(session, 'ready', '报告已生成', true)
      clearPersisted()
      return
    }

    if (eventName === 'error') {
      session.errorMsg = str(payload.message) || '处理失败'
      pushStep(session, 'error', session.errorMsg || '处理失败', true)
    }
  }

  // ==================== 兜底轮询 ====================

  /** 轮询直到终态（SSE 断流且重连失败时接管） */
  const startPolling = async (session: InterviewSession) => {
    if (!session.interviewId) {
      return
    }
    let delay = POLL_INITIAL_MS
    for (;;) {
      if (!session.interviewId || TERMINAL_STATUSES.includes(session.status)) {
        return
      }
      try {
        const status = await getInterviewStatus(session.interviewId)
        applyStatus(session, status)
        if (TERMINAL_STATUSES.includes(status.status)) {
          // 已经就绪：直接把报告正文拉回来，别再让用户点一次"载入报告内容"
          if (status.status === INTERVIEW_STATUS.READY || status.reportReady) {
            await loadReport(session)
          }
          return
        }
        delay = POLL_INITIAL_MS
      } catch (error) {
        console.warn('查询面试状态失败，稍后重试:', error)
        delay = Math.min(Math.round(delay * POLL_BACKOFF), POLL_MAX_MS)
      }
      await wait(delay)
      delay = Math.min(Math.round(delay * POLL_BACKOFF), POLL_MAX_MS)
    }
  }

  const wait = (ms: number) =>
    new Promise<void>(resolve => {
      pollTimer = setTimeout(() => {
        pollTimer = null
        resolve()
      }, ms)
    })

  /** 用 status 接口的结果更新界面（与流式事件共用同一套步骤去重） */
  const applyStatus = (session: InterviewSession, status: InterviewStatusVo) => {
    if (status.errorMsg) {
      session.status = status.status
      session.errorMsg = status.errorMsg
      pushStep(session, 'error', status.errorMsg, true)
      return
    }
    applyStatusFields(session, {
      status: status.status,
      text: status.stageDescription,
      sentenceCount: status.sentenceCount ?? undefined,
      speakerCount: status.speakerCount,
      reportReady: status.reportReady
    })
  }

  /**
   * 从后端把报告拉回来（流断过、或刷新页面后重新进入时用）。
   *
   * @returns 是否拿到了报告
   */
  const loadReport = async (session: InterviewSession): Promise<boolean> => {
    const id = session.interviewId
    if (!id) {
      return false
    }
    try {
      const report = await getInterviewReport(id)
      session.report = report
      session.reportUrl = session.reportUrl || ''
      session.downloadUrl = getInterviewDownloadUrl(id)
      session.qaCount = report.qaList?.length ?? 0
      session.status = INTERVIEW_STATUS.READY
      pushStep(session, 'ready', '报告已生成', true)
      clearPersisted()
      return true
    } catch (error) {
      console.warn('获取报告失败:', error)
      return false
    }
  }

  /** 静默恢复：查一次状态，已就绪就把报告拉回来。用于刷新后重新进入同一场面试，失败不打扰用户。 */
  const tryRestore = async (interviewId: string, session: InterviewSession): Promise<boolean> => {
    try {
      const status = await getInterviewStatus(interviewId)
      session.interviewId = interviewId
      applyStatus(session, status)
      if (status.reportReady || status.status === INTERVIEW_STATUS.READY) {
        await loadReport(session)
        return true
      }
      return false
    } catch (error) {
      console.warn('恢复面试进度失败:', error)
      return false
    }
  }

  const STORAGE_KEY = 'dobao.interview.current'

  const persistId = (interviewId: string) => {
    try {
      localStorage.setItem(STORAGE_KEY, interviewId)
    } catch {
      // 隐私模式下 localStorage 不可用，忽略即可
    }
  }

  const clearPersisted = () => {
    try {
      localStorage.removeItem(STORAGE_KEY)
    } catch {
      // 同上
    }
  }

  const readPersistedId = (): string | null => {
    try {
      return localStorage.getItem(STORAGE_KEY)
    } catch {
      return null
    }
  }

  /**
   * 给定 interviewId 直接把报告取回来（用于会话历史里恢复一场已完成的面试）。
   *
   * <p>返回报告本身而不是写进 session：调用方要把它挂到**那一条历史消息**上，
   * 而不是当前正在进行的这一场。
   */
  const loadReportById = async (interviewId: string): Promise<InterviewReport | null> => {
    try {
      return await getInterviewReport(interviewId)
    } catch (error) {
      console.warn('按 interviewId 取报告失败:', error)
      return null
    }
  }

  return {
    isProcessing,
    start,
    startById,
    retry,
    stop,
    loadReport,
    loadReportById,
    tryRestore,
    readPersistedId
  }
}

// ==================== 工具函数 ====================

/**
 * 前端校验：扩展名 + 大小。
 * 与后端 `InterviewService.validate` 同口径，目的是"不合法就不发请求"。
 *
 * @throws Error 校验不通过
 */
export const validateAudio = (file: File): void => {
  const ext = file.name.split('.').pop()?.toLowerCase() || ''
  if (!AUDIO_EXTENSIONS.includes(ext)) {
    throw new Error(`仅支持音频文件（${AUDIO_EXTENSIONS.join(' / ')}），当前文件：.${ext || '未知'}`)
  }
  if (file.size > MAX_AUDIO_BYTES) {
    throw new Error(
      `音频文件过大：${formatFileSize(file.size)}，上限 ${formatFileSize(MAX_AUDIO_BYTES)}。请先压缩或裁剪后再上传。`
    )
  }
  if (file.size === 0) {
    throw new Error('音频文件为空，请重新选择')
  }
}

const str = (value: unknown): string | null => (typeof value === 'string' && value ? value : null)

const num = (value: unknown): number | undefined => (typeof value === 'number' ? value : undefined)

const bool = (value: unknown): boolean => value === true

/** 毫秒 → mm:ss（与后端 InterviewReportRenderer.formatTimestamp 同口径） */
export const formatTimestamp = (ms: number): string => {
  if (!ms || ms <= 0) {
    return '00:00'
  }
  const totalSeconds = Math.floor(ms / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const pad = (n: number) => String(n).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

/** 毫秒 → 中文时长 */
export const formatAudioDuration = (ms: number): string => {
  if (!ms || ms <= 0) {
    return '未知'
  }
  const totalSeconds = Math.floor(ms / 1000)
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`
}

const oneLine = (text: string | null | undefined): string =>
  text == null || String(text).trim() === '' ? '-' : String(text).replace(/\s+/g, ' ').trim()

/** 旧版本生成的报告缺少参考回答/总结时的统一提示，与后端 InterviewReportRenderer 同文案 */
const LEGACY_REPORT_HINT = '本场报告生成于旧版本，未包含本节内容；重新生成后即可看到。'

/**
 * 结构化报告 → Markdown，固定三节：**问答清单 / 参考回答 / 面试总结**。
 *
 * <p>时间戳来自后端 `QaListBuilder` 格式化出的真实句子，是可信的定位信息；
 * 文案与后端 `InterviewReportRenderer` 保持同一口径，避免同一份报告在页面与下载文件里不一样。
 */
export const reportToMarkdown = (report: InterviewReport): string => {
  const lines: string[] = ['# 面试总结报告', '']
  lines.push(`- 面试编号：${report.interviewId}`)
  lines.push(`- 音频时长：${formatAudioDuration(report.audioDurationMs)}`)
  lines.push(`- 对话条目：${report.qaList?.length ?? 0}`)
  lines.push(`- 参考回答：${report.referenceAnswers?.length ?? 0} 条`)
  lines.push('')

  // 一、问答清单
  lines.push('## 一、问答清单', '')
  const qaList = report.qaList ?? []
  if (qaList.length === 0) {
    lines.push('本次面试未获取到可成对的对话内容。', '')
  } else {
    lines.push(
      '> 整场面试的问答，按对话轮次逐条列出：面试官的一段发言为一条 Q，紧随其后的候选人发言为对应的 A，两者均为**逐字原文**，未做任何摘要或改写。',
      ''
    )
    for (const qa of qaList) {
      lines.push(`**${qa.qaId}** [${formatTimestamp(qa.questionBeginMs)}] ${oneLine(qa.question)}`, '')
      const answerRange =
        qa.answerEndMs > qa.answerBeginMs
          ? `${formatTimestamp(qa.answerBeginMs)}~${formatTimestamp(qa.answerEndMs)}`
          : formatTimestamp(qa.answerBeginMs)
      lines.push(`**A** [${answerRange}] ${oneLine(qa.answer)}`, '')
    }
  }

  // 二、参考回答（只覆盖技术问题）
  lines.push('## 二、参考回答', '')
  const referenceAnswers = report.referenceAnswers
  if (referenceAnswers == null) {
    lines.push(LEGACY_REPORT_HINT, '')
  } else if (referenceAnswers.length === 0) {
    lines.push('本次面试未识别出需要给出参考回答的技术问题。', '')
  } else {
    lines.push(
      '> 下列参考回答由模型根据问答清单生成，**仅供复盘参考**，不代表标准答案；编号对应第一节的问答条目。',
      ''
    )
    for (const item of referenceAnswers) {
      lines.push(`**${oneLine(item.qaId)}** ${oneLine(item.question)}`, '')
      lines.push(`**参考回答** ${oneLine(item.answer)}`, '')
    }
  }

  // 三、面试总结（两行短列表 + 约 100 字总结）
  lines.push('## 三、面试总结', '')
  const summary = report.summary
  if (summary == null) {
    lines.push(LEGACY_REPORT_HINT, '')
  } else {
    const covered = (summary.coveredTopics ?? []).filter((t) => t && t.trim() !== '')
    const gaps = (summary.gapTopics ?? []).filter((t) => t && t.trim() !== '')
    if (covered.length === 0 && gaps.length === 0 && !summary.summary) {
      lines.push('本次面试未生成总结。', '')
    } else {
      if (covered.length > 0) {
        lines.push(`- 本轮涉及知识点：${covered.map((t) => t.trim()).join('、')}`)
      }
      if (gaps.length > 0) {
        lines.push(`- 后续需补充：${gaps.map((t) => t.trim()).join('、')}`)
      }
      if (covered.length > 0 || gaps.length > 0) {
        lines.push('')
      }
      if (summary.summary && summary.summary.trim() !== '') {
        lines.push(oneLine(summary.summary), '')
      }
    }
  }

  return lines.join('\n')
}
