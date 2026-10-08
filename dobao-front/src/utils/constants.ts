import type { Agent } from '@/types'

/** 智能体列表 */
export const AGENTS: Agent[] = [
  { id: 'chat', name: '对话助手', icon: 'fa-solid fa-comments' },
  { id: 'ppt', name: 'PPT生成', icon: 'fa-solid fa-file-powerpoint' },
  { id: 'deep', name: '深度研究', icon: 'fa-solid fa-microscope' },
  { id: 'interview', name: '面试总结', icon: 'fa-solid fa-microphone-lines' }
]

/** 支持上传的文件类型 */
export const SUPPORTED_FILE_TYPES = {
  mime: [
    'application/pdf',
    'application/msword',
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    'text/plain',
    'image/png',
    'image/jpeg',
    'image/jpg'
  ],
  extensions: ['pdf', 'doc', 'docx', 'txt', 'png', 'jpg', 'jpeg']
}

/**
 * 面试录音支持的扩展名与大小上限。
 * 两者必须与后端 `InterviewService.validate` / `FileInfo.isAudioType` /
 * `interview.asr.max-audio-bytes` 对齐：后端才是真正的闸门，前端这层只是为了"不合法不发请求"。
 */
export const AUDIO_EXTENSIONS = ['mp3', 'wav', 'm4a', 'aac', 'flac', 'amr']

/** 音频大小上限（80MB），与后端 `interview.asr.max-audio-bytes` 对齐 */
export const MAX_AUDIO_BYTES = 80 * 1024 * 1024

/** 流式消息类型 */
export const STREAM_TYPES = {
  TEXT: 'text',
  THINKING: 'thinking',
  REFERENCE: 'reference',
  RECOMMEND: 'recommend',
  COMPLETE: 'complete',
  DONE: '[DONE]'
} as const

/**
 * 面试总结 SSE 事件类型（对应后端 `GET /interview/{id}/stream`）。
 *
 * 后端返回的是**具名 SSE 事件**（`event: progress` + `data: {...}`），
 * 与对话用的 `type` 字段载荷不同，因此单独一套常量与解析分支，
 * 避免把两种协议混在一个 `processStreamData` 里。
 */
export const INTERVIEW_STREAM_EVENTS = {
  SNAPSHOT: 'snapshot',
  PROGRESS: 'progress',
  COMPLETE: 'complete',
  ERROR: 'error'
} as const

/** 面试状态（对应后端 InterviewStatus 枚举） */
export const INTERVIEW_STATUS = {
  UPLOADED: 'UPLOADED',
  TRANSCRIBING: 'TRANSCRIBING',
  TRANSCRIBED: 'TRANSCRIBED',
  ANALYZING: 'ANALYZING',
  READY: 'READY',
  FAILED: 'FAILED'
} as const