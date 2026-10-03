# 面试总结接入会话模型（历史可见 + 可还原）· 设计

日期：2026-10-03
涉及模块：`dobao-backend/src/main/java/com/dobao/dobaobackend/{service,controller,entity,interview}`、`dobao-front/src`

## 1. 问题

面试总结（上传录音 → 转写 → 角色判定 → 问答清单 → 报告）**只写 `ai_interview`，从不进入 `ai_session`**，
因此它游离在既有会话模型的四条能力之外：

| 能力 | chat / ppt / deep | 面试总结（现状） |
|---|---|---|
| 落库 | `BaseAgent.saveQuestion` → `ai_session` | 只写 `ai_interview` |
| 侧边栏列表 | `GET /session/list` 按 `sessionId` 聚合 | 无记录 → 刷新后整场消失 |
| 会话详情回放 | `GET /session/{id}` 返回 messages | 无记录 → "会话不存在" |
| 跨设备续看 | 从 DB 重建 | 只靠 `localStorage` 的 `dobao.interview.current` |

证据：

1. `InterviewController` / `InterviewService` / `InterviewTaskService` 中**没有任何 `AiSessionService` 引用**。
2. 前端早就写好了"从历史恢复面试"的钩子却全是死代码：`useInterview.ts` 的
   `tryRestore` / `readPersistedId` / `startById` / `loadReportById`
   （注释原文："用于会话历史里恢复一场已完成的面试"）**在别处一次都没有被调用**——因为历史里拿不到 `interviewId`。
3. `interviewId` 只写进 `localStorage`，换浏览器/设备即丢失。

## 2. 已确认决策（用户 2026-10-03 选择）

1. **目标形态 = A. 可见 + 可还原**：面试会话出现在会话列表，点开能还原进度/报告。
   本期**不做**"面试会话里继续追问"（chat memory 注入报告上下文）。
2. **存储粒度 = 一个会话可含多场面试，每场一行 `ai_session`**（与 chat/ppt "一行 = 一轮"一致）。
3. **`answer` 只写一行状态摘要**（如 `面试总结已完成：12 条问答、8 条参考回答`），
   报告正文的唯一来源仍是 `ai_interview.report_json` + `/interview/{id}/report`。
4. **零 DDL**：用 `ai_session.fileid` 承载 `interviewId`，
   沿用本表既有约定（`AiSession.fileid` 注释原文："用于关联 ai_file_info **或 ai_ppt_inst**"——
   它本来就是"按 `agent_type` 解释的多态业务指针"）。
5. **删除会话时级联删除** `ai_interview` 记录 + MinIO 上的录音与报告对象。

## 3. 数据模型（零 DDL）

一场面试 = 一行 `ai_session`：

| 列 | 值 | 说明 |
|---|---|---|
| `session_id` | 前端 `conversationId` | 与 chat 同一 id 空间；多场面试 = 多行 |
| `agent_type` | `interview` | 列表/详情/前端分支依据。`SessionController.normalizeAgentType` 只把 `websearch`/`file` 归并为 `chat`，`interview` 会原样保留 |
| `question` | 录音文件名 | 侧边栏标题取 `question[:20]`（`api/index.ts` `loadChats`），正好是 `interView.m4a` 这种形态 |
| `answer` | 状态摘要 | 处理中留空；成功写完成摘要；失败写失败摘要 |
| `fileid` | `interviewId` | 决策 4 |
| `create_time` | 上传时刻 | 决定回放顺序 |
| `update_time` | 回填时刻 | 决定列表排序（报告就绪后浮到最前） |

`ai_interview` 结构**完全不动**，继续承担：转写 JSON、`report_json`、MinIO 报告地址、状态机。

## 4. 写入路径

新增独立 Bean `InterviewSessionRecorder`（`interview/` 包）：

- `recordUploaded(conversationId, interviewId, fileName)` — 插入会话行（`answer` 空）；
  若 `(session_id, fileid)` 已存在则只刷 `update_time`（幂等，不重复插行）
- `markReady(interviewId, qaCount, referenceCount)` — 回填成功摘要
- `markFailed(interviewId, reason)` — 回填失败摘要

**为什么单开一个 Bean**：`InterviewService` 的类注释明确要求"状态机的所有写都在 `InterviewTaskService`，
避免两个类互相依赖"。上传写入（快路径）在 `InterviewService`，回填（异步线程）在 `InterviewTaskService`，
Recorder 作为第三个 Bean 被两边依赖，不破坏这条边界。异步线程没有事务上下文，回填各自独立执行。

接线点：

| 位置 | 改动 |
|---|---|
| `InterviewController.upload` | 新增可选参数 `conversationId` |
| `InterviewService.upload` | 建记录后 `recordUploaded`（与现有 `@Transactional` 同事务）；**幂等命中分支也要调**——否则把已上传过的录音传到新会话里，新会话永远没有会话行 |
| `InterviewTaskService.publishReport` | 报告落库成功后 `markReady`（用报告里真实的问答/参考回答条数） |
| `InterviewTaskService` 失败分支 | 转写失败、`runAnalysis` catch 中 `markFailed`；重试成功后被 `markReady` 覆盖 |

## 5. 读取路径

| 位置 | 改动 |
|---|---|
| `MessageVO` | 新增 `interviewId` 字段（VO，无 DDL） |
| `SessionController.convertToMessageVO` | `agent_type='interview'` 时：`interviewId = fileid`、`fileName = question`、跳过 `ai_file_info` 查询（`fileid` 此时是 interviewId，查不到；用户气泡靠 `fileName` 显示录音 chip） |
| `SessionController.getSessionList` | **必须顺带修**：现实现"先 `page()` 再按 `sessionId` 去重"，一个会话出现多行会挤爆首页且 `total` 错误。改为 `id in (select max(id) from ai_session group by session_id)` 后再分页排序 |
| `useInterview.ts` | `start()` 透传 `conversationId`；把 `tryRestore` / `startById` 从死代码接上 |
| `useChat.ts` `selectChat` | `agentType === 'interview'` 时，按每条消息的 `msg.interviewId` 建 `reactive(createInterviewSession())` → `tryRestore` 拉状态；非终态再 `startById` 续订 SSE；失败态露出重试按钮 |
| `api/index.ts` | `uploadInterview(...)` 带 `conversationId`；`SessionDetail` 消息类型加 `interviewId` |

回放形态与实时那场完全一致：用户气泡（录音 chip + 文件名）→ AI 气泡（`InterviewPanel` + 报告 markdown + 下载）。
`answer` 摘要只在面试接口不可用时作为兜底文案。

## 6. 删除语义（级联）

`SessionController.deleteSession(conversationId)` 增加面试分支，顺序为"先删对象、再删库"：

1. 取该会话下所有面试指针：`ai_session where session_id = ? and agent_type = 'interview'` → `fileid`（= interviewId）
2. 按 `interview_id in (...)` 查 `ai_interview`，收集 `file_id` / `file_name` / `report_file_name`
3. 删 MinIO：报告对象 `report_file_name`、音频对象 `FileManageService.generateObjectName(fileId, 从 fileName 推扩展名)`
   （MinIO `removeObject` 对不存在的 key 幂等，重试安全）
4. 删库：`ai_interview`（按 interview_id）→ 现有的 `ai_file_info` / `ai_ppt_inst` / `ai_session`
5. 顺序理由：若第 3 步失败则抛异常、事务回滚，用户看到"删除失败"；
   若第 4 步失败而后重试，第 3 步幂等通过——不会出现"库删了、音频还在"

## 7. 边界情况

| 场景 | 行为 |
|---|---|
| 上传后立刻刷新 / 换设备 | 列表已有会话行 + 详情有 `interviewId` → `tryRestore` 接回进度，不再依赖 `localStorage` |
| 同一音频重复上传（`audio_hash` 幂等命中） | 复用 `interviewId`；确保目标会话有行（缺失则插）；同会话同录音不重复插行 |
| 分析失败 → 重试 | `markFailed` → 成功后 `markReady` 覆盖 |
| 一个会话多场面试 | 多行，按 `create_time` 升序回放多个面板 |
| 未传 `conversationId`（老调用方 / 兼容） | 只写 `ai_interview`，不写会话行（行为退化为现状），不报错 |
| 将来做"面试会话继续追问" | chat memory 只会读到文件名 + 一行摘要，污染可忽略；要排除时按 `agent_type` 过滤 |

## 8. 明确不改的东西

- `ai_interview` 表结构、状态机、SSE 进度通道（`InterviewProgressHub`）、报告生成与渲染
- `BaseAgent.createPersistentChatMemory` 与所有 chat/ppt/deep 链路
- 面试的实时交互（选文件 → 开始总结 → 进度 → 报告）

## 9. 验收标准

1. 上传录音后 **立刻刷新页面**，侧边栏能看到以文件名命名的会话；点开能看到进行中的进度并继续接收 SSE。
2. 报告就绪后刷新：侧边栏该会话的 `answer` 显示完成摘要，点开直接渲染完整报告 + 下载按钮可用。
3. 换一个浏览器（清掉 `localStorage`）打开：上述 1、2 依然成立。
4. 同一会话连跑两场面试：详情按顺序回放两个面板，各自的报告互不串台。
5. 同一录音在两个会话里各上传一次：两边都能看到并可还原（幂等命中同一场分析）。
6. 报告生成失败时，列表可见、详情能重试；重试成功后摘要被覆盖。
7. 删除面试会话：`ai_session` / `ai_interview` 记录消失，MinIO 上录音与报告对象被删除。
8. 非面试会话（chat/ppt）的列表与详情行为不变。

## 10. 已知不修的相邻问题（本次不动，避免扩大影响面）

- `AiFileInfo.conversationId` 从来没有任何地方写入（`setConversationId` 全仓零调用），
  所以 `deleteSession` 里删 `ai_file_info` 的语句实际是空操作，文件元数据会累积。
  本次面试删除链路**不依赖**它——音频对象名由 `ai_interview.file_id` + `file_name` 推导。
