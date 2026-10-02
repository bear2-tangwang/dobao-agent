# 统一文件问答与联网搜索 Agent 改造说明

## 一、背景

原项目后端分别实现了两个 React Agent：

- `WebSearchReactAgent`：负责联网搜索问答，接口为 `/agent/chat/stream`
- `FileReactAgent`：负责文件问答，接口为 `/agent/file/stream`

两个 Agent 的 React 循环逻辑高度重复，前端也把「对话助手」和「文件问答」作为两个独立入口。

本次目标：

- 将两个功能合并为一个统一 Agent。
- 前端只保留一个「对话助手」入口，可在同一会话中同时使用文件问答和联网搜索。
- 默认优先走文件；如果文件没有找到相关内容，再走联网搜索补充。
- 统一走 `/agent/chat/stream` 接口。
- `agentType` 统一为 `chat`。

## 二、最终方案

### 后端

1. 新增 `UnifiedReactAgent`
   - 同时注入 Tavily 联网搜索工具和 `FileContentService.loadContent` 文件工具。
   - 使用统一的 `getUnifiedPrompt()` 提示词。
   - 支持可选 `fileId`，没有文件时退化为普通联网搜索。
   - 同一轮工具执行改为顺序执行，避免并发工具结果写入顺序不稳定。

2. 统一接口
   - `GET /agent/chat/stream?query=xxx&conversationId=xxx&fileId=xxx`
   - `fileId` 为可选参数。
   - `/agent/file/stream` 保留为兼容旧客户端的别名，前端不再使用。

3. 统一提示词策略
   - 有文件：优先调用 `loadContent`。
   - 文件能回答：直接基于文件回答。
   - 文件没有相关内容：先告知用户「文件中未找到相关内容」，再调用联网搜索。
   - 无文件：直接联网搜索。
   - 问题同时需要文件和实时信息：允许先文件后搜索组合回答。

4. 会话数据
   - `SaveQuestionRequest` 增加 `agentType` 字段。
   - `AiSessionServiceImpl.saveQuestion` 默认写入 `agentType=chat`。
   - 历史会话中 `websearch` / `file` / `null` 类型在会话详情中归一化为 `chat`。

5. 会话详情文件元信息
   - `MessageVO` 增加 `fileName`、`fileType`、`fileSize`。
   - `SessionController` 根据 `fileid` 查询 `ai_file_info` 并返回文件元信息。

### 前端

1. Agent 列表
   - 移除独立的「文件问答」入口。
   - 保留：对话助手、PPT生成、深度研究。

2. 上传入口
   - 在「对话助手」模式下即可上传文件。
   - 上传后提示支持文件问答 + 联网搜索。
   - 单文件限制保持不变。

3. 请求 API
   - `getStreamChatUrl` 统一返回 `/agent/chat/stream`。
   - 有 `fileId` 时自动追加 `fileId` 参数。

4. 历史会话展示
   - 加载历史消息时，如果后端返回 `fileName`，用户消息区域展示真实文件名。
   - 当前不做文件与会话持久化绑定，历史文件不会自动恢复为可继续问答的已上传文件。

## 三、修改文件清单

### 后端

| 类型 | 文件 | 说明 |
|---|---|---|
| 新增 | `agent/UnifiedReactAgent.java` | 统一 Agent，集成文件和搜索工具 |
| 修改 | `controller/AgentController.java` | `/agent/chat/stream` 统一入口，兼容 `/agent/file/stream` |
| 修改 | `prompts/ReactAgentPrompts.java` | 新增 `getUnifiedPrompt()` |
| 修改 | `entity/vo/SaveQuestionRequest.java` | 增加 `agentType` |
| 修改 | `service/impl/AiSessionServiceImpl.java` | 保存问题时写入 `agentType=chat` |
| 修改 | `entity/vo/MessageVO.java` | 增加文件元信息字段 |
| 修改 | `controller/SessionController.java` | 统一旧 `agentType`，返回文件元信息 |
| 删除 | `agent/file/FileReactAgent.java` | 由 `UnifiedReactAgent` 替代 |
| 删除 | `agent/websearch/WebSearchReactAgent.java` | 由 `UnifiedReactAgent` 替代 |

### 前端

| 类型 | 文件 | 说明 |
|---|---|---|
| 修改 | `utils/constants.ts` | 移除「文件问答」入口 |
| 修改 | `api/index.ts` | 统一流式接口为 `/agent/chat/stream` |
| 修改 | `types/index.ts` | 增加文件元信息字段 |
| 修改 | `components/InputArea.vue` | 对话助手模式支持上传文件 |
| 修改 | `composables/useChat.ts` | 历史会话展示真实文件名 |

## 四、验证情况

已完成：

- 后端 `mvn compile` 通过。
- 前端 `vue-tsc --build` 类型检查通过。
- 前端 `vite build` 因当前机器 Node 环境 `spawn EPERM` 无法完成，疑似本机权限/环境问题，与本次代码改动无关。

建议后续手工验证：

1. 纯对话：不传文件，正常联网搜索。
2. 纯文件问答：上传文件后提问，基于文件回答。
3. 文件无答案：文件中找不到时提示后联网补充。
4. 文件 + 搜索结合：同时引用文件内容和网络信息。
5. 历史会话：旧会话可正常打开，`agentType` 兼容。
6. 停止生成：统一 Agent 后 `/agent/stop` 仍可用。

## 五、注意事项与后续可优化点

- 当前仍只支持单文件，不做多文件。
- 文件上传仍未与会话绑定，刷新后需要重新上传才能继续基于该文件问答。
- 删除会话时仍不会清理未绑定 `conversationId` 的历史上传文件。
- `executeToolCalls` 已改为顺序执行，若未来需要并行加速，需要额外处理消息写入顺序。
- 后续可将 BaseAgent 中重复的 React 循环进一步下沉到公共抽象类，但目前统一 Agent 已满足当前合并需求。