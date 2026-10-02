# `/agent/deep/stream` 深度研究链路流程文档

> 适用范围：`dobao-backend` 后端 + `dobao-front` 前端消费端
> 入口接口：`GET /agent/deep/stream`（SSE，`text/event-stream;charset=UTF-8`）
> 核心实现：`PlanExecuteAgent`（Plan-Execute 主控）+ `SimpleReactAgent`（子任务 ReAct 执行器）

---

## 1. 一句话概览

前端用 `GET` 发起 SSE 请求，后端为每次调用**新建一个 `PlanExecuteAgent`**，从 MySQL 装载该会话最近 30 条历史记忆，然后按 **需求澄清 → 研究主题生成 → 计划-执行-批判循环（最多 3 轮）→ 最终报告流式输出** 的顺序推进；全程以 `{"type":...,"content":...}` 的 JSON 帧推给前端，`type=thinking` 进"思考过程"面板，`type=text` 进正文，最后单独推一帧 `reference` 来源列表，并把完整结果回填数据库。

---

## 2. 总体链路图

```
┌────────────────────────────── 前端 dobao-front ──────────────────────────────┐
│ useChat.sendMessage()                                                        │
│   ├─ getStreamChatUrl(agent='deep')  →  {backendUrl}/agent/deep/stream       │
│   ├─ apiStreamChat(): fetch(url?query=..&conversationId=.., Accept: text/event-stream)
│   └─ reader.read() 逐行解析 "data: {json}" → processStreamData()             │
│          text      → streamedContent（正文）                                 │
│          thinking  → streamedThinking（思考面板）                             │
│          reference → aiMsg.reference（参考来源）                              │
│          recommend → aiMsg.recommend（本链路不产生）                          │
│   停止：AbortController.abort() + GET /agent/stop?conversationId=..          │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    │ HTTP GET (SSE)
                                    ▼
┌────────────────────────── 后端 dobabo-backend ──────────────────────────────┐
│ AgentController#deepStream(query, conversationId)                            │
│   1) query 空校验 → Flux.error(IllegalArgumentException)                     │
│   2) initPlanExecuteAgent()  ← 每次请求新建实例                               │
│        builder().chatModel(chatModel)                                        │
│                 .tools(webSearchToolCallbacks)   ← Tavily MCP 工具           │
│                 .sessionService / .taskManager / .maxRounds(3)               │
│   3) agent.createPersistentChatMemory(conversationId, 30)  ← MySQL 历史       │
│   4) agent.setChatMemory(memory)                                             │
│   5) return agent.stream(conversationId, query)   ← Flux<String> (SSE body)   │
└───────────────────────────────────┬──────────────────────────────────────────┘
                                    ▼
                        PlanExecuteAgent.callInternal()
                                    │
        ┌───────────────────────────┼────────────────────────────┐
        ▼                           ▼                            ▼
  ①需求澄清阶段            ②研究主题生成阶段            ③执行循环阶段(≤3轮)
  clarifyRequirement      generateResearchTopic          executeLoop
        │                           │                            │
        │ 命中【需要补充信息】        │                    ┌───────┴────────┐
        └──► 输出暂停提示并结束 ◄────┘                    ▼                │
                                                     ④最终报告 summarizeStream
                                                          │
                                                          ▼
                                            ⑤落库 + 清理资源（doFinally）
```

---

## 3. 入口层：请求与装配

### 3.1 接口契约

| 项 | 值 |
| --- | --- |
| 方法 / 路径 | `GET /agent/deep/stream` |
| Controller | `AgentController#deepStream` |
| 入参 | `query`（必填，用户问题）、`conversationId`（必填，会话 ID） |
| 出参 | `Flux<String>`，`produces = text/event-stream;charset=UTF-8` |
| 注意 | **不接受 `fileId`**。前端 `apiStreamChat` 虽会拼 `fileId`，但深度研究链路不消费该参数（文件问答走 `/agent/chat/stream`） |

### 3.2 Agent 装配参数

`initPlanExecuteAgent()` 每次请求都新建实例，装配如下：

| 参数 | 传入值 | 实际生效情况 |
| --- | --- | --- |
| `chatModel` | Spring AI 注入的 `ChatModel` | 生效（qwen 系列，见 `application.yml`） |
| `tools` | `webSearchToolCallbacks` | 生效，来自 **Tavily MCP**（`afterPropertiesSet()` 启动时初始化一次） |
| `sessionService` | `AiSessionService` | 生效，负责历史读取与结果落库 |
| `taskManager` | `AgentTaskManager` | 生效，负责并发互斥与停止 |
| `maxRounds` | `3` | 生效，执行循环最多 3 轮 |
| `contextCharLimit` | 未传入 → 默认 `50000` | 生效，上下文超限触发压缩 |
| `maxToolRetries` | 未传入 → 默认 `2` | ⚠️ **未生效**，`executeWithRetry` 内无重试循环 |

### 3.3 记忆装配（`BaseAgent.createPersistentChatMemory`）

```
sessionService.findRecentBySessionId(conversationId, 30)   // 按 create_time DESC，LIMIT 30
   │
   ├─ 结果按时间倒序返回，代码从尾到头反转为正序
   ├─ 每条记录 → UserMessage(question) + AssistantMessage(answer) 依次写入
   └─ MessageWindowChatMemory.builder().maxMessages(30).build()
```

坑点：`answer` 为空（上一轮被停止 / 未完成）时仍会写入一条空的 `AssistantMessage`。

---

## 4. 主控层：`PlanExecuteAgent.callInternal()`

### 4.1 前置检查与初始化

```
1. checkRunningTask(conversationId)
      taskManager.hasRunningTask() == true  →  Flux.error("该会话正在执行中，请稍后再试")
2. sink = Sinks.many().unicast().onBackpressureBuffer()   // SSE 数据源
3. registerTaskInternal(): taskManager.registerTask(conversationId, sink, "plan-execute")
      同会话已有任务 → 注册失败 → Flux.error（互斥的第二道防线）
4. initTimers() / clearUsedTools()
5. 缓冲区：finalAnswerBuffer(text) / thinkingBuffer(thinking) / allReferences(搜索来源)
6. initStateAndSaveQuestion()
      OverAllState(conversationId, question)
        ├─ getChatHistory() 装载历史消息
        ├─ state.add(UserMessage(question))
        └─ sessionService.saveQuestion(...)  →  拿到 currentSessionId（后续回填答案用）
           ⚠️ firstResponseTime 此刻仍为 0，落库的就是 0（首响时间在流结束时才补）
7. clarifyRequirementPhase(... runnable 链式回调 ...)
8. registerTaskToManager(): taskManager.setDisposable(conversationId, compositeDisposable)
9. return wrapSinkWithHandlers(...)  → 作为 SSE 响应体返回
```

### 4.2 阶段编排方式（回调链，非阻塞）

三个阶段通过 `Runnable onComplete` 串起来，**不阻塞 HTTP 线程**；每阶段的模型调用用 `subscribeOn(Schedulers.boundedElastic())` 异步执行，句柄注册进 `compositeDisposable` 以便一键取消：

```
clarifyRequirementPhase( state, sink, finished, thinkingBuffer,
    onComplete = () -> generateResearchTopicPhase( ..., 
        onComplete = () -> executeLoopPhase(...) ) )
```

### 4.3 sink 包装与统一收尾（`wrapSinkWithHandlers`）

```
sink.asFlux()
  .doOnNext(chunk)      → parseAndAppendToBuffers()  // 按 type 分别累积 text / thinking
                          recordFirstResponse()      // 首个 chunk 到达时记录首响耗时
  .doOnCancel(...)      → finished=true; taskManager.stopTask()
  .doFinally(...)       → saveSessionResult()  // 结果落库
                          taskManager.stopTask()
                          compositeDisposable.dispose()
```

---

## 5. 阶段① 需求澄清 `clarifyRequirementPhase`

| 项 | 内容 |
| --- | --- |
| 先发帧 | `{"type":"thinking","content":"\n🔍 正在分析您的需求...\n"}` |
| System Prompt | `getCurrentTime() + REQUIREMENT_CLARIFICATION`（需求分析专家：判断信息是否足够，≤120 字） |
| 上下文 | `state.getMessages()`（历史 + 当前问题） |
| 调用 | `chatClient.prompt().messages(...).stream().content()` |
| 解析 | `ThinkTagParser.parse(chunk, inThink)` → 逐 `Segment` 发送；`thinking=true` → thinking 帧，`false` → 累积进 `responseBuffer`（**本阶段非思考文本也发 thinking 帧**，只用于判定，不进入最终答案） |

分支判定（`handleClarificationComplete`）：

```
responseBuffer 含 "【需要补充信息】" ?
  ├─ 是 → 推 text 帧 "⏸【暂停深入研究】<澄清问题>"  → complete() → 流程终止（不出报告）
  └─ 否 → 推 thinking "✅ 需求分析完成 / ✅ 信息充足，准备生成研究主题"
          → onComplete.run() 进入阶段②
```

---

## 6. 阶段② 研究主题生成 `generateResearchTopicPhase`

| 项 | 内容 |
| --- | --- |
| 先发帧 | `thinking: "📝 正在生成研究主题...\n"` |
| System Prompt | `getCurrentTime() + RESEARCH_TOPIC_GENERATION`（拆解 3-5 个可检索分析点） |
| 上下文 | `state.getMessages()` + `<original_question>...</original_question>` |
| 解析 | 同上 `ThinkTagParser`；非思考文本累积进 `topicBuffer` |
| 完成 | `state.setRefinedResearchTopic(topic)` → 推 `thinking: "✅ 研究主题已生成"` → 进入阶段③ |

---

## 7. 阶段③ 执行循环 `executeLoop`（核心）

### 7.1 轮次主循环

```
while (state.getRound() < maxRounds(3) && !finished && !compositeDisposable.isDisposed()) {
    state.nextRound();                          // 轮次 +1
    emit thinking "🔄 第 N 轮研究开始"

    plan = generatePlan(state)                  // 生成计划（阻塞式 .call()）
    if (plan 为空 || 所有 task.id 为 null) break; // 模型判断"信息已充分"

    emit thinking "--- 开始执行任务 ---"
    results = executePlan(plan, state)          // 分 order 串并行执行

    emit thinking "--- 任务执行完成 ---"
    critique = critique(state, plan, results)   // 批判性评估（阻塞式 .call()）

    if (critique.passed()) break;               // 通过 → 跳出循环
    state.add(AssistantMessage("【Critique Feedback】" + feedback))  // 未通过 → 反馈入上下文
    emit thinking "--- 准备进入下一轮迭代 ---"
    compressIfNeeded(state)                     // 超 50000 字符才压缩
}
emit thinking "✅ 研究阶段完成，准备生成最终报告"
summarizeStream(state, ...)                     // 阶段④
```

### 7.2 生成计划 `generatePlan`

- System：`getCurrentTime() + PLAN` + **动态注入**当前轮次、`renderToolDescriptions()`（工具名 + 描述）、`BeanOutputConverter<List<PlanTask>>` 的格式说明。
- User：`【研究主题】` + `renderFullContext()` + 硬约束（若历史含 `【Critique Feedback】`，新计划必须针对性补齐、不得重复失败尝试）。
- 结构化输出：`BeanOutputConverter<List<PlanTask>>` 转换，转换前用 `ThinkTagParser.stripThinkTags()` 去掉思考标签。
- **只发状态帧，不回传计划原文**；计划以 `thinking` 文本形式友好展示：
  ```
  📋 执行计划表：
    🟠 <task-1 instruction>
    🟠 <task-2 instruction>
  ```
- `PlanTask(id, instruction, order)`：`order` 相同 → 可并行；`order` 递增 → 存在先后依赖；`id=null` → 表示无需工具、可进入总结。

### 7.3 执行计划 `executePlan`（order 分组，组间串行 / 组内并行）

```
grouped = plan.groupBy(order)
for order in sorted(grouped.keys):                     // 组间串行
    dependencyContext = buildDependencyContext(accumulatedResults, plan, order)
        // order==1 → "无"；否则仅拼装 order-1 的已完成结果
    latch = CountDownLatch(tasks.size())
    for task in grouped[order]:                        // 组内并行
        Mono.fromRunnable {
            if disposed return; toolSemaphore.acquire() // 全局并发上限 3
            result = executeWithRetry(task, dependencyContext)
            results[task.id] = result
            if success → accumulatedResults[task.id] = output
            state.add(AssistantMessage("【Completed Task Result】...taskId/success/result/error...【End Task Result】"))
        }.subscribeOn(boundedElastic()).subscribe() → compositeDisposable
    latch.await()                                      // 等齐本 order 全部完成
```

> `state` 中每条 `【Completed Task Result】` 就是后续 `critique` 与 `summarize` 的事实依据；`extractToolResults()` 正是按该标记提取。

### 7.4 单任务执行 `executeWithRetry`（真正的 ReAct 层）

```
emit thinking "⚙️ 正在执行任务 <id> : <instruction>"
fullContext = 【Available Results】<dependencyContext> + 【Current Task】<instruction>

SimpleReactAgent.builder()
    .chatModel(chatModel).tools(tools).maxRounds(5)
    .systemPrompt(PlanExecutePrompts.EXECUTE)          // 只做"忠实整理"，不分析不推理
    .build()
    .callWithReference(null, fullContext)              // conversationId=null → 不写记忆
   │
   ├─ 结果 answer → emit thinking "执行结果: <answer>"
   ├─ 结果 searchResults → 同步累加到 allReferences（供 reference 帧与落库）
   └─ 异常 → emit thinking "❌ 任务 <id> 执行失败: <msg>"，返回 success=false
```

`SimpleReactAgent` 内部循环（非流式，`executeInternal`）：

```
messages = [REACT_AGENT_SYSTEM_PROMPT, EXECUTE systemPrompt, ...history(无), <question>fullContext</question>]
while true:
    round++;  round > maxRounds(5) → 追加"给出最终答案"指令并强制收尾（返回 forcedAnswer）
    chatClient.prompt().messages(messages).call().chatClientResponse()
    ├─ 无 toolCalls → 返回 SimpleReactResult(answer=文本, searchResults=收集到的来源)
    └─ 有 toolCalls → AssistantMessage(toolCalls) 入列
                     → 逐个 findTool(name) 并在当前线程直接 callback.call(argsJson)
                     → ToolResponseMessage 入列 → 继续下一轮
    parseSearchResult(): 解析 tavily 返回 [{ "text": { "results":[{url,title,content}] } }]
                         → AgentState.searchResults
```

> 关键点：子任务的工具调用**走同步阻塞链路**，其结果既不流式直出，也不写会话记忆，只以 `执行结果: ...` 的 thinking 帧呈现，并以 `AssistantMessage` 回流到主控 `state`。

### 7.5 批判评估 `critique`

- System：`getCurrentTime() + CRITIQUE` + `BeanOutputConverter<CritiqueResult>` 格式说明。
- User：`【用户原始问题】` + `【研究主题】` + `【当前轮次的执行计划】` + `【当前轮次的工具结果】`。
- 输出：`{"passed": true|false, "feedback": "..."}` → `passed=true` 结束循环；`false` 则反馈入 `state` 并进入下一轮。

### 7.6 上下文压缩 `compressIfNeeded`

```
state.currentChars() < contextCharLimit(50000) → 直接返回
否则：
  emit thinking "📦 上下文过长，正在压缩..."
  chatModel.call(getCurrentTime + 硬性字符上限 + COMPRESS, state.renderFullContext())
  state.clearMessages() → state.add(SystemMessage("【Compressed Agent State】\n" + 压缩结果))
```

补充：`OverAllState.renderFullContext()` 在渲染时会**过滤掉较早轮次的 `【Critique Feedback】`，只保留最近一次**，避免历史反馈持续污染新计划。

---

## 8. 阶段④ 最终报告 `summarizeStream`

| 项 | 内容 |
| --- | --- |
| 先发帧 | `thinking: "\n📝 正在生成最终研究报告...\n\n"` |
| System Prompt | `getCurrentTime() + SUMMARIZE`（只依据检索结果、实事求是、Markdown 报告、不提中间过程） |
| User | `【用户原始问题】` + `【研究主题】` + `【工具检索结果】`（`state.extractToolResults()`，为空时给"（未检索到相关结果）"） |
| 调用 | `chatClient.prompt().messages(...).stream().chatResponse()`，`publishOn(boundedElastic)` |
| 解析 | `ThinkTagParser.parse(text, holder)`：`thinking=true` → **thinking 帧**；`false` → 累积 `finalAnswerBuffer` 并推 **text 帧**（正文） |
| 完成 | `allReferences` 非空 → 推一帧 `reference`（JSON 数组，含 `count`）→ `complete(sink)` |

---

## 9. SSE 数据协议

统一由 `AgentResponse.json(type, content)` 序列化为一行 JSON（`type` / `content` / 可选 `count`）：

| type | 产生位置 | 前端行为 |
| --- | --- | --- |
| `thinking` | 全过程进度提示、计划表、任务执行结果、模型 ` thinking` 内容 | 追加到思考面板 |
| `text` | 阶段①的暂停提示、阶段④的正文增量 | 追加到正文 |
| `reference` | 阶段④完成后一次性推送 `allReferences` | 解析为来源列表（`processReferences`） |
| `recommend` | 本链路**不产生**（`generateRecommendations` 未被调用） | — |
| `error` | 异常分支（`AgentResponse.error`） | 前端按解析失败或断流处理 |

前端解析要点（`useChat.ts`）：按 `\n` 切行 → 取 `data: ` 前缀 → 特殊判断 `[DONE]` → `JSON.parse` 后交给 `processStreamData`；同时兼容无前缀的裸 JSON 行与残留 buffer 兜底。

---

## 10. 停止 / 取消 链路

```
前端 stopMessage()
  ├─ AbortController.abort()      // 主动断开 SSE
  └─ GET /agent/stop?conversationId=..
        └─ AgentTaskManager.stopTask(conversationId)
             ├─ TaskInfo.disposable.dispose()  → 即 compositeDisposable.dispose()
             │     （取消所有阶段订阅：澄清/主题/执行任务/总结）
             ├─ sink.tryEmitNext({"type":"text","content":"⏹ 用户已停止生成\n"})
             ├─ sink.tryEmitComplete()
             └─ taskMap.remove(conversationId)
```

各阶段对"被停止"的处理：

- 阶段① / ②：`doOnError` → `handleError`。
- 执行循环：捕获 `InterruptedException` / `compositeDisposable.isDisposed()`，日志降级为 `info`（不当作错误），并补推一帧 `⏹ 用户已停止生成`。
- 任务级：`executeWithRetry` 在入口与 `SimpleReactAgent` 返回后各查一次 `isDisposed()`，避免停止后继续写 `state`。
- 无论何种结束方式，`doFinally` 都会执行 `saveSessionResult()`，因此**中途停止也会把已产生的部分答案落库**。

---

## 11. 持久化与状态模型

### 11.1 数据库写入时序（`ai_session` 表）

| 时机 | 方法 | 写入字段 |
| --- | --- | --- |
| 流程启动 | `saveQuestion` | `session_id`、`question`、`agent_type`（默认 `"chat"`，**本链路未显式传 plan-execute**）、`first_response_time`（此时为 0） |
| 流结束（`doFinally`） | `updateAnswer` | `answer`（finalAnswerBuffer）、`thinking`（thinkingBuffer）、`tools`、`reference`（reference 帧同构 JSON）、`recommend`、`first_response_time`、`total_response_time` |

> `finalAnswerBuffer` 与 `thinkingBuffer` 的累积发生在 `wrapSinkWithHandlers().doOnNext()`：按帧 `type` 分流，`thinking` 帧全部计入 thinking（**包含计划、进度、任务执行结果等过程信息**）。

### 11.2 关键数据结构

| 类 | 作用 |
| --- | --- |
| `OverAllState` | 主控状态：`conversationId` / `question` / `messages` / `round` / `refinedResearchTopic`；提供 `renderFullContext()`、`extractToolResults()`、`currentChars()` |
| `PlanTask(id, instruction, order)` | 计划任务；`order` 决定并行/串行 |
| `TaskResult(taskId, success, output, error)` | 单任务执行结果 |
| `CritiqueResult(passed, feedback)` | 批判评估结果，`passed` 控制是否继续迭代 |
| `SearchResult(url, title, content)` | 检索来源，汇总进 `allReferences` |
| `SimpleReactResult(answer, searchResults)` | 子 ReAct 执行器返回体 |
| `AgentState` | 子 ReAct 内部的跨轮检索结果容器 |
| `AgentTaskManager.TaskInfo(sink, disposable, agentType)` | 会话级任务登记项，支撑互斥与停止 |
| `ThinkTagParser` | 无状态解析器，把流式 chunk 拆成 `thinking/normal` 段并跨 chunk 追踪状态 |

---

## 12. 并发与线程模型

| 环节 | 线程 |
| --- | --- |
| HTTP 请求线程 | 仅做装配，立即返回 `Flux`，不阻塞 |
| 阶段①②、执行循环、总结 | `Schedulers.boundedElastic()`（`subscribeOn` / `publishOn`） |
| 组内并行任务 | 每个 task 一个 `boundedElastic` 线程，`Semaphore(3)` 限制同时执行数 |
| 子 ReAct 工具调用 | 在任务线程内**同步串行**执行 |

互斥语义：同一 `conversationId` 同时只允许一个任务，靠 `AgentTaskManager.taskMap` 保证（`checkRunningTask` 提前拦截 + `registerTask` 二次拦截）。

---

## 13. 关键设计要点与已识别问题

**设计亮点**

1. 阶段化 + 回调链驱动，天然支持流式进度反馈与中途取消。
2. `order` 分组实现"能并行则并行、有依赖则串行"，并把上一个 order 的结果作为下游任务的显式依赖上下文。
3. 批判闭环（Critique → Feedback 回灌 → 下一轮增量补充）配合上下文压缩，兼顾研究深度与上下文成本。
4. `ThinkTagParser` 跨 chunk 追踪 ` thinking` 状态，使模型思考内容能独立成帧而不污染正文。

**已识别问题（梳理结论，非修改）**

| # | 问题 | 位置 | 影响 |
| --- | --- | --- | --- |
| 1 | `maxToolRetries` 传入但未使用，`executeWithRetry` 实际只有一次尝试 | `PlanExecuteAgent#executeWithRetry` | 命名与行为不一致，工具偶发失败即标记任务失败 |
| 2 | `PlanExecuteAgent.Builder` 的 `maxToolRetries` / `contextCharLimit` 默认值注释与数值不符（注释 20000，代码 50000） | `PlanExecuteAgent.Builder` | 文档误导 |
| 3 | `saveQuestion` 时 `firstResponseTime` 恒为 0（`initTimers` 刚执行、尚无首帧） | `initStateAndSaveQuestion` | 首响耗时字段初值无意义，依赖结尾 `updateAnswer` 覆盖 |
| 4 | `saveQuestion` 未传 `agentType` | `initStateAndSaveQuestion` | 落库 agent_type 恒为 `"chat"`，无法区分深度研究 |
| 5 | 阶段①/② 的非思考文本也以 `thinking` 帧下发 | `clarifyRequirementPhase` / `generateResearchTopicPhase` | 前端思考面板混入非思考内容（阶段④已正确区分） |
| 6 | `emit(sink, finished, content, type, thinkingBuffer)` 重载未使用 `thinkingBuffer` 参数 | `PlanExecuteAgent#emit` | 冗余参数，易误解为会写入缓冲 |
| 7 | 子任务结果未做流式呈现，只以 `执行结果: ...` 一次性 thinking 帧输出 | `executeWithRetry` | 长任务期间前端"思考"停顿感明显 |
| 8 | `/agent/deep/stream` 不接受 `fileId`，但前端 `apiStreamChat` 会拼接该参数 | `AgentController#deepStream` / `api/index.ts` | 参数被静默忽略 |
| 9 | 上一轮被停止时 `answer` 为空仍写入 `AssistantMessage("")` | `BaseAgent#createPersistentChatMemory` | 历史记忆中出现空助手消息 |
| 10 | 停止时 `stopTask` 会被调用两次（`doOnCancel`/执行循环 + `doFinally`） | `PlanExecuteAgent` | 第二次仅告警"没有正在执行的任务"，无功能影响 |

---

## 14. 端到端时序（一次完整深度研究）

```
前端                 Controller          PlanExecuteAgent            子ReAct        模型/MCP        MySQL
 │ GET /agent/deep/stream │                    │                        │              │              │
 ├───────────────────────►│                    │                        │              │              │
 │                        ├─ builder/setMemory ─┤                       │              │              │
 │                        │                    ├─ findRecent(30) ──────────────────────────────────►│
 │                        │                    │◄──────────── 历史记录 ─────────────────────────────┤
 │                        │                    ├─ saveQuestion(question) ─────────────────────────►│
 │                        │◄──── Flux<String> ─┤                        │              │              │
 │◄══ thinking 分析需求 ═══┤                    ├─ 澄清 .stream() ─────────────────────►│              │
 │◄══ thinking 主题/进度 ══┤                    ├─ 研究主题 .stream() ──────────────────►│              │
 │                        │                    │  ┌─ 循环 轮1..3 ──────────────────┐   │              │
 │◄══ thinking 计划表 ═════┤                    ├──┤ generatePlan .call() ────────►│   │              │
 │◄══ thinking 任务/结果 ══┤                    │  │ executePlan → SimpleReactAgent ──► tool_call ──► Tavily MCP
 │                        │                    │  │      （结果入 state / allReferences）          │
 │◄══ thinking 评估 ═══════┤                    ├──┤ critique .call() ────────────►│   │              │
 │                        │                    │  └─ 未通过 → Feedback 入 state / 压缩 ─┘              │
 │◄══ thinking 生成报告 ═══┤                    ├─ summarizeStream .stream() ──────────►│              │
 │◄══ text 正文增量 ═══════┤                    │                        │              │              │
 │◄══ reference 来源 ══════┤                    ├─ complete(sink)        │              │              │
 │                        │                    ├─ doFinally: updateAnswer ────────────────────────►│
 │◄══ [流结束] ════════════┤                    └─ dispose 资源          │              │              │
```

---

## 15. 快速定位索引

| 关注点 | 文件 |
| --- | --- |
| 接口定义 | `dobao-backend/src/main/java/com/dobao/dobaobackend/controller/AgentController.java` |
| 主控 Plan-Execute | `dobao-backend/src/main/java/com/dobao/dobaobackend/agent/deeppresearch/PlanExecuteAgent.java` |
| 子任务 ReAct | `dobao-backend/src/main/java/com/dobao/dobaobackend/agent/deeppresearch/SimpleReactAgent.java` |
| 基类（记忆/响应/计时） | `dobao-backend/src/main/java/com/dobao/dobaobackend/agent/BaseAgent.java` |
| 任务与停止 | `dobao-backend/src/main/java/com/dobao/dobaobackend/service/AgentTaskManager.java` |
| 状态与消息渲染 | `dobao-backend/src/main/java/com/dobao/dobaobackend/entity/vo/OverAllState.java` |
| SSE 帧协议 | `dobao-backend/src/main/java/com/dobao/dobaobackend/common/AgentResponse.java` |
| 思考标签解析 | `dobao-backend/src/main/java/com/dobao/dobaobackend/utils/ThinkTagParser.java` |
| 提示词 | `dobao-backend/src/main/java/com/dobao/dobaobackend/prompts/PlanExecutePrompts.java` |
| 落库实现 | `dobao-backend/src/main/java/com/dobao/dobaobackend/service/impl/AiSessionServiceImpl.java` |
| 前端 SSE 消费 | `dobao-front/src/composables/useChat.ts` |
| 前端请求构造 | `dobao-front/src/api/index.ts` |
