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
  /** 面试会话的面试ID（后端在 agent_type=interview 的会话行上填 = fileid） */
  interviewId?: string
}

/** Session detail */
export interface SessionDetail {
  conversationId: string
  agentType?: string
  fileid?: string
  messages: SessionMessage[]
}

/**
 * 一条问答（对应后端 QaItem）。
 *
 * Q 与 A 都是**逐字原文**（由后端 QaListBuilder 直接格式化，未经模型改写），
 * 时间戳来自真实转写句子，正文会展示出来。
 */
export interface InterviewQaItem {
  qaId: string
  question: string
  questionBeginMs: number
  answer: string
  answerBeginMs: number
  answerEndMs: number
}

/** 技术问题的参考回答（对应后端 ReferenceAnswer，报告第二部分） */
export interface InterviewReferenceAnswer {
  /** 出自哪条问答，对应第一节的 Q001（后端已校验过编号真实存在） */
  qaId: string
  /** 从面试官原话里收敛出的技术问题 */
  question: string
  /** 参考回答（模型生成，仅供复盘参考，不是标准答案） */
  answer: string
}

/** 面试总结（对应后端 ReportSummary，报告第三部分） */
export interface InterviewReportSummary {
  /** 本轮涉及的知识点（短词） */
  coveredTopics: string[] | null
  /** 后续需要补充的知识点（短词） */
  gapTopics: string[] | null
  /** 约 100 字的总结性文字 */
  summary: string | null
}

/** 结构化报告（对应后端 InterviewReport / report_json） */
export interface InterviewReport {
  interviewId: string
  audioDurationMs: number
  generatedAt: string | null
  /** 一、问答清单：整场面试问答，Q/A 均为逐字原文 */
  qaList: InterviewQaItem[] | null
  /** 二、参考回答：只含技术问题；旧报告（无此节）为 null */
  referenceAnswers: InterviewReferenceAnswer[] | null
  /** 三、面试总结；旧报告（无此节）为 null */
  summary: InterviewReportSummary | null
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
   * <p>上传接口返回之前还没有 `interviewId`，面板据此显示"提交中"，
   * 而不是又露出一张上传卡（那张卡上的按钮会打断正在跑的场次）。
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
