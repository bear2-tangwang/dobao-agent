import { STREAM_TYPES } from '@/utils/constants'
import type {
  SessionDetail,
  Reference,
  InterviewUploadVo,
  InterviewStatusVo,
  InterviewReport
} from '@/types'

/** Test backend connection */
export const testConnection = async (backendUrl: string): Promise<{ success: boolean; error?: string }> => {
  try {
    await fetch(`${backendUrl}/file/list`, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    })
    return { success: true }
  } catch (error) {
    return {
      success: false,
      error: '无法连接到后端服务，请确保后端在 ' + backendUrl + ' 运行'
    }
  }
}

/** Load chat list */
export const loadChats = async (backendUrl: string) => {
  try {
    const response = await fetch(`${backendUrl}/session/list?pageNum=1&pageSize=100`, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    })

    if (!response.ok) {
      throw new Error('获取会话列表失败')
    }

    const result = await response.json()
    const data = result as {
      code: number
      data?: { records?: Array<{ conversationId: string; question?: string; agentType?: string; fileid?: string }> }
    }

    if (data.code === 200 && data.data && data.data.records) {
      return data.data.records.map(item => ({
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
export const getChatDetail = async (backendUrl: string, chatId: string): Promise<SessionDetail | null> => {
  try {
    const response = await fetch(`${backendUrl}/session/${chatId}`, {
      method: 'GET',
      headers: { 'Accept': 'application/json' }
    })

    if (!response.ok) {
      throw new Error('获取会话详情失败')
    }

    const result = await response.json()
    const data = result as { code: number; data?: SessionDetail }

    if (data.code === 200 && data.data) {
      return data.data
    }
    return null
  } catch (error) {
    console.error('获取会话详情失败:', error)
    return null
  }
}

/** Delete chat */
export const deleteChat = async (backendUrl: string, chatId: string) => {
  try {
    const response = await fetch(`${backendUrl}/session/${chatId}`, {
      method: 'DELETE',
      headers: { 'Accept': 'application/json' }
    })

    if (!response.ok) {
      throw new Error('删除会话失败')
    }

    const result = (await response.json()) as { code: number; message: string }
    return {
      success: result.code === 200 || result.code === 0,
      message: result.message
    }
  } catch (error) {
    console.error('删除会话失败:', error)
    return {
      success: false,
      error: error instanceof Error ? error.message : String(error)
    }
  }
}

/** Upload file */
export const uploadFile = async (backendUrl: string, file: File) => {
  const formData = new FormData()
  formData.append('file', file)

  const response = await fetch(`${backendUrl}/file/upload`, {
    method: 'POST',
    body: formData
  })

  if (!response.ok) {
    throw new Error('文件上传失败')
  }

  const result = (await response.json()) as { code: number; data?: { fileId?: string }; message?: string }
  if (result.code === 200 && result.data) {
    return {
      success: true,
      fileId: result.data.fileId!
    }
  }
  throw new Error(result.message || '文件上传失败')
}

/** Build stream chat URL */
export const getStreamChatUrl = (backendUrl: string, selectedAgent: string, hasFile: boolean): string => {
  if (selectedAgent === 'ppt') {
    return `${backendUrl}/agent/pptx/stream`
  } else if (selectedAgent === 'deep') {
    return `${backendUrl}/agent/deep/stream`
  }
  return `${backendUrl}/agent/chat/stream`
}

/** Build stream SSE connection */
export const streamChat = async (
  backendUrl: string,
  agentId: string,
  query: string,
  conversationId: string,
  fileId?: string | null,
  signal?: AbortSignal
): Promise<ReadableStreamDefaultReader<Uint8Array>> => {
  const apiUrl = getStreamChatUrl(backendUrl, agentId, !!fileId)
  const url = new URL(apiUrl)
  url.searchParams.append('query', query)
  url.searchParams.append('conversationId', conversationId)
  if (fileId) {
    url.searchParams.append('fileId', fileId)
  }

  const response = await fetch(url.toString(), {
    method: 'GET',
    headers: {
      'Accept': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    },
    signal
  })

  if (!response.ok) {
    throw new Error(`HTTP error! status: ${response.status}`)
  }
  if (!response.body) {
    throw new Error('流式响应无 body')
  }
  return response.body.getReader()
}

/** Stop stream request */
export const stopStream = async (backendUrl: string, conversationId: string) => {
  try {
    const stopUrl = `${backendUrl}/agent/stop?conversationId=${conversationId}`
    const response = await fetch(stopUrl, { method: 'GET' })
    return await response.json()
  } catch (error) {
    console.warn('调用停止接口失败:', error)
    return null
  }
}

// ==================== 面试总结（步骤 5） ====================

/** 后端统一返回包装（与 BaseResult 对应） */
interface ApiResult<T> {
  code: number
  message?: string
  data?: T
}

/** 拆统一返回：code 非 200 视为业务错误，抛出 message */
const unwrap = <T>(result: ApiResult<T>, fallback: string): T => {
  if (result.code === 200 || result.code === 0) {
    if (result.data === undefined || result.data === null) {
      throw new Error(fallback + '：返回数据为空')
    }
    return result.data
  }
  throw new Error(result.message || fallback)
}

/**
 * 上传面试录音。
 *
 * 注意这里是**普通请求**而不是流式：multipart 落 MinIO 本身就要 1~3 秒，
 * 上传成功后前端立刻去开 SSE 流（见 `streamInterview`）。
 *
 * `conversationId` 是"这场面试属于哪个会话"的唯一凭据：带上它，后端才会在
 * `ai_session` 里写一行，刷新/换设备后仍能从会话列表找到并还原这场面试。
 */
export const uploadInterviewAudio = async (
  backendUrl: string,
  file: File,
  conversationId?: string | null,
  signal?: AbortSignal
): Promise<InterviewUploadVo> => {
  const formData = new FormData()
  formData.append('file', file)
  // 为空时不能拼出 `?conversationId=`：空串会被后端当成"有值但为空"，这里直接不带查询串
  const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''

  const response = await fetch(`${backendUrl}/interview/upload${query}`, {
    method: 'POST',
    body: formData,
    signal
  })
  if (!response.ok) {
    throw new Error(`音频上传失败（HTTP ${response.status}）`)
  }
  const result = (await response.json()) as ApiResult<InterviewUploadVo>
  return unwrap(result, '音频上传失败')
}

/** 面试总结 SSE 流地址 */
export const getInterviewStreamUrl = (backendUrl: string, interviewId: string): string =>
  `${backendUrl}/interview/${encodeURIComponent(interviewId)}/stream`

/** 查询面试处理状态（断流兜底 / 刷新页面恢复） */
export const getInterviewStatus = async (
  backendUrl: string,
  interviewId: string,
  signal?: AbortSignal
): Promise<InterviewStatusVo> => {
  const response = await fetch(`${backendUrl}/interview/${encodeURIComponent(interviewId)}/status`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    signal
  })
  if (!response.ok) {
    throw new Error(`查询状态失败（HTTP ${response.status}）`)
  }
  const result = (await response.json()) as ApiResult<InterviewStatusVo>
  return unwrap(result, '查询状态失败')
}

/** 取结构化报告 */
export const getInterviewReport = async (
  backendUrl: string,
  interviewId: string,
  signal?: AbortSignal
): Promise<InterviewReport> => {
  const response = await fetch(`${backendUrl}/interview/${encodeURIComponent(interviewId)}/report`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    signal
  })
  if (!response.ok) {
    throw new Error(`获取报告失败（HTTP ${response.status}）`)
  }
  const result = (await response.json()) as ApiResult<InterviewReport>
  return unwrap(result, '获取报告失败')
}

/** 报告 Markdown 下载地址（走浏览器原生下载） */
export const getInterviewDownloadUrl = (backendUrl: string, interviewId: string): string =>
  `${backendUrl}/interview/${encodeURIComponent(interviewId)}/report/download`

/** 重试：有文字稿只重跑分析，否则重新提交转写 */
export const retryInterview = async (backendUrl: string, interviewId: string): Promise<void> => {
  const response = await fetch(`${backendUrl}/interview/${encodeURIComponent(interviewId)}/retry`, {
    method: 'POST',
    headers: { Accept: 'application/json' }
  })
  if (!response.ok) {
    throw new Error(`重试失败（HTTP ${response.status}）`)
  }
  const result = (await response.json()) as ApiResult<string>
  unwrap(result, '重试失败')
}

/**
 * 发起面试总结 SSE 流。
 *
 * 与 `streamChat` 的区别：后端用的是**具名事件**（`event: xxx` + `data: {...}`），
 * 所以这里只负责把字节流交给调用方，解析交给 `useInterview`。
 */
export const streamInterview = async (
  backendUrl: string,
  interviewId: string,
  signal?: AbortSignal
): Promise<{ reader: ReadableStreamDefaultReader<Uint8Array>; response: Response }> => {
  const response = await fetch(getInterviewStreamUrl(backendUrl, interviewId), {
    method: 'GET',
    headers: {
      Accept: 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive'
    },
    signal
  })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  if (!response.body) {
    throw new Error('流式响应无 body')
  }
  return { reader: response.body.getReader(), response }
}

// Keep STREAM_TYPES export
export { STREAM_TYPES }
export type { Reference }
