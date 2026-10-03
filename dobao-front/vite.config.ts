import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址（开发环境）
const BACKEND = 'http://localhost:8888'

/**
 * 开发环境把后端各路径代理到 8888，让浏览器视角**同源**。
 *
 * 这一步不是"可选优化"，而是会话 Cookie 能生效的前提：
 * 前后端不同源时，Cookie 需要 SameSite=None + Secure（localhost 上 Safari 不认），
 * CORS 还得把 allowCredentials(true) 与具体 Origin 配套设置，坑远多于收益。
 *
 * 这里**不做路径改写**（不加 /api 前缀也不 rewrite）：后端既有路由就是这几个根路径，
 * 前端用相对路径直接请求，两边都不用为代理改一行代码。
 */
const proxy = Object.fromEntries(
  ['/oauth', '/auth', '/session', '/file', '/interview', '/agent'].map((path) => [
    path,
    { target: BACKEND, changeOrigin: true }
  ])
)

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    /**
     * ⚠️ 必须显式绑定 127.0.0.1。
     *
     * 默认只绑 IPv6 回环（实测仅监听 `[::1]:5173`）。而 `localhost` 的解析顺序
     * 在 Windows / 浏览器上是 ::1 与 127.0.0.1 混着的 —— 一旦命中 IPv4，
     * 而你又恰好把地址写成 127.0.0.1，浏览器就会直接报 ERR_CONNECTION_REFUSED
     * （"127.0.0.1 拒绝连接"），看起来像服务没起，其实是没绑那个地址。
     */
    host: '127.0.0.1',
    port: 5173,
    // 端口被占时直接失败，而不是悄悄换到 5174 —— 会话 Cookie 与后端回跳地址
    // 都写死了 5173，换端口会连带 OAuth 回跳失效，宁可起不来也不要静默漂移
    strictPort: true,
    proxy
  }
})
