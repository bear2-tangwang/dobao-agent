# dobao-agent

一个面向通用智能体场景的全栈应用：AI 对话、联网深度研究、PPT 生成、面试录音转写与报告整理，并预留 Agent 与工具扩展接口。


## 项目介绍

### 智能对话

- 基于 Spring AI 接入兼容 OpenAI 协议的模型服务，SSE 流式输出。
- 支持多智能体切换（对话助手 / PPT 生成 / 深度研究 / 面试总结）。
- 支持文件问答：上传文件后携带 `fileId` 提问，后端解析并做向量检索。

### 深度研究

- Plan-Execute / ReAct 两种研究 Agent 流程。
- 通过 Tavily MCP 提供联网搜索能力，支持资料检索与多步研究。

### PPT 生成

- 状态机策略驱动的生成流程：意图识别 → 需求澄清 → 大纲 → 模板选择 → 渲染 → 联网补充。
- 使用 Apache POI 与 Python `render_ppt.py` 完成模板渲染，产物存入 MinIO。

### 面试助手

- 上传面试录音，调用 DashScope ASR 服务转写。
- 对转写文本规范化，识别面试官/候选人角色并抽取问答。
- 生成面试总结与报告文件，支持报告预览与下载。
- 通过 SSE 推送处理进度，支持失败重试。

### 文件与用户会话

- MinIO 对象存储管理上传文件与生成产物。
- PostgreSQL + pgvector 持久化业务数据、向量数据及登录会话（Spring Session JDBC）。
- GitHub OAuth 登录，数据按用户隔离。

### 前端交互

- 聊天界面：Markdown 渲染、代码高亮、侧边会话管理。
- 拖入热区上传文件、鼠标拖尾效果、面试进度面板。

## 架构与技术栈

### 系统架构

```text
浏览器
  │
  ▼
Vue 3 前端（开发环境 Vite :5173）
  │  /agent、/interview、/file、/auth、/session、/oauth 代理到后端
  ▼
Spring Boot 后端（:8888）
  ├── AI 对话 / Agent / 深度研究 / PPT 生成
  ├── 面试录音转写与报告
  ├── 登录认证与会话
  ├── PostgreSQL + pgvector（业务数据、向量、会话）
  └── MinIO（文件和生成产物）

外部服务：模型服务（DashScope）/ ASR / 文生图 / Tavily MCP / GitHub OAuth
生产入口：Nginx（静态前端、API 反向代理及对象存储访问配置）
```

### 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite 8、Vue Router、Marked + highlight.js |
| 后端 | Java 17、Spring Boot 3.5.6、Maven |
| AI / Agent | Spring AI 1.1.0、Spring AI Alibaba、OpenAI 兼容模型接口、MCP Client |
| 数据访问 | MyBatis-Plus、JDBC |
| 数据库与向量 | PostgreSQL 16、pgvector |
| 文件存储 | MinIO |
| 文档处理 | Apache PDFBox、Apache POI、Python（PPT 渲染） |
| 联网研究 | Tavily MCP |
| 部署 | Docker、Docker Compose、Nginx |

### 目录结构

```text
dobao-agent/
├── dobao-backend/                 # Java / Spring Boot 后端
│   ├── src/main/java/com/dobao/dobaobackend/
│   │   ├── agent/                 # 对话、深度研究、PPT Agent
│   │   ├── auth/                  # GitHub OAuth 认证
│   │   ├── controller/            # HTTP 接口
│   │   ├── interview/             # 面试转写、进度、报告
│   │   ├── mapper/                # 数据访问
│   │   ├── service/               # 业务服务
│   │   ├── tool/                  # 工具集成
│   │   └── prompts/               # 提示词
│   ├── src/main/resources/        # application*.yml、Python 渲染脚本
│   ├── pom.xml
│   └── Dockerfile
├── dobao-front/                   # Vue 3 前端
│   ├── src/
│   │   ├── api/                   # HTTP 与业务 API
│   │   ├── components/            # 聊天、面试等组件
│   │   ├── composables/           # useChat / useInterview
│   │   ├── router/                # 前端路由
│   │   ├── stores/                # 认证状态
│   │   └── views/                 # 登录与回调页面
│   └── vite.config.ts             # 开发服务器与 API 代理                   # 生产前端静态文件目录
```

## 启动方式

以下为本地开发启动步骤，仅覆盖前后端应用；PostgreSQL、MinIO 及第三方服务需另行准备。

### 环境要求

- JDK 17 或更高版本
- Maven 3.9+（或使用项目自带的 Maven Wrapper）
- Node.js 22.18+ 或 24.12+（与前端 `package.json` 的 engines 声明一致）
- PostgreSQL（需 pgvector 扩展）和 MinIO
- 可用的模型服务凭据；按需配置 ASR、文生图、Tavily MCP 与 GitHub OAuth

### 1. 准备配置

后端配置文件：

- `dobao-backend/src/main/resources/application.yml`（基础配置，端口 8888）
- `dobao-backend/src/main/resources/application-dev.yml`
- `dobao-backend/src/main/resources/application-prod.yml`

按本机服务地址、端口、数据库账号调整数据库连接、MinIO 地址及模型服务凭据。

### 2. 启动后端

```bash
cd dobao-backend
mvn spring-boot:run
```

后端默认监听 `http://localhost:8888`。如果数据库、MinIO 或模型服务尚未正确配置，部分接口可能在调用时返回错误，请以应用日志为准。

### 3. 启动前端

另开终端：

```bash
cd dobao-front
npm ci
npm run dev
```

开发服务器为 `http://127.0.0.1:5173`，并将 `/oauth`、`/auth`、`/session`、`/file`、`/interview`、`/agent` 请求代理到后端 `http://localhost:8888`。端口是严格固定的：会话 Cookie 与 OAuth 回跳地址都绑定了 5173，如果端口被占用，请先释放端口，不要只改其中一处。

## Docker 部署

生产部署使用根目录 `docker-compose.yml` 编排四个服务：

| 服务 | 说明 |
| --- | --- |
| nginx | 静态前端、API 反向代理（:80）及 MinIO 对外访问 |
| backend | Spring Boot 应用（:8888） |
| postgres | PostgreSQL 16 + pgvector，业务数据、向量及会话同库 |
| minio | 上传文件和生成文件的对象存储 |

## 环境变量
| 配置项 | 用途 |
| --- | --- |
| `POSTGRES_*` | PostgreSQL 数据库 |
| `MINIO_*` | 对象存储与对外访问地址 |
| `DASHSCOPE_API_KEY`、`DASHSCOPE_BASE_URL` | 对话、向量化等模型服务 |
| `DASHSCOPE_ASR_BASE_URL`、`ASR_MODEL` | 录音转写 |
| `CHAT_MODEL`、`EMBEDDING_MODEL` | 对话与向量化模型选择 |
| `QWEN_IMAGE_API_URL`、`QWEN_IMAGE_MODEL` | 文生图服务 |
| `TAVILY_API_KEY`、`TAVILY_MCP_URL` | 联网研究 |
| `GITHUB_CLIENT_ID`、`GITHUB_CLIENT_SECRET` | GitHub OAuth |
| `GITHUB_REDIRECT_URI`、`FRONTEND_BASE_URL` | OAuth 回调与前端落点 |
| `SESSION_COOKIE_SECURE` | 是否仅通过 HTTPS 发送会话 Cookie |
| `AUTH_DIAGNOSTICS_ENABLED` | 登录诊断接口开关；生产环境应关闭 |
