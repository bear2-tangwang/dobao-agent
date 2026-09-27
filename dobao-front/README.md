# 豆豆 - AI 智能助手前端

基于 Vue 3 + TypeScript + Vite 的「豆豆」AI 聊天前端,由后端 `dobao-backend/src/main/resources/static` 下的 CDN 版页面**保真迁移**而来(保留原 CSS 与结构,逻辑重构为工程化写法)。

## 功能特性

- 多智能体对话:对话助手 / 文件问答 / PPT生成 / 深度研究
- 会话管理:会话列表、详情加载、删除(弹窗确认)
- 文件上传:PDF / Word / TXT / PNG / JPG(单文件,仅文件问答模式)
- 流式输出:SSE 逐字输出,支持停止生成
- 思考过程展示(可折叠)、参考来源(可折叠)、推荐问题(点击追问)
- Markdown 渲染 + 代码高亮 + DOMPurify XSS 防护
- 暗色科技感响应式设计,Font Awesome 图标

## 快速开始

### 1. 确认后端可运行

后端(Spring Boot)默认监听 `http://localhost:8888`,提供 `GET /agent/chat/stream`(SSE)与 `/session/*` 接口,并已放开跨域(CORS)。

### 2. 配置后端地址

在项目根目录的 `.env` 中配置:

```
VITE_BACKEND_URL=http://localhost:8888
```

不配置时默认回退 `http://localhost:8888`。若后端部署在其他地址,启动前修改此处即可。

### 3. 安装并启动

```bash
npm install
npm run dev
```

浏览器打开 Vite 输出的地址(默认 `http://localhost:5173`)。

### 4. 生产构建

```bash
npm run build    # 先跑 vue-tsc 类型检查,再 vite build,产物在 dist/
```

## 技术栈

- Vue 3.5(`<script setup>`) + TypeScript
- Vite 8 + vue-tsc
- Marked + marked-highlight + Highlight.js + DOMPurify(Markdown 渲染与防 XSS)
- Font Awesome 6(图标)
- 无 UI 组件库,样式沿用原手写 CSS(`src/style.css`,全局引入)

## 项目结构

```
src/
├── api/index.ts            # 后端 API 封装(含 SSE 流式)
├── components/             # SideBar / EmptyState / MessageItem / InputArea / ConfirmDialog / ConnectionError
├── composables/useChat.ts  # 核心逻辑(会话、文件、流式解析)
├── config/index.ts         # backendUrl 配置
├── utils/                  # constants / format / markdown
└── types/index.ts          # TS 类型定义
```

## 说明与限制

- 「文件问答」「PPT生成」「深度研究」等依赖的后端端点(`/file/upload`、`/agent/file/stream`、`/agent/pptx/stream`、`/agent/deep/stream`、`/agent/stop`)后端尚未实现,选择这些模式会提示错误,与旧页面行为一致。
- 新增智能体:改 `src/utils/constants.ts` 的 `AGENTS`,并在 `src/api/index.ts` 的 `getStreamChatUrl` 增加对应分支。
- 流式结束不依赖 `[DONE]` 帧(后端以正常关闭流表示完成),前端仍保留对 `[DONE]` 的防御性处理。

## 许可证

MIT