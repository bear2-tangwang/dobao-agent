/** Agent */
export interface Agent {
  id: string
  name: string
  icon: string
}

/** Reference source */
export interface Reference {
  url: string
  title: string
  content: string
}

/** Message */
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

/** Chat */
export interface Chat {
  id: string
  title: string
  agentType?: string
  fileid?: string
  messages: Message[]
  isNew?: boolean
}

/** SSE Stream payload */
export interface StreamPayload {
  type: string
  content?: unknown
  count?: number
  data?: unknown
}

/** Session message */
export interface SessionMessage {
  id: number | string
  question?: string
  answer?: string
  thinking?: string
  reference?: unknown
  fileid?: string
  fileName?: string
  fileType?: string
  fileSize?: number
  createTime?: string
}

/** Session detail */
export interface SessionDetail {
  conversationId: string
  agentType?: string
  fileid?: string
  messages: SessionMessage[]
}
