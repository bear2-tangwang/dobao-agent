import { STREAM_TYPES } from '@/utils/constants'
import type { SessionDetail, Reference } from '@/types'

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

// Keep STREAM_TYPES export
export { STREAM_TYPES }
export type { Reference }
