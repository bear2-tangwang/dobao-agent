import { ref, computed, nextTick, onMounted } from 'vue'
import hljs from 'highlight.js'
import { backendUrl as DEFAULT_BACKEND_URL } from '@/config'
import { AGENTS, SUPPORTED_FILE_TYPES, STREAM_TYPES } from '@/utils/constants'
import { generateId, formatFileSize } from '@/utils/format'
import { renderMarkdown, processReferences, processRecommendations } from '@/utils/markdown'
import {
  testConnection as apiTestConnection,
  loadChats as apiLoadChats,
  getChatDetail as apiGetChatDetail,
  deleteChat as apiDeleteChat,
  uploadFile as apiUploadFile,
  streamChat as apiStreamChat,
  stopStream as apiStopStream
} from '@/api'
import type { Agent, Chat, Message, StreamPayload } from '@/types'

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

        if (sessionData.messages && Array.isArray(sessionData.messages)) {
          sessionData.messages.forEach(msg => {
            if (msg.question) {
              target.messages.push({
                id: 'user_' + msg.id,
                role: 'user',
                content: msg.question,
                file: !!msg.fileid,
                fileName: msg.fileid ? '已上传文件' : null,
                thinking: [],
                reference: [],
                recommend: [],
                showThinking: false,
                showReference: false,
                hasThinking: false,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }

            if (msg.answer || msg.thinking) {
              const reference = processReferences(msg.reference)
              target.messages.push({
                id: 'assistant_' + msg.id,
                role: 'assistant',
                content: msg.answer || '',
                thinking: msg.thinking ? [msg.thinking] : [],
                reference,
                recommend: [],
                // 默认折叠状态,需要点击展开
                showThinking: false,
                showReference: false,
                hasThinking: !!msg.thinking,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }
          })
        }

        const firstUserMessage = target.messages.find(m => m.role === 'user')
        if (firstUserMessage && firstUserMessage.content) {
          target.title = firstUserMessage.content.substring(0, 20) + (firstUserMessage.content.length > 20 ? '...' : '')
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
      if (selectedFile.value) {
        alert('已上传文件，请先删除当前文件再上传新文件（限1个）')
        return
      }
      await handleFile(files[0]!)
    }
    input.value = ''
  }

  const removeFile = () => {
    selectedFile.value = null
    uploadedFileId.value = null
  }

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
      if (aiMsg.hasThinking) {
        aiMsg.showThinking = false
        if (currentThinkingSectionDiv) {
          const thinkingContent = currentThinkingSectionDiv.querySelector('.thinking-content')
          if (thinkingContent) {
            ;(thinkingContent as HTMLElement).style.display = 'none'
          }
        }
      }
      streamedContent += data.content
      updateStreamContent(streamedContent)
    } else if (data.type === STREAM_TYPES.THINKING && data.content) {
      if (!aiMsg.hasThinking) {
        if (currentThinkingSectionDiv) {
          currentThinkingSectionDiv.style.display = 'block'
        }
        if (currentThinkingContentDiv) {
          currentThinkingContentDiv.style.display = 'block'
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
          // 自动展开参考来源
          if (aiMsg.reference.length > 0) {
            aiMsg.showReference = true
          }
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
      showThinking: false,
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
    // 供模板直接使用的工具函数
    renderMarkdown,
    formatFileSize
  }
}