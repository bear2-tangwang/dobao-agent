/**
 * 全局配置
 *
 * 开发环境一律走**同源相对路径**，由 Vite 的 server.proxy 转发到后端 8888：
 * 直连 http://localhost:8888 属于跨源，需要 credentials + 精确 Origin，
 * 且主机名一旦与回跳地址不一致（localhost vs 127.0.0.1）Cookie 就会绑错站点。
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
 * 主机名必须与后端 `github.oauth.redirect-uri` 的主机名一致：会话 Cookie 按主机名
 * 隔离（端口不影响），不一致时 authorize 写的 state 与回调读它的地方不是同一个
 * Cookie，`session.getAttribute(STATE_KEY)` 拿到 null，表现为"授权成功但仍是未登录"
 * （?error=state）。
 *
 * 默认值与 `dobao-front/.env` 保持一致，避免 .env 缺失时静默退回另一个主机名。
 */
export const authBase = import.meta.env.VITE_AUTH_BASE ?? 'http://127.0.0.1:8888'
