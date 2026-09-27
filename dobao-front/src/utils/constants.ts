import type { Agent } from '@/types'

/** 智能体列表 */
export const AGENTS: Agent[] = [
  { id: 'chat', name: '对话助手', icon: 'fa-solid fa-comments' },
  { id: 'ppt', name: 'PPT生成', icon: 'fa-solid fa-file-powerpoint' },
  { id: 'deep', name: '深度研究', icon: 'fa-solid fa-microscope' }
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

/** 流式消息类型 */
export const STREAM_TYPES = {
  TEXT: 'text',
  THINKING: 'thinking',
  REFERENCE: 'reference',
  RECOMMEND: 'recommend',
  COMPLETE: 'complete',
  DONE: '[DONE]'
} as const