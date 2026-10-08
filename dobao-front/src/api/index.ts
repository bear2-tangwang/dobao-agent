import { STREAM_TYPES } from '@/utils/constants'
import { apiBase } from '@/config'
import { ApiError, UnauthorizedError, del, get, post, request } from '@/api/http'
import type {
  SessionDetail,
  Reference,
  InterviewUploadVo,
  InterviewStatusVo,
  InterviewReport
} from '@/types'

/** 从异常里取一句可读的话，取不到就用 fallback */
const messageOf = (error: unknown, fallback: string): string =>
  error instanceof Error && error.message ? error.message : fallback

/** Test backend connection */
export const testConnection = async (): Promise<{ success: boolean; error?: string }> => {
  try {
    await request<unknown>('/file/list')
    return { success: true }
  } catch (error) {
    // 未登录也说明后端是通的 —— 登录页需要能区分"服务没起来"和"没登录"
    if (error instanceof UnauthorizedError) {
      return { success: true }
    }
    return {
      success: false,
      error: `无法连接到后端服务，请确保后端在 ${apiBase} 运行`
    }
  }
}

/** Load chat list */
export const loadChats = async () => {
  try {
    const data = await get<{
      records?: Array<{ conversationId: string; question?: string; agentType?: string; fileid?: string }>
    }>('/session/list?pageNum=1&pageSize=100')

    if (data?.records) {
      return data.records.map((item) => ({
        id: item.conversationId,
        title: item.question
          ? item.question.substring(0, 20) + (item.question.length > 20 ? '...' : '')
          : '新对话',
        agentType: item.agentType,
        fileid: item.fileid,
        messages: []
      }))
    }
    return []
  } catch (error) {
    console.error('加载会话列表失败:', error)
    return []
  }
}

/** Get chat detail */
export const getChatDetail = async (chatId: string): Promise<SessionDetail | null> => {
  try {
    return await get<SessionDetail>(`/session/${encodeURIComponent(chatId)}`)
  } catch (error) {
    console.error('获取会话详情失败:', error)
    return null
  }
}

/** Delete chat */
export const deleteChat = async (chatId: string) => {
  try {
    const message = await del<string>(`/session/${encodeURIComponent(chatId)}`)
    return { success: true, message }
  } catch (error) {
    console.error('删除会话失败:', error)
    return {
      success: false,
      message: messageOf(error, '删除会话失败')
    }
  }
}

/** Upload file */
export const uploadFile = async (file: File) => {
  const formData = new FormData()
  formData.append('file', file)

  try {
    const data = await post<{ fileId?: string }>('/file/upload', { body: formData })
    if (!data?.fileId) {
      throw new Error('文件上传失败：返回数据为空')
    }
    return { success: true, fileId: data.fileId }
  } catch (error) {
    throw new Error(messageOf(error, '文件上传失败'))
  }
}

/** Build stream chat URL（同源相对地址，经 Vite 代理到后端） */
export const getStreamChatUrl = (selectedAgent: string): string => {
  if (selectedAgent === 'ppt') {
    return `${apiBase}/agent/pptx/stream`
  } else if (selectedAgent === 'deep') {
    return `${apiBase}/agent/deep/stream`
  }
  return `${apiBase}/agent/chat/stream`
}

/** Build stream SSE connection */
export const streamChat = async (
  agentId: string,
  query: string,
  conversationId: string,
  fileId?: string | null,
  signal?: AbortSignal
): Promise<ReadableStreamDefaultReader<Uint8Array>> => {
  const apiUrl = getStreamChatUrl(agentId)
  // apiUrl 是相对地址，new URL 需要基准
  const url = new URL(apiUrl, window.location.origin)
  url.searchParams.append('query', query)
  url.searchParams.append('conversationId', conversationId)
  if (fileId) {
    url.searchParams.append('fileId', fileId)
  }

  const response = await fetch(url.toString(), {
    method: 'GET',
    // 必须带会话 Cookie，否则后端拦截器会判定未登录
    credentials: 'include',
    headers: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive'
    },
    signal
  })

  if (response.status === 401) {
    throw new UnauthorizedError('登录已过期，请重新登录')
  }
  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  if (!response.body) {
    throw new Error('流式响应无 body')
  }
  return response.body.getReader()
}

/** Stop stream request */
export const stopStream = async (conversationId: string) => {
  try {
    return await get<Record<string, unknown>>(
      `/agent/stop?conversationId=${encodeURIComponent(conversationId)}`
    )
  } catch (error) {
    console.warn('调用停止接口失败:', error)
    return null
  }
}

// ==================== 面试总结 ====================

/**
 * 上传面试录音。
 *
 * 这里是**普通请求**而不是 SSE：multipart 落 MinIO 本身要 1~3 秒，
 * 上传成功后前端立刻去开进度流（见 `streamInterview`）。
 *
 * `conversationId` 是"这场面试属于哪个会话"的唯一凭据：带上它，后端才会在
 * `ai_session` 里写一行，刷新/换设备后仍能从会话列表找到并还原这场面试。
 */
export const uploadInterviewAudio = async (
  file: File,
  conversationId?: string | null,
  signal?: AbortSignal
): Promise<InterviewUploadVo> => {
  const formData = new FormData()
  formData.append('file', file)
  // 为空时不能拼出 `?conversationId=`：空串会被后端当成"有值但为空"，这里直接不带查询串
  const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''

  return post<InterviewUploadVo>(`/interview/upload${query}`, { body: formData, signal })
}

/** 面试总结 SSE 流地址 */
export const getInterviewStreamUrl = (interviewId: string): string =>
  `${apiBase}/interview/${encodeURIComponent(interviewId)}/stream`

/** 查询面试处理状态（断流兜底 / 刷新页面恢复） */
export const getInterviewStatus = async (
  interviewId: string,
  signal?: AbortSignal
): Promise<InterviewStatusVo> =>
  get<InterviewStatusVo>(`/interview/${encodeURIComponent(interviewId)}/status`, { signal })

/** 取结构化报告 */
export const getInterviewReport = async (
  interviewId: string,
  signal?: AbortSignal
): Promise<InterviewReport> =>
  get<InterviewReport>(`/interview/${encodeURIComponent(interviewId)}/report`, { signal })

/** 报告 Markdown 下载地址（走浏览器原生下载） */
export const getInterviewDownloadUrl = (interviewId: string): string =>
  `${apiBase}/interview/${encodeURIComponent(interviewId)}/report/download`

/** 重试：有文字稿只重跑分析，否则重新提交转写 */
export const retryInterview = async (interviewId: string): Promise<void> => {
  await post<string>(`/interview/${encodeURIComponent(interviewId)}/retry`)
}

/**
 * 发起面试总结 SSE 流。后端用的是**具名事件**（`event: xxx` + `data: {...}`），
 * 与对话接口的 `type` 字段载荷不同，因此这里只把字节流交给调用方，解析交给 `useInterview`。
 */
export const streamInterview = async (
  interviewId: string,
  signal?: AbortSignal
): Promise<{ reader: ReadableStreamDefaultReader<Uint8Array>; response: Response }> => {
  const response = await fetch(getInterviewStreamUrl(interviewId), {
    method: 'GET',
    credentials: 'include',
    headers: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive'
    },
    signal
  })
  if (response.status === 401) {
    throw new UnauthorizedError('登录已过期，请重新登录')
  }
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('流式响应无 body')
  }
  return { reader: response.body.getReader(), response }
}

export { STREAM_TYPES, ApiError, UnauthorizedError }
export type { Reference }
