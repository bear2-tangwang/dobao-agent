# 豆豆 · AI 智能体前端

Vue 3 + TypeScript + Vite 的「豆豆」聊天前端：多智能体对话（对话助手 / PPT 生成 / 深度研究 /
面试总结），SSE 流式输出，GitHub OAuth 登录，数据按用户隔离。

## 技术栈

- Vue 3.5（`<script setup>`）+ TypeScript + Vue Router
- Vite 8 + `vue-tsc`，无 UI 组件库，手写 CSS（`src/style.css` + 按功能拆分的样式文件）
- Marked + marked-highlight + highlight.js（Markdown 渲染与代码高亮）、DOMPurify（防 XSS）
- Font Awesome 图标

## 开发

```bash
npm install
npm run dev        # http://127.0.0.1:5173
npm run type-check # vue-tsc 类型检查
npm run build      # 类型检查 + 构建到 dist/
```

后端（`dobao-backend`）默认监听 `127.0.0.1:8888`，需先启动。文件问答已并入「对话助手」，
由 `chat` 智能体加上传的 `fileId` 触发。

## 环境变量（`.env`）

| 变量 | 说明 |
| --- | --- |
| `VITE_API_BASE` | 业务接口前缀，留空即可（直接使用后端既有根路径） |
| `VITE_AUTH_BASE` | 登录跳转与后端回跳的后端根地址，默认 `http://127.0.0.1:8888` |

`VITE_AUTH_BASE` 的**主机名必须与后端 `github.oauth.redirect-uri` 一致**：会话 Cookie 按主机名
隔离（端口不影响），不一致时 authorize 写入的 state 在回调里读不到，登录会以 `?error=state` 失败。

## 代理与「前端路由禁区」

开发环境由 `vite.config.ts` 的 `server.proxy` 把 `/oauth`、`/auth`、`/session`、`/file`、
`/interview`、`/agent` 转发到后端 8888（生产环境由 Nginx 反代同一域名的这些路径），
浏览器视角始终同源，会话 Cookie 无需跨站配置。

这几个前缀同时是**前端路由禁区**：落在这几个前缀下的 SPA 路由会先被转发给后端，前端页面
永远渲染不到。匹配是按**字符串前缀**，`/oauth-callback`、`/oauthx` 同样会被代理走。
登录回跳页因此放在 `/login/callback`。

`server.host: '127.0.0.1'` 与 `strictPort: true` 是刻意设置：Cookie 与 OAuth 回跳地址都绑定了
主机名与 5173 端口，两者都不应静默漂移。

## 目录结构

```
src/
├── api/           # http 封装（统一错误与 401 处理）、业务接口、登录接口
├── components/    # ChatApp / SideBar / MessageItem / InputArea / InterviewPanel 等
├── composables/   # useChat（会话与对话流）、useInterview（面试总结上传 + 进度）
├── config/        # apiBase / authBase
├── router/        # 路由与登录守卫
├── stores/        # 登录态（reactive 手写，未引状态库）
├── types/         # TS 类型定义
├── utils/         # constants / format / markdown / wave
└── views/         # LoginView / AuthCallbackView
```

## 说明

- 对话流式结束不依赖 `[DONE]` 帧（后端以正常关闭流表示完成），前端仍保留防御性处理。
- 面试总结走**具名 SSE 事件**（`event: xxx` + `data: {...}`），与对话接口的 `type` 字段载荷
  是两套协议，分别由 `useChat` / `useInterview` 解析。
- 新增智能体：改 `src/utils/constants.ts` 的 `AGENTS`，并在 `src/api/index.ts` 的
  `getStreamChatUrl` 增加对应分支。
