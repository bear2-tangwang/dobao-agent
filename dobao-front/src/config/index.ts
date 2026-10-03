/**
 * 全局配置
 *
 * 开发环境一律走**同源相对路径**，由 Vite 的 server.proxy 转发到后端 8888：
 *  - 浏览器视角同源 → 会话 Cookie 天然生效，不需要 CORS、不需要 SameSite=None；
 *  - 直连 http://localhost:8888 会踩两个坑：① 跨域要开 credentials + 精确 Origin；
 *    ② 主机名一旦和回跳地址不一致（localhost vs 127.0.0.1），Cookie 就绑错站点。
 *
 * 生产环境由 Nginx 把同一域名的这些路径反代到后端，这两个常量保持默认值即可。
 */

/**
 * 业务接口前缀。
 *
 * 刻意留空（而不是 '/api'）：后端既有路由就是 `/session`、`/file`、`/interview`、
 * `/agent`、`/auth` 这几个根路径，留空可以做到"前端零前缀 + 后端零改动"；
 * 加 '/api' 则必须在代理层做 rewrite，多一层容易配错的地方。
 */
export const apiBase = import.meta.env.VITE_API_BASE ?? ''

/**
 * 登录跳转与后端回跳所用的**后端根地址**。
 *
 * 必须与后端 `github.oauth.redirect-uri` 的主机名一致（都是 localhost），
 * 否则会话 Cookie 会落在另一个站点上，表现为"授权成功但仍是未登录"。
 */
export const authBase = import.meta.env.VITE_AUTH_BASE ?? 'http://localhost:8888'

/**
 * @deprecated 保留旧名字兼容既有调用点。
 * 值已从绝对地址改为空前缀，调用方拼出的 `${backendUrl}/session/list`
 * 等价于 `/session/list`，由代理转发到后端。
 */
export const backendUrl = apiBase
