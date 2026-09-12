import type { Agent } from '@/types'

/** 智能体列表 */
export const AGENTS: Agent[] = [
  { id: 'chat', name: '对话助手', icon: '💬' },
  { id: 'file', name: '文件问答', icon: '📁' },
  { id: 'ppt', name: 'PPT生成', icon: '📊' },
  { id: 'deep', name: '深度研究', icon: '🔬' }
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
  TEXT: 'text', // 文本内容
  THINKING: 'thinking', // 思考过程
  REFERENCE: 'reference', // 参考来源
  RECOMMEND: 'recommend', // 推荐问题
  COMPLETE: 'complete', // 完成
  DONE: '[DONE]' // 结束标记
} as const