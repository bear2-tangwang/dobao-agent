/**
 * 登录相关接口。
 */
import { get, post } from '@/api/http'
import { authBase } from '@/config'

/** 当前登录用户（与后端 AuthController#toView 对应） */
export interface CurrentUser {
  userId: string
  login: string | null
  nickname: string | null
  avatarUrl: string | null
  email: string | null
}

/** 登录方式探测结果 */
export interface AuthConfig {
  provider: string
  enabled: boolean
  authorizeUrl: string
  /** 登录成功后浏览器该落在哪个前端地址（由后端配置提供） */
  frontendBaseUrl?: string
  /** GitHub 会回跳到哪个主机名（由后端配置提供） */
  redirectUriHost?: string | null
}

/** 查询当前用户；未登录时 http 层会抛 UnauthorizedError */
export const fetchCurrentUser = (): Promise<CurrentUser> => get<CurrentUser>('/auth/me')

/** 探测登录方式是否已配置（不返回任何密钥） */
export const fetchAuthConfig = (): Promise<AuthConfig> => get<AuthConfig>('/auth/config')

/** 退出登录 */
export const logoutRequest = (): Promise<string> => post<string>('/auth/logout')

/**
 * 拼授权跳转地址。
 *
 * 走 `authBase`（后端根地址）而不是相对路径：授权与回跳都是**浏览器顶层跳转**，
 * 必须保证发起方与 GitHub 回跳方的主机名一致，会话 Cookie 才不会绑错站点。
 *
 * @param redirect 登录成功后要回到的前端站内路径
 */
export const buildAuthorizeUrl = (redirect?: string): string => {
  const url = new URL('/oauth/github/authorize', authBase)
  if (redirect) {
    url.searchParams.set('redirect', redirect)
  }
  return url.toString()
}
