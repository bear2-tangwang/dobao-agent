import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import hljs from 'highlight.js'
import { backendUrl as DEFAULT_BACKEND_URL } from '@/config'
import { AGENTS, SUPPORTED_FILE_TYPES, STREAM_TYPES, AUDIO_EXTENSIONS } from '@/utils/constants'
import { generateId, formatFileSize } from '@/utils/format'
import { renderMarkdown, processReferences, processRecommendations } from '@/utils/markdown'
import { useInterview, createInterviewSession, isTerminalInterviewStatus } from '@/composables/useInterview'
import {
  testConnection as apiTestConnection,
  loadChats as apiLoadChats,
  getChatDetail as apiGetChatDetail,
  deleteChat as apiDeleteChat,
  uploadFile as apiUploadFile,
  streamChat as apiStreamChat,
  stopStream as apiStopStream
} from '@/api'
import type { Agent, Chat, Message, StreamPayload, InterviewSession } from '@/types'

export function useChat() {
  // ===== 配置和常量 =====
  const backendUrl = ref(DEFAULT_BACKEND_URL)
  const connectionError = ref<string | null>(null)
  const agents = ref<Agent[]>(AGENTS)

  // ===== 状态 =====
  const selectedAgent = ref('chat')
  const chatList = ref<Chat[]>([])
  const currentChatId = ref<string | null>(null)
  const inputMessage = ref('')
  const selectedFile = ref<File | null>(null)
  const uploadedFileId = ref<string | null>(null)
  const isUploading = ref(false)
  const isSending = ref(false)
  const currentRecommendMsgId = ref<string | null>(null)

  // ===== 面试总结（步骤 5） =====
  // 合规勾选：未勾选不允许提交（需求文档 §6 合规项）
  const interviewAgreed = ref(false)
  // 面试模式下选中的录音（不立刻上传，等用户点"开始总结"或面板按钮）
  const interviewFile = ref<File | null>(null)
  /**
   * "待提交"面板（选完文件、还没点开始总结的那条消息）与"正在处理"的消息。
   *
   * <p>记的是 **id + 所属会话 id**，不是消息对象：`chatList` 是深度响应式的，
   * `chat.messages` 里拿到的是 Proxy，把对象存起来再和 `messages` 比对很容易踩到
   * raw / proxy 不相等的问题。id 比较没有这个歧义。
   *
   * <p>一个会话里可能存在多条面试消息（多场面试），因此这里必须区分：
   * 待提交的那条、正在跑的那条、以及已经出过报告的那些（后者一概不碰）。
   */
  const pendingInterviewMsgId = ref<string | null>(null)
  const pendingInterviewChatId = ref<string | null>(null)
  const runningInterviewMsgId = ref<string | null>(null)

  /**
   * 面试进度对象**由每条消息各自持有**（`message.interview`），不再全应用共用一个。
   *
   * <p>共用一份会踩坑：一个会话里做第二场面试时 `Object.assign` 会把第一场的报告就地清空，
   * 第一场的气泡只剩一个空壳（用户看到的"上一场的 AI 气泡消失了，用户气泡还在"）。
   * 每场一个对象之后，多场面试互不影响；`useInterview` 的每个方法都接收目标 session，
   * 只就地改字段、从不换对象，所以流式进度照旧实时刷新。
   */
  const interview = useInterview(backendUrl)

  // 确认对话框状态
  const showConfirmDialog = ref(false)
  const confirmTitle = ref('确认操作')
  const confirmMessage = ref('')
  let confirmCallback: (() => void) | null = null

  // 引用
  const messagesContainer = ref<HTMLElement | null>(null)

  // 当前流式输出容器的 DOM 引用(与 static 原版一致,流式期间直接 innerHTML,不经过 Vue 响应式)
  let currentStreamContentDiv: HTMLElement | null = null
  let currentThinkingContentDiv: HTMLElement | null = null
  let currentThinkingSectionDiv: HTMLElement | null = null
  // 当前正在流式输出的消息与累积文本(停止/结束时回填给消息对象,由 Vue 渲染接管)
  let currentAiMsg: Message | null = null
  let streamedContent = ''
  let streamedThinking: string[] = []

  // 用于中断流式请求的 AbortController
  let abortController: AbortController | null = null

  // ===== 直接更新流式输出的 DOM(原 updateStreamContent) =====
  const updateStreamContent = (content: string, isThinking = false) => {
    const target = isThinking ? currentThinkingContentDiv : currentStreamContentDiv
    if (target) {
      target.innerHTML = renderMarkdown(content)
      target.querySelectorAll<HTMLElement>('pre code').forEach(block => {
        hljs.highlightElement(block)
      })
    }
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
  }

  // 流式结束收尾:把累积的流内容回填消息对象,清理状态
  const finalizeStream = () => {
    if (currentAiMsg) {
      currentAiMsg.content = streamedContent
      currentAiMsg.thinking = streamedThinking
      currentAiMsg = null
    }
    streamedContent = ''
    streamedThinking = []
    isSending.value = false
    abortController = null
    currentStreamContentDiv = null
    currentThinkingContentDiv = null
    currentThinkingSectionDiv = null
  }

  const scrollToBottom = () => {
    return nextTick(() => {
      if (messagesContainer.value) {
        messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      }
    })
  }

  // ===== 初始化 =====
  onMounted(async () => {
    await loadChatsFromStorage()
    createNewChat()
  })

  // ===== API 相关 =====
  const testConnection = async () => {
    const result = await apiTestConnection(backendUrl.value)
    if (result.success) {
      connectionError.value = null
    } else {
      connectionError.value = result.error || null
    }
  }

  const loadChatsFromStorage = async () => {
    chatList.value = await apiLoadChats(backendUrl.value)
  }

  const selectChat = async (chatId: string) => {
    currentChatId.value = chatId
    // 待提交的录音属于上一个会话：切走后不该还在输入区挂着 chip
    // （那条上传卡仍留在原会话里，回去再选文件会被复用）
    pendingInterviewMsgId.value = null
    pendingInterviewChatId.value = null
    interviewFile.value = null

    const chat = chatList.value.find(c => c.id === chatId)
    if (chat && chat.isNew) {
      return
    }

    const sessionData = await apiGetChatDetail(backendUrl.value, chatId)
    if (sessionData) {
      const target = chatList.value.find(c => c.id === chatId)
      if (target) {
        target.agentType = sessionData.agentType
        target.fileid = sessionData.fileid
        target.messages = []
        // 只用来决定"标题要不要按气泡文案覆盖"（见下方注释），
        // 面试分支本身一律按**消息**判断（msg.interviewId）：会话级开关会漏判 ——
        // SessionController.getSession 的 agentType 取的是第一行（按 create_time asc），
        // "先在会话里聊一句、再在同一会话里跑面试"时它是 chat，面试那条消息就走不到面试分支。
        const isInterviewSession = sessionData.agentType === 'interview'

        if (sessionData.messages && Array.isArray(sessionData.messages)) {
          // 面试消息要按 interviewId 逐条 await 还原状态/报告，forEach 里没法 await
          for (const msg of sessionData.messages) {
            if (msg.question) {
              target.messages.push({
                id: 'user_' + msg.id,
                role: 'user',
                // 面试消息的 question 是录音文件名（侧边栏标题要它），
                // 但用户当时发出去的文案是固定的这一句，回放时保持与实时一致；
                // 文件名交给下面的录音 chip 呈现
                content: msg.interviewId ? '请总结这段面试录音' : msg.question,
                file: !!msg.fileid,
                fileName: msg.fileid ? (msg.fileName || '已上传文件') : null,
                thinking: [],
                reference: [],
                recommend: [],
                showThinking: false,
                showReference: false,
                hasThinking: false,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }

            // 面试消息：AI 气泡挂进度面板，按 interviewId 把状态与报告还原回来
            if (msg.interviewId) {
              const interviewMsg: Message = {
                id: 'assistant_' + msg.id,
                role: 'assistant',
                content: '',
                thinking: [],
                reference: [],
                recommend: [],
                showThinking: false,
                showReference: false,
                hasThinking: false,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now(),
                interview: reactive(createInterviewSession())
              }
              target.messages.push(interviewMsg)
              const interviewSession = interviewMsg.interview!
              interviewSession.fileName = msg.fileName || msg.question || null
              // 顺序执行：先拿一次状态（终态会顺带把报告正文拉回来）
              await interview.tryRestore(msg.interviewId, interviewSession)
              continue
            }

            // 其余消息（含面试会话里没有 interviewId 的历史脏数据）都走普通 assistant 分支，
            // 渲染 answer/thinking，不再需要单独的退化分支
            if (msg.answer || msg.thinking) {
              const reference = processReferences(msg.reference)
              target.messages.push({
                id: 'assistant_' + msg.id,
                role: 'assistant',
                content: msg.answer || '',
                thinking: msg.thinking ? [msg.thinking] : [],
                reference,
                recommend: [],
                // 有思考过程时默认展开
                showThinking: !!msg.thinking,
                showReference: false,
                hasThinking: !!msg.thinking,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }
          }

          // 回放结束后，只给"最新一场仍在处理的面试"续订进度流：
          // 本 composable 的流与轮询都是单句柄（stop()/pollTimer 共享），
          // 同一会话同时维持多条流会互相打断 —— 这与实时交互的既有约束一致。
          // 这里不按会话类型收口：内部本来就按 m.interview 过滤，混排会话里的面试消息
          // 同样需要续订（会话级开关会让它们永远停在回放时的那一次查询结果上）。
          const pending = target.messages.filter(
            m => m.interview && !isTerminalInterviewStatus(m.interview.status)
          )
          const latest = pending[pending.length - 1]
          if (latest && latest.interview?.interviewId) {
            void interview.startById(latest.interview.interviewId, latest.interview, latest.interview.fileName)
          }
        }

        // 面试会话的标题由列表接口给出（question=录音文件名），不要用气泡文案覆盖；
        // 混排会话（首行是 chat、里面有面试消息）这里仍按普通会话处理，不去覆盖列表接口已经
        // 给好的标题 —— 面试消息的用户气泡文案是固定的一句"请总结这段面试录音"，
        // 拿它当标题会把用户原本的会话标题冲掉。
        if (!isInterviewSession) {
          const firstUserMessage = target.messages.find(m => m.role === 'user')
          if (firstUserMessage && firstUserMessage.content) {
            target.title = firstUserMessage.content.substring(0, 20) + (firstUserMessage.content.length > 20 ? '...' : '')
          }
        }
      }
    }

    scrollToBottom()
  }

  const deleteChat = async (chatId: string) => {
    confirmTitle.value = '确认删除'
    confirmMessage.value = '删除该会话后将无法恢复，是否继续？'
    confirmCallback = async () => {
      const result = await apiDeleteChat(backendUrl.value, chatId)
      if (result.success) {
        const index = chatList.value.findIndex(c => c.id === chatId)
        if (index !== -1) {
          chatList.value.splice(index, 1)
          if (currentChatId.value === chatId) {
            if (chatList.value.length > 0) {
              selectChat(chatList.value[0]!.id)
            } else {
              createNewChat()
            }
          }
        }
      } else {
        alert('删除失败: ' + (result.message || result.error || '未知错误'))
      }
      showConfirmDialog.value = false
    }
    showConfirmDialog.value = true
  }

  // ===== 文件处理 =====
  const handleFileSelect = async (event: Event) => {
    const input = event.target as HTMLInputElement
    const files = input.files
    if (files && files.length > 0) {
      const file = files[0]!
      const ext = file.name.split('.').pop()?.toLowerCase() || ''
      // 音频一律走面试链路：即使用户没切到"面试总结"，传录音的意图也是明确的
      if (AUDIO_EXTENSIONS.includes(ext)) {
        selectedAgent.value = 'interview'
        const chat = currentChat.value
        if (chat) {
          handleInterviewFile(file, chat)
        }
      } else if (selectedAgent.value === 'interview') {
        alert(`面试总结只接受音频文件（${AUDIO_EXTENSIONS.join(' / ')}），当前文件：.${ext || '未知'}`)
      } else if (selectedFile.value) {
        alert('已上传文件，请先删除当前文件再上传新文件（限1个）')
      } else {
        await handleFile(file)
      }
    }
    input.value = ''
  }

  const removeFile = () => {
    // 面试模式：清掉待提交的录音与对应的上传卡
    if (selectedAgent.value === 'interview') {
      discardPendingInterview()
      return
    }
    selectedFile.value = null
    uploadedFileId.value = null
  }

  /**
   * 丢弃"待提交"面板：把那条消息从会话里摘掉，并清掉挂着的录音。
   *
   * <p>只针对**还没开始**的那一场。已经开始处理或已经出过报告的消息一律不碰 ——
   * 之前的实现会把 `interviewMsg.interview` 置空，于是"上一场的 AI 气泡"变成一个
   * 只剩复制按钮的空壳（进度和报告一起消失），这正是本次要修的问题。
   */
  const discardPendingInterview = () => {
    const chat = chatList.value.find(c => c.id === pendingInterviewChatId.value)
    if (chat && pendingInterviewMsgId.value) {
      const index = chat.messages.findIndex(m => m.id === pendingInterviewMsgId.value)
      if (index !== -1) {
        chat.messages.splice(index, 1)
      }
    }
    pendingInterviewMsgId.value = null
    pendingInterviewChatId.value = null
    interviewFile.value = null
  }

  /** 找出某个会话里"待提交"的面试消息（选了文件但还没点开始总结的那条） */
  const findPendingInterviewMessage = (chat: Chat): Message | undefined =>
    chat.messages.find(
      m =>
        m.interview &&
        !m.interview.interviewId &&
        !m.interview.uploading &&
        !m.interview.errorMsg &&
        m.interview.steps.length === 0
    )

  /**
   * 面试模式：选中录音文件。
   *
   * <p>这里刻意**不上传**，只把文件挂起来并点亮"开始总结"按钮 ——
   * 80MB 的音频传起来要几秒到几十秒，用户点错了想换文件时不该已经花掉一次上传。
   *
   * <p>只创建/复用一条"待提交"的上传卡：已经跑起来的、已经出过报告的场次都属于历史，
   * 不能拿来复用（复用会把上一场的报告就地清空）。真正开始总结时，会在这条消息上
   * 开一场新的面试，并把它排到用户气泡之后（见 `sendMessage`）。
   *
   * @param chat 目标会话（由调用方传入，避免依赖后面才声明的 computed）
   */
  const handleInterviewFile = (file: File, chat: Chat) => {
    interviewFile.value = file
    // 本会话里已经有待提交的卡：复用它，只换文件名即可
    const own = chat.messages.find(m => m.id === pendingInterviewMsgId.value)
    const existing = own ?? findPendingInterviewMessage(chat)
    if (existing) {
      pendingInterviewMsgId.value = existing.id
      pendingInterviewChatId.value = chat.id
      return
    }
    const target = createInterviewMessage()
    chat.messages.push(target)
    pendingInterviewMsgId.value = target.id
    pendingInterviewChatId.value = chat.id
  }

  /** 弹出录音选择器（给进度面板里的"选择录音文件"用） */
  const interviewPickFile = () => {
    document.querySelector<HTMLInputElement>('.input-card input[type="file"]')?.click()
  }

  /**
   * 把"开始总结"按钮的点击转成一次正常的发送流程。
   * 复用 `sendMessage` 的好处是：面试和对话/PPT 走的是同一条入口，
   * 用户消息、标题、isNew 这些处理不会出现两套。
   */
  const interviewStartRequested = () => {
    void sendMessage()
  }

  /**
   * 面板上的"重新选择"：清掉待提交的录音与上传卡，再打开选择器。
   * 已经开始处理的场次不受影响（那时面板显示的是进度，不是上传卡）。
   */
  const interviewReselectRequested = () => {
    discardPendingInterview()
    interviewPickFile()
  }

  /**
   * 面试进度面板挂在 AI 消息上（复用消息气泡的排版与复制按钮）。
   *
   * <p>`interview` 用 `reactive()` 包一层：对象一旦被塞进响应式数组，
   * 拿原始对象直接改字段是不会触发重渲染的（Vue 只在**通过代理读**的时候才收集依赖），
   * 这里显式做成代理，谁引用它都是同一份响应式对象。
   */
  const createInterviewMessage = (): Message => ({
    id: generateId(),
    role: 'assistant',
    content: '',
    thinking: [],
    reference: [],
    recommend: [],
    showThinking: false,
    showReference: false,
    hasThinking: false,
    timestamp: Date.now(),
    interview: reactive(createInterviewSession())
  })

  /**
   * 取出这场面试要挂载的面板消息：
   * 复用"待提交"的那条（带着它自己的 session），否则新建一条。
   *
   * <p>顺序很重要：调用方已经把用户气泡 push 进去了，这里必须把面板消息
   * **挪到最后**，否则会出现"AI 气泡排在用户气泡之前"（选文件时就建卡，
   * 用户消息是点开始总结时才补上的），也就是用户看到的"气泡排序不对"。
   */
  const takeInterviewMessage = (chat: Chat): Message => {
    const pending = chat.messages.find(m => m.id === pendingInterviewMsgId.value)
    pendingInterviewMsgId.value = null
    pendingInterviewChatId.value = null

    const target = pending ?? findPendingInterviewMessage(chat)
    if (!target) {
      const created = createInterviewMessage()
      chat.messages.push(created)
      return created
    }
    if (!target.interview) {
      target.interview = reactive(createInterviewSession())
    }
    const index = chat.messages.indexOf(target)
    if (index !== -1 && index !== chat.messages.length - 1) {
      chat.messages.splice(index, 1)
      chat.messages.push(target)
    }
    return target
  }

  /**
   * 面试模式：校验 + 上传 + 开始接收进度（进度写进 `target.interview`）。
   *
   * <p>是"普通请求上传 → 立刻开 SSE 流"两步，而不是上传本身流式：
   * multipart 落 MinIO 要 1~3 秒，后端上传接口本身就是同步返回的。
   */
  const startInterview = async (target: Message) => {
    const file = interviewFile.value
    if (!file) {
      return
    }
    if (!interviewAgreed.value) {
      alert('请先勾选"我已获得录音中各方的同意"')
      return
    }
    const session = target.interview ?? (target.interview = reactive(createInterviewSession()))
    // 立刻收走待提交状态：上传期间输入区不该还挂着一个 chip，
    // 面板也会切到"提交中"，避免"重新选择"把在跑的这场打断
    interviewFile.value = null
    runningInterviewMsgId.value = target.id
    isSending.value = true
    try {
      // 进度由 InterviewPanel 直接读这条消息自己的 reactive 对象实时渲染，
      // 这里 await 到整场结束只是为了控制"处理中"的按钮状态
      // 带上当前会话ID：后端据此在 ai_session 写一行，这场面试才会进入会话历史
      await interview.start(file, session, currentChatId.value)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      if (session.stopped) {
        // 用户自己点的"停止"，请求被 abort 是预期结果，不打扰
        session.uploading = false
      } else if (session.interviewId) {
        // 已经有后端记录：面板里的错误块 + "重试"比弹窗更合适
        session.errorMsg = session.errorMsg || message
      } else {
        // 校验不通过 / 上传失败：连记录都没建起来，面板里没有可重试的东西，直接提示
        // （不写 errorMsg，这条上传卡保持"待提交"状态，用户可以换一个文件重来）
        alert('面试总结未开始：' + message)
      }
    } finally {
      isSending.value = false
      runningInterviewMsgId.value = null
    }
  }

  /** 面板上的"重试"（带这一场自己的 session） */
  const retryInterviewNow = async (session?: InterviewSession) => {
    if (!session) {
      return
    }
    isSending.value = true
    try {
      await interview.retry(session)
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error)
      session.errorMsg = message
    } finally {
      isSending.value = false
    }
  }

  /** 面板上的"载入报告内容"（流断过、或只拿到 reportUrl 时） */
  const refreshInterviewReport = async (session?: InterviewSession) => {
    if (!session) {
      return
    }
    isSending.value = true
    try {
      await interview.loadReport(session)
    } finally {
      isSending.value = false
    }
  }

  const toggleInterviewAgree = () => {
    interviewAgreed.value = !interviewAgreed.value
  }

  /** 这条消息的面试面板是否真的在处理（只有正在跑的那一场会转圈） */
  const isInterviewProcessing = (msg: Message): boolean => interview.isProcessing(msg.interview)

  /** 这条消息的面板按钮是否应该禁用（提交中 / 正在处理 / 等待开始） */
  const isInterviewBusy = (msg: Message): boolean =>
    isSending.value && (msg.id === runningInterviewMsgId.value || msg.id === pendingInterviewMsgId.value)

  /** 这条消息是不是"待提交"的上传卡（只有它显示已选录音名） */
  const interviewPendingFileName = (msg: Message): string | null =>
    msg.id === pendingInterviewMsgId.value ? interviewFile.value?.name ?? null : null

  const handleFile = async (file: File) => {
    selectedFile.value = file
    isUploading.value = true
    uploadedFileId.value = null

    try {
      const validTypes = SUPPORTED_FILE_TYPES.mime
      const validExts = SUPPORTED_FILE_TYPES.extensions
      const fileExt = file.name.split('.').pop()!.toLowerCase()

      if (!validTypes.includes(file.type) && !validExts.includes(fileExt)) {
        alert('不支持的文件类型，仅支持 PDF、Word、TXT、PNG、JPG 格式')
        removeFile()
        return
      }

      const result = await apiUploadFile(backendUrl.value, file)
      uploadedFileId.value = result.fileId
    } catch (error) {
      console.error('文件上传错误:', error)
      alert('文件上传失败: ' + (error instanceof Error ? error.message : String(error)))
      removeFile()
    } finally {
      isUploading.value = false
    }
  }

  // ===== 消息发送和流式处理 =====
  const processStreamData = (data: StreamPayload, aiMsg: Message) => {
    if (data.type === STREAM_TYPES.TEXT && data.content) {
      streamedContent += data.content
      updateStreamContent(streamedContent)
    } else if (data.type === STREAM_TYPES.THINKING && data.content) {
      if (!aiMsg.hasThinking) {
        if (currentThinkingSectionDiv) {
          currentThinkingSectionDiv.style.display = 'block'
          // v-show="msg.showThinking" 挂在 .thinking-content 上（.thinking-text 的父节点），
          // 只解除 .thinking-text 的 display 是无效的，父节点仍是 display:none
          const wrapper = currentThinkingSectionDiv.querySelector<HTMLElement>('.thinking-content')
          if (wrapper) {
            wrapper.style.display = 'block'
          }
          // 流式期间 aiMsg 是 push 进响应式数组的原始对象，直接改它不会触发 Vue 重渲染，
          // 折叠箭头的 class 绑定会一直停留在初始状态，这里手动同步一次
          const arrow = currentThinkingSectionDiv.querySelector<HTMLElement>('.collapse-arrow')
          if (arrow) {
            arrow.classList.add('is-open')
          }
        }
      }
      aiMsg.hasThinking = true
      aiMsg.showThinking = true
      streamedThinking.push(String(data.content))
      updateStreamContent(streamedThinking.join(''), true)
    } else if (data.type === STREAM_TYPES.REFERENCE && data.content) {
      try {
        let refsData: unknown = data.content
        if (typeof refsData === 'string') {
          refsData = JSON.parse(refsData)
        }
        const parsed = refsData as { data?: { content?: unknown } }
        if (parsed && parsed.data && parsed.data.content) {
          refsData = parsed.data.content
        }
        if (Array.isArray(refsData)) {
          aiMsg.reference = processReferences(refsData)
        }
      } catch (e) {
        console.warn('解析reference失败:', e, '原始数据:', data.content)
      }
    } else if (data.type === STREAM_TYPES.RECOMMEND && data.content) {
      try {
        let recommendData: unknown = data.content
        if (typeof recommendData === 'string') {
          recommendData = JSON.parse(recommendData)
        }
        if (Array.isArray(recommendData)) {
          aiMsg.recommend = processRecommendations(recommendData)
        }
      } catch (e) {
        console.warn('解析recommend失败:', e, '原始数据:', data.content)
      }
    } else if (data.type === STREAM_TYPES.COMPLETE) {
      currentRecommendMsgId.value = aiMsg.id
      finalizeStream()
    }
  }

  const sendMessage = async () => {
    if (isSending.value || isUploading.value) return

    // 面试总结走独立链路：选好录音后点"开始总结"即上传 + 开流
    if (selectedAgent.value === 'interview') {
      const chat = currentChat.value
      if (!chat) {
        return
      }
      if (!interviewFile.value) {
        alert('请先选择一段面试录音（mp3 / wav / m4a / aac / flac / amr）')
        return
      }
      if (chat.isNew) {
        chat.isNew = false
      }
      if (!chat.title || chat.title === '新对话') {
        chat.title = interviewFile.value.name.substring(0, 20)
      }
      // 把录音以"用户消息"的形式呈现，和对话/PPT 的交互保持一致
      chat.messages.push({
        id: generateId(),
        role: 'user',
        content: '请总结这段面试录音',
        file: true,
        fileName: interviewFile.value.name,
        thinking: [],
        reference: [],
        recommend: [],
        showThinking: false,
        showReference: false,
        hasThinking: false,
        timestamp: Date.now()
      })
      // 先把用户气泡放进列表，再把面试面板取出来排到它后面：
      // 面板是"选文件"时就建好的（那会儿还没有用户气泡），不挪的话会排在用户气泡之前
      const target = takeInterviewMessage(chat)
      await startInterview(target)
      return
    }

    if (!inputMessage.value.trim() && !selectedFile.value) return

    clearAllRecommendQuestions()
    const message = inputMessage.value.trim()
    const hasFile = !!selectedFile.value
    currentRecommendMsgId.value = null
    isSending.value = true

    inputMessage.value = ''
    const fileToSend = selectedFile.value
    const fileIdToSend = uploadedFileId.value

    const chat = currentChat.value
    if (chat && chat.isNew) {
      chat.isNew = false
    }

    const userMsg: Message = {
      id: generateId(),
      role: 'user',
      content: message,
      file: hasFile,
      fileName: fileToSend ? fileToSend.name : null,
      thinking: [],
      reference: [],
      recommend: [],
      showThinking: false,
      showReference: false,
      hasThinking: false,
      timestamp: Date.now()
    }
    chat!.messages.push(userMsg)

    if (chat!.messages.filter(m => m.role === 'user').length === 1 && message) {
      chat!.title = message.substring(0, 20) + (message.length > 20 ? '...' : '')
    }

    const aiMsg: Message = {
      id: generateId(),
      role: 'assistant',
      content: '',
      thinking: [],
      reference: [],
      recommend: [],
      // 思考过程默认展开：在"首次渲染"就置为 true，
      // 这样 .thinking-content 从一开始就是 display:'' ，
      // 不依赖流式期间的任何响应式更新或 DOM 补丁（流式时 aiMsg 是原始对象，
      // 改它不会触发重渲染，箭头/折叠状态都容易停在初始值）。
      // 有思考内容时 .thinking-section 才会显示，所以这里置 true 不会提前露出空面板。
      showThinking: true,
      showReference: false,
      hasThinking: false,
      timestamp: Date.now()
    }
    chat!.messages.push(aiMsg)

    await nextTick()

    // 定位最新一条 AI 消息的流式输出 DOM 节点
    const messageElements = messagesContainer.value?.querySelectorAll('.message.assistant')
    const aiMessageElement = messageElements ? messageElements[messageElements.length - 1] : null
    if (aiMessageElement) {
      currentStreamContentDiv = aiMessageElement.querySelector('.text-content')
      currentThinkingSectionDiv = aiMessageElement.querySelector('.thinking-section')
      currentThinkingContentDiv = aiMessageElement.querySelector('.thinking-text')
    }
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
    }
    currentAiMsg = aiMsg
    streamedContent = ''
    streamedThinking = []

    try {
      abortController = new AbortController()
      const reader = await apiStreamChat(
        backendUrl.value,
        selectedAgent.value,
        message || (hasFile ? '请分析这个文件' : ''),
        currentChatId.value!,
        hasFile ? fileIdToSend : null,
        abortController.signal
      )
      const decoder = new TextDecoder('utf-8')
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        let lineEndIndex
        while ((lineEndIndex = buffer.indexOf('\n')) !== -1) {
          const line = buffer.substring(0, lineEndIndex)
          buffer = buffer.substring(lineEndIndex + 1)

          if (line.startsWith('data: ')) {
            let dataStr = line.slice(6)

            if (dataStr.trim() === STREAM_TYPES.DONE) {
              finalizeStream()
              return
            }

            if (dataStr.trim() === '') continue

            try {
              if (dataStr.includes(STREAM_TYPES.DONE)) {
                const parts = dataStr.split(STREAM_TYPES.DONE)
                dataStr = parts[0]!
              }

              const data = JSON.parse(dataStr) as StreamPayload
              processStreamData(data, aiMsg)
            } catch (e) {
              console.warn('解析数据失败:', dataStr, e)
            }
          } else if (line.trim() !== '') {
            const cleanLine = line.replace(/^data:\s*/, '').trim()

            if (cleanLine === STREAM_TYPES.DONE) {
              finalizeStream()
              return
            }

            if (cleanLine && cleanLine.startsWith('{') && cleanLine.endsWith('}')) {
              try {
                const data = JSON.parse(cleanLine) as StreamPayload
                processStreamData(data, aiMsg)
              } catch (e) {
                console.warn('解析JSON行失败:', cleanLine, e)
              }
            }
          }
        }
      }

      // 循环退出后残留 buffer 兜底
      if (buffer.trim() !== '') {
        const cleanBuffer = buffer.replace(/^data:\s*/, '').trim()

        if (cleanBuffer === STREAM_TYPES.DONE) {
          finalizeStream()
        } else if (cleanBuffer && cleanBuffer.startsWith('{') && cleanBuffer.endsWith('}')) {
          try {
            const data = JSON.parse(cleanBuffer) as StreamPayload
            processStreamData(data, aiMsg)
          } catch (e) {
            console.warn('解析剩余数据失败:', cleanBuffer, e)
          }
        }
      }

      finalizeStream()
    } catch (error) {
      console.error('请求错误:', error)
      if (error instanceof Error && error.name !== 'AbortError') {
        streamedContent += '\n\n⚠️ 请求出错: ' + error.message
        updateStreamContent(streamedContent)
      }
      finalizeStream()
    }
  }

  const stopMessage = async () => {
    if (!isSending.value) return

    // 面试模式：停的是 SSE 进度流（后端任务仍在跑，靠轮询/重新进入续上）
    if (selectedAgent.value === 'interview') {
      const running = currentChat.value?.messages.find(m => m.id === runningInterviewMsgId.value)
      interview.stop()
      if (running?.interview) {
        // 标记"用户主动停了"：面板不再显示处理中，也不该因为 AbortError 弹一堆提示
        running.interview.stopped = true
        running.interview.uploading = false
      }
      isSending.value = false
      runningInterviewMsgId.value = null
      return
    }

    if (abortController) {
      abortController.abort()
      abortController = null
    }

    await apiStopStream(backendUrl.value, currentChatId.value!)

    finalizeStream()
  }

  // ===== UI 交互 =====
  const selectAgent = (agentId: string) => {
    if (selectedFile.value) {
      selectedFile.value = null
      uploadedFileId.value = null
    }
    // 切换模式时清掉另一个模式挂着的文件，避免"切走了却还带着上一个文件"
    if (agentId !== 'interview') {
      discardPendingInterview()
    }
    selectedAgent.value = agentId
  }

  const quickPrompt = (prompt: string) => {
    inputMessage.value = prompt
  }

  const clearAllRecommendQuestions = () => {
    if (currentChat.value && currentChat.value.messages) {
      currentChat.value.messages.forEach(msg => {
        msg.recommend = []
      })
    }
  }

  const sendRecommendQuestion = (question: string) => {
    clearAllRecommendQuestions()
    inputMessage.value = question
    sendMessage()
  }

  const createNewChat = () => {
    // 新会话：把上一个会话挂着的待提交录音与面板引用清掉
    pendingInterviewMsgId.value = null
    pendingInterviewChatId.value = null
    interviewFile.value = null

    const existingNewChat = chatList.value.find(c => c.isNew)
    if (existingNewChat) {
      currentChatId.value = existingNewChat.id
      return
    }

    const newChat: Chat = {
      id: generateId(),
      title: '新对话',
      messages: [],
      isNew: true
    }
    chatList.value.unshift(newChat)
    currentChatId.value = newChat.id
  }

  const toggleThinking = (msgId: string) => {
    const chat = currentChat.value
    if (!chat) return
    const msg = chat.messages.find(m => m.id === msgId)
    if (msg) {
      msg.showThinking = !msg.showThinking
    }
  }

  const toggleReference = (msgId: string) => {
    const chat = currentChat.value
    if (!chat) return
    const msg = chat.messages.find(m => m.id === msgId)
    if (msg) {
      msg.showReference = !msg.showReference
    }
  }

  const currentChat = computed<Chat | undefined>(() => {
    return chatList.value.find(c => c.id === currentChatId.value)
  })

  const isLastMessage = (msg: Message) => {
    const chat = currentChat.value
    if (!chat || chat.messages.length === 0) return false
    const lastMsg = chat.messages[chat.messages.length - 1]!
    return lastMsg.id === msg.id
  }

  const copyMessage = async (msg: Message) => {
    const textToCopy = msg.role === 'user' ? msg.content : msg.content || ''
    if (!textToCopy) return

    try {
      await navigator.clipboard.writeText(textToCopy)
      msg.copied = true
      setTimeout(() => {
        msg.copied = false
      }, 2000)
    } catch (error) {
      console.error('复制失败:', error)
    }
  }

  const canSend = computed(() => {
    if (isSending.value) return false
    if (isUploading.value) return false
    // 面试模式：要有录音 + 勾了合规同意才能提交
    if (selectedAgent.value === 'interview') {
      return !!interviewFile.value && interviewAgreed.value
    }
    return inputMessage.value.trim().length > 0 || !!selectedFile.value
  })

  const confirmOk = () => {
    if (confirmCallback) {
      confirmCallback()
    }
  }

  const confirmCancel = () => {
    showConfirmDialog.value = false
    confirmCallback = null
  }

  return {
    backendUrl,
    connectionError,
    agents,
    selectedAgent,
    chatList,
    currentChatId,
    currentChat,
    inputMessage,
    selectedFile,
    isUploading,
    isSending,
    currentRecommendMsgId,
    canSend,
    messagesContainer,
    selectAgent,
    quickPrompt,
    sendRecommendQuestion,
    isLastMessage,
    createNewChat,
    selectChat,
    deleteChat,
    removeFile,
    handleFileSelect,
    sendMessage,
    stopMessage,
    toggleThinking,
    toggleReference,
    copyMessage,
    testConnection,
    showConfirmDialog,
    confirmTitle,
    confirmMessage,
    confirmOk,
    confirmCancel,
    // 面试总结（步骤 5）
    interviewAgreed,
    interviewFile,
    // 每条面试消息各自持有 session，所以"是否在跑/是否忙/待提交的文件名"都要按消息算
    isInterviewProcessing,
    isInterviewBusy,
    interviewPendingFileName,
    toggleInterviewAgree,
    interviewStartRequested,
    interviewReselectRequested,
    retryInterviewNow,
    refreshInterviewReport,
    // 供模板直接使用的工具函数
    renderMarkdown,
    formatFileSize
  }
}
