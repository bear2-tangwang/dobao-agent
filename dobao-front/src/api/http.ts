/**
 * 统一请求封装。
 *
 * 所有业务请求都要经过这里：
 *  1. `credentials: 'include'` 必须带上，否则会话 Cookie 不会随请求发出；
 *  2. 后端统一返回体是 {@code {code, message, data}}，HTTP 200 也可能是业务失败；
 *  3. 登录过期要能**统一**跳登录页，而不是每个调用点各写一遍。
 */
import { apiBase } from '@/config'

/** 未登录（后端 code=401 或 HTTP 401） */
export class UnauthorizedError extends Error {
  constructor(message = '未登录') {
    super(message)
    this.name = 'UnauthorizedError'
  }
}

/** 其它业务/传输错误 */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly code: number
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface ApiResult<T> {
  code?: number
  message?: string
  data?: T
}

/** 成功码：BaseResult 的 CODE_SUCCESS=200，部分老接口用 0 */
const isOk = (code: number | undefined): boolean => code === 200 || code === 0

/**
 * 登录过期时的统一处理钩子。由 router 注册，避免 api 层直接依赖 router 造成循环引用。
 */
let onUnauthorized: (() => void) | null = null

export const setUnauthorizedHandler = (handler: () => void): void => {
  onUnauthorized = handler
}

const notifyUnauthorized = (): void => {
  if (onUnauthorized) onUnauthorized()
}

/**
 * 带完整错误语义的请求。
 *
 * @param path 形如 `/session/list`（apiBase 会自动拼在前面）
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const url = path.startsWith('http') ? path : `${apiBase}${path}`

  const response = await fetch(url, {
    credentials: 'include',
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.headers ?? {})
    }
  })

  if (response.status === 401) {
    notifyUnauthorized()
    throw new UnauthorizedError()
  }

  const body = (await response.json().catch(() => null)) as ApiResult<T> | null

  if (!response.ok) {
    throw new ApiError(body?.message ?? `请求失败（HTTP ${response.status}）`, response.status)
  }

  if (body && typeof body.code === 'number') {
    if (body.code === 401) {
      notifyUnauthorized()
      throw new UnauthorizedError(body.message ?? '登录已过期')
    }
    if (!isOk(body.code)) {
      throw new ApiError(body.message ?? '请求失败', body.code)
    }
    return body.data as T
  }

  // 少数接口直接返回裸数据
  return body as unknown as T
}

/** 便捷方法 */
export const get = <T>(path: string, init?: RequestInit): Promise<T> =>
  request<T>(path, { ...init, method: 'GET' })

export const post = <T>(path: string, init?: RequestInit): Promise<T> =>
  request<T>(path, { ...init, method: 'POST' })

export const del = <T>(path: string, init?: RequestInit): Promise<T> =>
  request<T>(path, { ...init, method: 'DELETE' })
