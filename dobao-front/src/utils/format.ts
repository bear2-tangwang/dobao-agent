/** 生成唯一 ID */
export const generateId = (): string => {
  return 'chat_' + Date.now() + '_' + Math.random().toString(36).substring(2, 11)
}

/** 格式化文件大小 */
export const formatFileSize = (bytes: number): string => {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}