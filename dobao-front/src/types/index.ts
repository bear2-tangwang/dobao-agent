/** 智能体 */
export interface Agent {
  id: string
  name: string
  icon: string
}

/** 参考来源 */
export interface Reference {
  url: string
  title: string
  content: string
}

/** 消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  file?: boolean
  fileName?: string | null
  thinking: string[]
  reference: Reference[]
  recommend: string[]
  showThinking: boolean
  showReference: boolean
  hasThinking: boolean
  copied?: boolean
  timestamp: number
  pptFile?: string
}

/** 会话 */
export interface Chat {
  id: string
  title: string
  agentType?: string
  fileid?: string
  messages: Message[]
  isNew?: boolean
}

/** SSE 流式帧 */
export interface StreamPayload {
  type: string
  content?: unknown
  count?: number
  data?: unknown
}

/** 会话详情中的单条记录 */
export interface SessionMessage {
  id: number | string
  question?: string
  answer?: string
  thinking?: string
  reference?: unknown
  fileid?: string
  createTime?: string
}

/** 会话详情接口返回 */
export interface SessionDetail {
  conversationId: string
  agentType?: string
  fileid?: string
  messages: SessionMessage[]
}