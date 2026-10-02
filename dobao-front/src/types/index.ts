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
  /** 面试总结消息：进度与报告的数据源（存在时 MessageItem 渲染进度面板） */
  interview?: InterviewSession
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

// ==================== 面试总结（步骤 5） ====================

/**
 * 一条问答（对应后端 QaItem）。
 *
 * 2026-10 重构：Q 与 A 都是**逐字原文**（由后端 QaListBuilder 直接格式化，未经模型改写），
 * 因此没有 answerSummary / answerQuotes / topics 三个字段了；
 * 时间戳直接来自转写句子，正文会展示出来。
 */
export interface InterviewQaItem {
  qaId: string
  question: string
  questionBeginMs: number
  answer: string
  answerBeginMs: number
  answerEndMs: number
}

/** 知识点主题（对应后端 KnowledgeTopic） */
export interface InterviewKnowledgeTopic {
  topic: string
  points: string[]
  relatedQaIds: string[]
}

/** 待补充知识点（对应后端 KnowledgeGap） */
export interface InterviewKnowledgeGap {
  point: string
  performance: string
  why: string
  directions: string[]
}

/** 完整对话的一行（对应后端 TranscriptLine，报告第四部分） */
export interface InterviewTranscriptLine {
  role: string
  beginMs: number
  text: string
}

/** 结构化报告（对应后端 InterviewReport / report_json） */
export interface InterviewReport {
  interviewId: string
  audioDurationMs: number
  generatedAt: string | null
  qaList: InterviewQaItem[] | null
  knowledgeTopics: InterviewKnowledgeTopic[] | null
  knowledgeGaps: InterviewKnowledgeGap[] | null
  /** 第四部分：整场对话逐句原话（未改写），用于核对问答清单是否完整 */
  transcript: InterviewTranscriptLine[] | null
}

/** 状态接口返回（对应后端 InterviewStatusVO） */
export interface InterviewStatusVo {
  interviewId: string
  status: string
  /** 面向用户的一句中文说明（后端 InterviewStatus.description()，前端不再维护第二张文案表） */
  stageDescription: string
  errorMsg: string | null
  audioDurationMs: number | null
  speechDurationMs: number | null
  speakerCount: number | null
  sentenceCount: number | null
  reportReady: boolean
}

/** 上传接口返回（对应后端 InterviewUploadVO） */
export interface InterviewUploadVo {
  interviewId: string
  status: string
  fileName: string | null
  fileSize: number | null
  reused: boolean
}

/** 一条进度的语义 key（用于去重，避免流与兜底轮询重复记一次） */
export type InterviewStepKey =
  | 'uploaded'
  | 'transcribing'
  | 'transcribed'
  | 'analyzing'
  | 'extracting'
  | 'reporting'
  | 'ready'
  | 'error'

/** 进度步骤（渲染成对话气泡里的逐条记录） */
export interface InterviewStep {
  key: InterviewStepKey
  text: string
  done?: boolean
}

/** 面试总结的整场会话状态 */
export interface InterviewSession {
  interviewId: string | null
  status: string
  fileName: string | null
  fileSize: number | null
  steps: InterviewStep[]
  /** 已格式化出的问答条目数量（流式阶段实时更新） */
  qaCount: number
  reportUrl: string | null
  downloadUrl: string | null
  report: InterviewReport | null
  errorMsg: string | null
  /** 是否有过断流（前端界面上提示"进度可能不完整"） */
  degraded: boolean
  /**
   * 是否正在上传录音。
   *
   * <p>纯前端状态：上传接口返回之前还没有 `interviewId`，面板据此显示"提交中"，
   * 而不是又露出一张上传卡（那张卡上的"重新选择/开始总结"会打断正在跑的场次）。
   */
  uploading?: boolean
  /**
   * 用户是否主动点了"停止"。
   *
   * <p>后端任务是 `@Async` 的，停止只意味着**前端不再接收进度**；
   * 有了这个标记，面板不会一直挂着"处理中"的转圈，用户也可以稍后点"载入报告内容"。
   */
  stopped?: boolean
}
