/**
 * 全局配置
 * 后端地址通过 .env 中的 VITE_BACKEND_URL 配置
 */
export const backendUrl = import.meta.env.VITE_BACKEND_URL || 'http://localhost:8888'