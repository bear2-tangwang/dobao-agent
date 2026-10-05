import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 后端地址（开发环境）
const BACKEND = 'http://localhost:8888'

/**
 * 开发环境把后端各路径代理到 8888，让浏览器视角**同源** —— 这是会话 Cookie
 * 能生效的前提：跨源时要配 SameSite=None + Secure 与带 credentials 的 CORS。
 *
 * 这里**不做路径改写**（不加 /api 前缀也不 rewrite）：后端既有路由就是这几个根路径，
 * 前端用相对路径直接请求，两边都不用为代理改一行代码。
 *
 * ⚠️ 这几个前缀同时也是「前端路由禁区」：落在这些前缀下的 SPA 路由会先被转发到后端，
 * 前端页面永远渲染不到（登录回跳页因此是 /login/callback）。匹配是**按字符串前缀**，
 * /oauth-callback、/oauthx 同样会被代理走。
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
    // 显式绑 127.0.0.1：默认只绑 IPv6 回环，而 localhost 的解析顺序在 IPv4/IPv6 之间
    // 不稳定，命中 IPv4 时会表现为"拒绝连接"，看起来像服务没起
    host: '127.0.0.1',
    port: 5173,
    // 端口被占时直接失败，而不是悄悄换到 5174 —— 会话 Cookie 与后端 OAuth 回跳地址
    // 都写死了 5173，换端口会连带回跳失效，宁可起不来也不要静默漂移
    strictPort: true,
    proxy
  }
})
