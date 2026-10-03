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
 * 面试总结 · 上传 + 流式进度（编码方案步骤 5）。
 *
 * <h3>两条通道，各管一件事</h3>
 * <ul>
 *   <li><b>SSE 流（`GET /interview/{id}/stream`）</b>：把"已经发生的事"实时推给页面，
 *       让用户在 2~8 分钟的处理期里看到进度，而不是干等一个转圈。</li>
 *   <li><b>轮询兜底（`GET /interview/{id}/status`）</b>：流断掉（切标签页、锁屏、代理超时、
 *       后端还没实现该端点）时接管，每 3~10 秒自动拉一次，直到终态。</li>
 * </ul>
 *
 * <p><b>为什么兜底不能省</b>：真正的事实来源是数据库 + MinIO（后端是 `@Async` 后台任务，
 * 断线照跑），流只是通知。任何长连接都撑不住 40 分钟，所以"流断了进度就丢"是不可接受的；
 * 反过来只做轮询又会留下 2~8 分钟的空白期。两条都要。
 *
 * <p><b>去重</b>：流和兜底可能报同一件事（比如都报"转写完成"），
 * 因此每个阶段有稳定的语义 key（{@link InterviewStepKey}），相同 key 只记一条，
 * 只更新文案 —— 这样用户不会看到"转写完成"出现两次。
 *
 * <h3>为什么 session 由调用方传入，而不是在 composable 内部持有一个</h3>
 * 进度是一条条异步推进来的，`pushStep()` 必须改到**消息气泡真正渲染的那个对象**上，
 * 否则界面要等整场跑完才刷新（拷贝切断了响应式，见 `docs/interview-step5-frontend.md` §5）。
 *
 * <p>但"整个应用只保留一个 session"又会踩另一个坑：一个会话里做第二场面试时，
 * `Object.assign` 会把第一场的报告就地清空，第一场的气泡只剩一个空壳。
 * 所以现在的约定是：**每一条面试消息各自拥有一个 session 对象**（`Message.interview`），
 * 本 composable 的所有方法都接收这个对象作为参数，只做**就地修改**、从不替换对象 ——
 * 既保住了响应式，又让多场面试互不影响。
 */

/**
 * 兜底轮询参数。
 *
 * <p>SSE 是主通道，轮询只在"流重连也失败"时才启动；首轮间隔取 {@value #POLL_INITIAL_MS} 毫秒：
 * 实测单个阶段（问答整理、报告收尾）可能只有十几秒，间隔太长会整段错过，
 * 上限也不再放到 45 秒（那等于用户的每一步都慢半拍）。
 */
const POLL_INITIAL_MS = 5000
const POLL_MAX_MS = 30000
const POLL_BACKOFF = 1.4

/**
 * 断流后的重连退避（毫秒）。
 *
 * <p>长连接被代理/tab 挂起掐断是常态，而重连是**零成本**的：后端每次连接都会先补一帧
 * snapshot（当前状态、句数、说话人数都在），所以重连不会丢阶段、也不会重复记步骤
 * （步骤按语义 key 去重）。用尽这些退避仍然拿不到终态，才降级为轮询。
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
 * 换一场面试时用 `Object.assign(session, createInterviewSession())` 就地重置字段
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

export function useInterview(backendUrl: { value: string }) {
  /** 流 + 轮询共用的取消句柄：stop 时一起断掉 */
  let abortController: AbortController | null = null
  let pollTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 某一场面试是否还在处理中。
   *
   * <p>是普通函数而不是 computed：界面上同时可能存在多条面试消息，
   * 谁在跑要看各自的 session。在模板里调用它同样会被依赖收集到（渲染副作用里读到了
   * session 的字段），所以进度一变就会重新渲染。
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

  // ==================== 状态与步骤维护 ====================

  /**
   * 就地重置一场面试（**不换对象**，见文件头的说明）。
   * 用 `Object.assign` 一次性覆盖全部字段，避免漏掉某个字段导致上一场的残留。
   */
  const resetSession = (session: InterviewSession) => {
    Object.assign(session, createInterviewSession())
  }

  /**
   * 记一条进度。同 key 只保留一条（更新文案），避免流与兜底重复记录。
   */
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

  // ==================== 入口 ====================

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
      uploaded = await uploadInterviewAudio(backendUrl.value, file, conversationId, abortController.signal)
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

  /**
   * 直接对某个 interviewId 开始接收进度（重试、或从历史记录恢复时用）。
   */
  const startById = async (interviewId: string, session: InterviewSession, fileName: string | null = null) => {
    stop()
    resetSession(session)
    session.interviewId = interviewId
    session.fileName = fileName
    persistId(interviewId)
    await runProgress(session)
  }

  /**
   * 处理失败后重试：有文字稿只重跑分析，否则重新提交转写（后端 `retry` 里判断）。
   */
  const retry = async (session: InterviewSession) => {
    const id = session.interviewId
    if (!id) {
      return
    }
    session.errorMsg = null
    session.degraded = false
    await retryInterview(backendUrl.value, id)
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

  // ==================== 进度通道 ====================

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
    // 走到这里说明流没能把这场面试带到终态。**无论"正常结束"还是报错都算降级**：
    // 早期实现只在抛错时置位，流被中间层静默掐断时页面会一直冻在最后一条步骤上，
    // 用户完全看不出发生了什么。
    const detail = streamError instanceof Error ? streamError.message : String(streamError ?? '进度流已结束')
    console.warn('面试进度流中断，降级为轮询:', detail)
    session.degraded = true
    await startPolling(session)
  }

  /**
   * 读流 + 断线重连，直到终态、用户停止、或重试次数用尽。
   *
   * <p>为什么不是"断了就直接轮询"：轮询最快也要 5 秒一轮，而重连是即时的，
   * 且后端连接时会补 snapshot，两者配合最省事也最不容易让用户看到"卡住"。
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
   * 后端发的是**规范 SSE**：`event: progress` + `data: {json}` + 空行，
   * 心跳是注释行 `:ping`；与对话接口的 `{"type": ...}` 载荷不同，所以这里单独一套解析。
   *
   * <p><b>不要再"容忍"非规范帧</b>：2026-10 修过一个问题 —— 后端曾经自己拼好帧文本再发，
   * 被 Spring MVC 二次包成 `data:event: progress`，这里 `JSON.parse` 直接失败、
   * 所有事件被静默丢弃。修复方向是让后端输出结构化事件（见 `InterviewProgressHub`），
   * 并加了 `InterviewProgressStreamIT` 断言线上字节；前端只要按规范解析即可，
   * 遇到解析不了的内容只记一条 warn，不要试图猜格式。
   */
  const runStream = async (session: InterviewSession) => {
    const id = session.interviewId
    if (!id) {
      return
    }
    const controller = abortController
    const { reader } = await streamInterview(backendUrl.value, id, controller?.signal)

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
      // 重连 / 刷新后的当前态：字段与 status 接口对齐，因此不必再补一次状态查询
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
        str(payload.downloadUrl) || getInterviewDownloadUrl(backendUrl.value, session.interviewId || '')
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
        const status = await getInterviewStatus(backendUrl.value, session.interviewId)
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

  // ==================== 报告恢复 ====================

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
      const report = await getInterviewReport(backendUrl.value, id)
      session.report = report
      session.reportUrl = session.reportUrl || ''
      session.downloadUrl = getInterviewDownloadUrl(backendUrl.value, id)
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

  /**
   * 静默恢复：查一次状态，已就绪就把报告拉回来。
   * 用于"页面刷新后重新进入同一场面试"，失败不打扰用户。
   */
  const tryRestore = async (interviewId: string, session: InterviewSession): Promise<boolean> => {
    try {
      const status = await getInterviewStatus(backendUrl.value, interviewId)
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

  // ==================== 本地续跑标记 ====================

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
      return await getInterviewReport(backendUrl.value, interviewId)
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

// ==================== 报告 → Markdown ====================

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

/** 旧版报告（重构前生成）缺少参考回答/总结时的统一提示，与后端 InterviewReportRenderer 同文案 */
const LEGACY_REPORT_HINT = '本场报告生成于旧版本，未包含本节内容；重新生成后即可看到。'

/**
 * 结构化报告 → Markdown。
 *
 * <p>2026-10 重构后报告固定三节：**问答清单 / 参考回答 / 面试总结**。
 * 原「知识点清单」「待补充知识点」合并进总结，「完整对话」附录整体删除
 * （逐句原文仍在后端的 `transcript_json`，报告不再重复携带）。
 *
 * <p>问答清单仍<b>恢复展示时间戳</b>：Q 与 A 都是后端 `QaListBuilder` 从转写句子
 * 直接格式化出来的逐字原文，毫秒值也直接取自真实句子，因此是可信的定位信息 ——
 * 与后端 `InterviewReportRenderer` 保持同一口径。
 *
 * <p>旧版报告（`referenceAnswers` / `summary` 为 null）给出"重新生成"提示，
 * 与后端渲染器的文案一致，避免同一份报告在页面与下载文件里长得不一样。
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
