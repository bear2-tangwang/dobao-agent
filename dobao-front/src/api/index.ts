import { STREAM_TYPES } from '@/utils/constants'
import type { SessionDetail, Reference } from '@/types'

/** 测试后端连接(实际探测 /file/list 是否可达) */
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

/** 加载会话列表 */
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

/** 获取会话详情 */
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

/** 删除会话 */
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

/** 上传文件 */
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

/** 根据智能体选择流式聊天 URL */
export const getStreamChatUrl = (backendUrl: string, selectedAgent: string, hasFile: boolean): string => {
  if (hasFile) {
    return `${backendUrl}/agent/file/stream`
  } else if (selectedAgent === 'ppt') {
    return `${backendUrl}/agent/pptx/stream`
  } else if (selectedAgent === 'deep') {
    return `${backendUrl}/agent/deep/stream`
  }
  return `${backendUrl}/agent/chat/stream`
}

/** 建立流式 SSE 连接,返回 reader 供逐行解析 */
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

/** 停止流式请求 */
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

// 为保持与原引用一致,导出 STREAM_TYPES
export { STREAM_TYPES }
export type { Reference }