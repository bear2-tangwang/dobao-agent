# dobao-agent

一个面向通用智能体场景的全栈应用，提供 AI 对话、联网深度研究、PPT 内容生成、文件处理以及录音面试转写与报告整理等能力，并预留 Agent 与工具扩展接口。

> **项目状态说明：** 本 README 根据当前仓库中的源码目录、Maven/npm 配置、Docker Compose 配置及项目文档整理。具体模型可用性、第三方服务额度和生产环境部署状态取决于实际配置，不能仅凭代码仓库保证。

## 目录

- [项目功能](#项目功能)
- [技术栈](#技术栈)
- [系统架构](#系统架构)
- [目录结构](#目录结构)
- [环境要求](#环境要求)
- [本地开发](#本地开发)
- [环境变量与第三方服务](#环境变量与第三方服务)
- [构建与测试](#构建与测试)
- [Docker 部署](#docker-部署)
- [安全注意事项](#安全注意事项)
- [相关文档](#相关文档)

## 项目功能

### AI 智能体

- **智能对话**：基于 Spring AI 接入兼容 OpenAI 协议的模型服务。
- **深度研究**：通过研究 Agent 与 Tavily MCP 联网搜索能力支持资料检索和研究流程。
- **PPT 生成流程**：包含意图识别、内容大纲、模板与渲染等策略代码；实际生成效果依赖模型、模板和运行环境。
- **工具扩展**：后端包含 Agent、Tool 和 MCP Client 相关代码，可在现有结构上扩展工具能力。

### 面试辅助

- 上传面试录音并调用 ASR 服务转写。
- 对转写文本进行规范化，并尝试识别面试官/候选人角色和问答内容。
- 生成面试总结与报告文件。
- 通过后端进度推送机制反馈处理状态。

### 文件与用户会话

- 使用对象存储管理上传文件和生成产物。
- 使用 PostgreSQL 持久化业务数据、向量数据及登录会话。
- 提供 GitHub OAuth 登录相关页面和后端接口。
- 前端包含聊天、面试交互、登录与授权回调界面。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Vue Router |
| 后端 | Java、Spring Boot 3.5.6、Maven |
| AI / Agent | Spring AI、Spring AI Alibaba、OpenAI 兼容模型接口、MCP Client |
| 数据访问 | MyBatis-Plus、JDBC |
| 数据库与向量 | PostgreSQL 16、pgvector |
| 文件存储 | MinIO |
| 文档处理 | Apache PDFBox、Apache POI |
| 联网研究 | Tavily MCP |
| 部署 | Docker、Docker Compose、Nginx |

## 系统架构

```text
浏览器
  │
  ▼
Vue 3 前端（开发环境 Vite :5173）
  │  /agent、/interview、/file、/auth、/session、/oauth
  ▼
Spring Boot 后端（:8888）
  ├── AI 对话 / Agent / 深度研究 / PPT
  ├── 面试录音转写与报告
  ├── 登录认证与会话
  ├── PostgreSQL + pgvector（业务数据、向量、会话）
  └── MinIO（文件和生成产物）

外部服务：模型服务 / ASR / 文生图 / Tavily MCP / GitHub OAuth
生产入口：Nginx（静态前端、API 反向代理及对象存储访问配置）
```

生产部署的服务关系、端口和环境变量以根目录的 `docker-compose.yml`、`.env.example` 及 [项目部署文档](docs/项目部署文档.md) 为准。

## 目录结构

```text
dobao-agent/
├── dobao-backend/                 # Java / Spring Boot 后端
│   ├── src/main/java/             # 业务源码
│   │   └── com/dobao/dobaobackend/
│   │       ├── agent/             # 对话、深度研究、PPT Agent
│   │       ├── auth/              # 认证相关
│   │       ├── config/            # 配置
│   │       ├── controller/        # HTTP 接口
│   │       ├── entity/            # 实体及数据对象
│   │       ├── interview/         # 面试转写、进度、报告
│   │       ├── mapper/            # 数据访问
│   │       ├── service/           # 业务服务
│   │       ├── tool/              # 工具集成
│   │       └── utils/             # 工具类
│   ├── src/main/resources/        # application*.yml、Mapper XML 等资源
│   ├── src/test/                  # 后端测试
│   ├── pom.xml                    # Maven 配置
│   └── Dockerfile                 # 后端镜像构建
├── dobao-front/                   # Vue 前端
│   ├── src/
│   │   ├── api/                   # HTTP 与业务 API
│   │   ├── components/            # 聊天、面试等组件
│   │   ├── composables/           # 页面交互逻辑
│   │   ├── router/                # 前端路由
│   │   ├── stores/                # 状态与认证
│   │   ├── views/                 # 登录与回调页面
│   │   └── utils/                 # 工具函数
│   ├── package.json               # npm scripts 与依赖
│   └── vite.config.ts             # 开发服务器与 API 代理
├── deploy/
│   ├── nginx/                     # Nginx 模板配置
│   ├── sql/                       # PostgreSQL 扩展及初始化脚本
│   ├── data/ppt-template/         # PPT 模板目录
│   └── dist/                      # 生产前端静态文件目录
├── docs/                          # 功能设计、开发记录与部署文档
├── docker-compose.yml             # 生产服务编排
├── .env.example                   # 生产环境变量模板
└── README.md
```

## 环境要求

本地开发建议准备：

- **JDK 17 或更高版本**（Spring Boot 3.x 运行要求）。
- **Maven 3.9+**（如果使用项目 Maven Wrapper，也可以使用 Wrapper 命令）。
- **Node.js 22.18+ 或 24.12+**，版本范围与前端 `package.json` 的 engines 声明一致。
- npm。
- PostgreSQL（需要 pgvector 扩展）和 MinIO。
- 可用的模型服务凭据；按需配置 ASR、文生图、Tavily MCP 与 GitHub OAuth。

如果不准备数据库、对象存储和第三方服务，前端页面或后端进程即使启动，也不代表所有业务功能都能正常工作。

## 本地开发

### 1. 准备依赖服务与配置

先阅读以下配置：

- `dobao-backend/src/main/resources/application.yml`
- `dobao-backend/src/main/resources/application-dev.yml`
- `dobao-backend/src/main/resources/application-prod.yml`
- 根目录的 `.env.example`

当前基础配置使用后端端口 **8888**，前端 Vite 端口 **5173**；本地数据库连接地址在配置文件中指定。请根据本机服务地址、端口、数据库名及账号调整配置。

> **重要：先处理密钥再启动。** 当前仓库的部分配置文件中存在看起来像真实凭据的硬编码值。不要将这些值复制到 README、提交记录或日志中；请将已暴露的 API Key、OAuth Secret 和存储凭据撤销/轮换，并改为从环境变量或本地未跟踪配置读取。确认安全配置完成后再启动服务。

### 2. 启动后端

在仓库根目录执行：

```bash
cd dobao-backend
mvn spring-boot:run
```

如果使用 Windows PowerShell，命令相同；前提是 Maven 已安装并且 `mvn` 在 PATH 中。

后端默认监听 `http://localhost:8888`。如果数据库、MinIO 或模型服务尚未正确配置，部分接口可能启动失败或在调用时返回错误，请以应用日志为准。

### 3. 启动前端

另开终端：

```bash
cd dobao-front
npm ci
npm run dev
```

开发服务器配置为 `http://127.0.0.1:5173`，并将 `/oauth`、`/auth`、`/session`、`/file`、`/interview` 和 `/agent` 请求代理到后端 `http://localhost:8888`。端口是严格固定的；如果 5173 已被占用，请先释放端口或同步调整前端与 OAuth 回调配置，不要只改其中一处。

## 环境变量与第三方服务

生产环境变量模板为根目录 `.env.example`。部署时复制为 `.env` 并填写自己的值；不要提交包含真实凭据的 `.env` 文件。

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

部分变量仅在生产配置中使用，开发配置也可能存在独立默认值。更完整的映射、MinIO 地址方案、OAuth 设置和部署注意事项请查看 [部署文档](docs/项目部署文档.md) 与 `.env.example` 注释。

## 构建与测试

### 前端类型检查与生产构建

```bash
cd dobao-front
npm ci
npm run type-check
npm run build
```

`npm run build` 会先执行 TypeScript/Vue 类型检查，再构建静态资源。

### 后端测试与打包

```bash
cd dobao-backend
mvn test
mvn package
```

这些命令用于运行 Maven 测试和打包。测试结果应以当前代码、依赖和运行环境的实际执行输出为准；本 README 不代表测试已经通过。

## Docker 部署

生产部署使用根目录 `docker-compose.yml` 编排以下服务：

- **nginx**：静态前端、API 反向代理及相关访问入口。
- **backend**：Spring Boot 应用。
- **postgres**：PostgreSQL 16 + pgvector，保存业务数据、向量及会话数据。
- **minio**：上传文件和生成文件的对象存储。

部署前至少需要准备：

1. 根据 `.env.example` 创建并填写根目录 `.env`。
2. 检查主站域名、MinIO 对外地址、GitHub OAuth 回调地址及 Cookie 安全选项。
3. 准备 `deploy/data/ppt-template/ai.pptx` 所需的 PPT 模板。
4. 确认 `deploy/dist/` 是与当前前端源码对应的最新构建产物。
5. 按部署文档的步骤构建/加载镜像并启动服务。

部署命令、数据库初始化顺序、HTTPS、备份、运维与故障排查请严格参阅 [docs/项目部署文档.md](docs/项目部署文档.md)。该文档包含与当前部署方式有关的详细前提，不建议仅凭下面一条命令直接上线。

在已完成全部环境配置、镜像和静态文件准备后，服务启动命令为：

```bash
docker compose up -d
docker compose ps
docker compose logs -f backend
```

**数据提醒：** PostgreSQL 与 MinIO 使用 Docker volumes 保存数据。删除容器和删除数据卷不是同一操作；执行带 `-v` 的清理命令前，必须确认已经备份并且确实要删除数据。

## 安全注意事项

- 不要提交 `.env`、API Key、OAuth Secret、数据库密码或 MinIO 密钥。
- 如果密钥曾进入 Git 历史、共享日志或公开环境，应在提供方控制台撤销并重新生成；只从当前文件中删除并不能消除历史泄露。
- 生产环境应关闭认证诊断接口，并根据是否启用 HTTPS 正确设置 Cookie Secure 属性。
- 生产数据库、对象存储和后端服务不应无必要地直接暴露公网；按部署配置通过 Nginx 暴露所需入口。
- 对象存储访问权限、上传文件大小、第三方 API 费用和 OAuth 回调域名都应在上线前复核。
- 不要把真实服务器 IP、账号、密钥或个人数据写入公开文档。

## 相关文档

- [项目部署文档](docs/项目部署文档.md)：生产部署、环境变量、Nginx、HTTPS 与故障排查。
- [面试功能范围](docs/interview-feature-v1-scope.md)：面试辅助功能范围。
- [面试总结编码计划](docs/interview-summary-coding-plan.md)：面试报告相关设计与实现计划。
- [GitHub OAuth 登录计划](docs/github-oauth-login-plan.md)：OAuth 登录设计记录。
- [docs/](docs/)：更多开发记录、流程图和技术方案。
