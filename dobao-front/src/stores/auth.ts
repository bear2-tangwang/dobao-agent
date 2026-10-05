/**
 * 登录态 store。
 *
 * 用 `reactive` 手写而不是引 Pinia：整个应用只有这一份跨页面状态，
 * 引一个状态库的成本（依赖 + 心智）大于收益。
 */
import { computed, reactive } from 'vue'
import {
  buildAuthorizeUrl,
  fetchAuthConfig,
  fetchCurrentUser,
  logoutRequest,
  type AuthConfig,
  type CurrentUser
} from '@/api/auth'
import { UnauthorizedError } from '@/api/http'

/** 登录态：unknown 表示"还没问过后端"，用于路由守卫避免误跳登录页 */
export type AuthState = 'unknown' | 'authenticated' | 'anonymous'

const state = reactive({
  status: 'unknown' as AuthState,
  user: null as CurrentUser | null,
  config: null as AuthConfig | null
})

/** 后端回跳时带的 error 参数 → 给用户看的中文说明 */
const ERROR_MESSAGES: Record<string, string> = {
  denied: '你取消了 GitHub 授权，可以重新点击登录。',
  state: '登录状态已过期或校验失败，请重新登录。',
  missing_code: 'GitHub 没有返回授权码，请重新登录。',
  exchange: 'GitHub 授权失败，请稍后重试。',
  disabled: '该账号已被停用，请联系管理员。',
  server: '服务端处理登录时出错，请稍后重试。'
}

export const useAuth = () => {
  /** 是否已登录 */
  const isAuthenticated = computed(() => state.status === 'authenticated' && state.user !== null)

  /** 展示名：昵称优先，其次 GitHub 登录名 */
  const displayName = computed(() => state.user?.nickname || state.user?.login || '未登录')

  /**
   * 查询当前用户。
   *
   * @param force true 时即使已有结果也重新请求（路由守卫用，保证刷新页面后状态准确）
   */
  const fetchMe = async (force = false): Promise<boolean> => {
    if (!force && state.status !== 'unknown') {
      return state.status === 'authenticated'
    }
    try {
      state.user = await fetchCurrentUser()
      state.status = 'authenticated'
      return true
    } catch (error) {
      if (error instanceof UnauthorizedError) {
        state.user = null
        state.status = 'anonymous'
        return false
      }
      // 网络/服务端异常不应把用户当成"未登录"——那会把人踢到登录页反复循环。
      // 抛出交给调用方（路由守卫会放行到页面，由页面自己显示连接错误）。
      throw error
    }
  }

  /** 拉取登录方式配置（登录页用） */
  const loadConfig = async (): Promise<AuthConfig> => {
    if (state.config) return state.config
    state.config = await fetchAuthConfig()
    return state.config
  }

  /** 跳转 GitHub 授权 */
  const login = (redirect?: string): void => {
    window.location.href = buildAuthorizeUrl(redirect)
  }

  /** 退出登录 */
  const logout = async (): Promise<void> => {
    try {
      await logoutRequest()
    } finally {
      // 无论后端是否成功，前端状态都要清干净
      state.user = null
      state.status = 'anonymous'
    }
  }

  /** 把回跳携带的 error 参数翻译成提示语（没有错误时返回 null） */
  const messageForError = (code: string | null | undefined): string | null => {
    if (!code) return null
    return ERROR_MESSAGES[code] ?? '登录失败，请重试。'
  }

  return {
    state,
    isAuthenticated,
    displayName,
    fetchMe,
    loadConfig,
    login,
    logout,
    messageForError
  }
}
