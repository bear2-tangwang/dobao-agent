# 面试总结流水线重构方案（Plan-Execute + 状态机 + 分步 LLM）

> 状态：**方案待确认**（未开工）。本文只描述"要改成什么样、为什么"，不含实现代码。
> 目标读者：本项目维护者。所有结论都对应到现有代码位置，便于逐条反驳。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 要不要改成 plan-execute？ | **要，但只改"分析 + 报告"这一段**。转写等待（提交百炼 → `@Scheduled` 轮询）保持现有 DB 驱动，不搬进 agent 循环（那会破坏"不占线程 / 重启续跑 / 30 分钟超时兜底"三条已论证的设计） |
| 要不要 ReAct？ | **不要硬套**。本场景没有工具调用与外部观察，ReAct 的内循环只会空转。真正要借的是 **Plan(步骤 DAG) + Execute + Critique/Validate + 断点续跑** |
| 状态机怎么落？ | **单枚举 `InterviewStage` + 每状态一个 Handler（策略注册表）+ 状态落库**。形态抄本项目 PPT 模块（`PptInstStatus` + `PptStateStrategyFactory` + 断点重连），思想抄 deep research（`PlanTask.order` 分层并行 + critique 反馈重入） |
| 报告拆几步？ | 角色判定（已有）→ **① 问答参考回答 → ② 知识点总结 → ③ 薄弱点 → ④ 文档生成**，每步独立 SystemPrompt、独立落库、独立可重试。共 5 次 LLM 调用（现在 2 次） |
| 第 4 步纯 LLM 出 Markdown？ | **推荐"LLM 出章节正文 + 代码装配逐字数据"**。原因：`maxTokens: 10000`，20 分钟录音光"问答清单 + 完整对话"就超过上限；且让模型复述逐字原文会毁掉 AC-12"逐字未改写"。详见 §5 |
| 最大风险 | 延迟与成本（3~5 分钟 → 6~12 分钟）、AC-13"连跑 3 次一致"对文档步骤不再成立（需放宽口径） |

---

## 1. 现状诊断（不是"乱"，是缺"步"这个概念）

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | 编排、状态机、ASR 轮询、LLM 调用、报告装配、MinIO、SSE 打点全在一个类 | `service/InterviewTaskService.java`（401 行，14 个依赖） | 改任何一步都要读整个类；单测无从下手 |
| 2 | `analyze()` 一个方法串了 5 件事 | 同上 `:293-344` | 失败无法定位到"哪一步"，只能看日志 |
| 3 | 没有步状态：任何异常 → 整条记录 `FAILED` | 同上 `runAnalysis` | `/retry` 从头重跑（角色判定 + 报告生成），已成功的部分作废；重复计费 |
| 4 | 一次 LLM 调用同时产"知识点 + 待补充" | `interview/InterviewReportGenerator.java` | 输出被 `maxTokens` 截断的风险集中在这一处，`LlmOutputTruncatedException` 就是为它写的 |
| 5 | 报告格式有 **三份** 定义 | 后端 `InterviewReportRenderer.render`、前端 `useInterview.reportToMarkdown`、SSE `complete` 里的 `report_json` | 格式一改要动三处；页面展示与下载文件可能不一致 |
| 6 | 报告里只有"候选人原话"，没有"应该怎么答" | `InterviewReport.qaList`（`QaItem` 无参考答案字段） | 复盘价值低：用户知道被问了什么，不知道答得对不对 |
| 7 | 问答清单里寒暄/流程/改约也各占一条 | `QaListBuilder` 类注释自陈"5.5 分钟录音 20 条，真提问只有 2 条" | 参考回答与知识点归纳被噪声稀释 |

> 第 7 条正好是新方案第 ① 步的副产品：让模型在写参考答案时顺带判定"这是不是真提问"，噪声问题一起解掉。

---

## 2. 两个参考架构，各借什么

### 2.1 deep research：`PlanExecuteAgent`（1224 行）

骨架是**阶段回调串联**（`clarifyRequirementPhase → generateResearchTopicPhase → executeLoopPhase`），核心循环：

```java
while (state.getRound() < maxRounds) {
    List<PlanTask> plan = generatePlan(state, ...);        // LLM 出计划（含 order 依赖层）
    Map<String, TaskResult> results = executePlan(plan, ...); // order 相同 → 并行，不同 → 串行
    CritiqueResult critique = critique(state, plan, results, ...);
    if (critique.passed()) break;
    state.add(new AssistantMessage("【Critique Feedback】" + critique.feedback())); // 反馈重入下一轮
}
summarizeStream(state, ...);                               // 最后汇总成报告
```

值得借：
- **步骤 = 数据（`PlanTask(id, instruction, order)`）**，不是硬编码调用顺序 → 进度、重试、依赖都变成数据；
- **`order` 分层并行**：同层并行、跨层串行（面试的四步正好是一个 3 层 DAG，见 §3.3）；
- **critique 反馈重入**：把校验失败原因塞回下一次 prompt，而不是简单重试；
- **每阶段一个 SystemPrompt**，用户要求的正是这一点。

**不照搬**：
- ❌ LLM 生成计划 —— 面试总结的 DAG 是固定的，让模型规划只会引入不确定性；
- ❌ 纯内存态（`OverAllState` 挂在对象里）—— 面试链路要 `@Scheduled` 重启续跑，状态必须落库；
- ❌ 阶段回调套 4 层 `Runnable` —— 那是为了流式对话的即时反馈，面试是后台任务，用不着。

### 2.2 PPT 模块：状态 + 策略 + 断点重连

```java
// PptStateStrategyFactory：（静态 Map<状态, 策略>）
STRATEGY_MAP.put(PptInstStatus.REQUIREMENT, new RequirementStrategy());
...
public void executeNextState(AiPptInst inst, ...) {
    AiPptInst latestInst = context.getPptInstService().getById(inst.getId()); // 重新读库
    if (latestInst.getErrorMsg() != null && ... ) { /* 清错误，允许继续 */ }  // 断点重连
    getStrategy(inst.getStatusEnum()).execute(inst, ...);                    // 按状态分发
}
```

值得借：
- **状态枚举带 `code` + `desc`，持久化到表列**（`AiPptInst.status` 是 String，`getStatusEnum()` 转枚举）；
- **每状态一个 Strategy 类**，注册表分发 —— 这样"每一步"是文件级的，不会长成一个上帝类；
- **断点重连**：重新进入时读库、按状态继续 —— 但触发靠**用户话术**（`PptIntent.RESUME_PPT`，关键词"继续/重试"），不是自动恢复。

**要改掉的地方**：静态 `Map` + 单例 + 策略内部递归调用下一个策略（`PPTBuilderAgent:205/272`）。新方案用 **Spring 注入 `Map<InterviewStage, Handler>`**，由驱动器统一推进，handler 不互相调用。

### 2.3 面试模块已有的骨架（保留）

- `ai_interview.status` 已经是持久化状态列；
- `@Scheduled` 扫 `status=TRANSCRIBING` 驱动转写轮询 —— 这就是"DB 驱动的状态机调度器"，新方案的续跑调度直接复用同一思路；
- `InterviewProgressHub` 已是进程内 SSE 广播，事件名/字段是前后端契约 —— 只扩展 stage 取值，不改协议。

### 2.4 三者对照

| 维度 | deep research | PPT | 本方案（面试） |
|---|---|---|---|
| 状态存哪 | 内存 `OverAllState` | **DB 列** | **DB 列**（+ `pipeline_json` 放运行态） |
| 步骤定义 | LLM 生成 `PlanTask` | 代码里固定枚举 | **代码里固定 DAG（数据化，落库可见）** |
| 分发 | 阶段回调 | 静态工厂 Map | **Spring 注入 Map<Stage, Handler>** |
| 重试 | `executeWithRetry` + critique 重规划 | 失败策略 | **每步 attempt + 校验反馈重入 + 步级重试** |
| 续跑 | 无（断了就断了，状态纯内存） | 半自动（状态落库，靠用户话术触发） | **全自动（状态落库 + `@Scheduled` 抢锁续跑）** |
| 进度 | Sink 流式 | Sink 流式 | **复用现有 SSE Hub（分步 progress 帧）** |

### 2.5 关于 ReAct 的判断（重要）

ReAct = `think → act(工具) → observe` 循环，价值来自**外部工具能带回新信息**（deep research 里是联网搜索）。
面试总结的四步里没有任何工具调用：输入全是已经落库的转写稿，输出是文本，模型"思考"再多也不会得到新事实。硬套 ReAct 的代价是多一层循环 + 更多 token，收益为零。

**但架构要留位**：`InterviewStageHandler` 是接口，将来若要"按岗位检索常见考点 / 面经对照"（需要外部检索），新增一个 `SearchReActHandler` 即可，其余步骤不受影响。

### 2.6 参考架构的已知缺陷（**不要一起搬过来**）

逐条读过代码后确认的问题，每条都对应到位置；新方案必须避开：

| 缺陷 | 位置 | 新方案怎么避开 |
|---|---|---|
| **执行状态纯内存，进程重启即丢**（`AgentTaskManager` 只有一个 `ConcurrentHashMap`；`ai_session` 连 status 列都没有） | `service/AgentTaskManager.java` | 状态落 `ai_interview.pipeline_json`，`@Scheduled` 抢锁续跑 |
| `maxToolRetries` 是**死配置**（`executeWithRetry` 里没有重试循环，失败即返回） | `PlanExecuteAgent` Builder 默认 2 | 重试逻辑写在驱动器里，并有单测 |
| "先 `checkRunningTask` 再 `registerTaskInternal`" 非原子，存在并发窗口 | `PlanExecuteAgent:192-204` | 条件 UPDATE 抢锁（原子性交给数据库） |
| `Sinks.unicast()` 绑死请求生命周期：无 stream id、无 offset、断线即丢进度 | 三个 Agent 一致 | 沿用现有 `InterviewProgressHub`（multicast + snapshot + 以 DB 为事实源） |
| 会话/问题等运行态挂在 Agent **实例字段**上，只能靠"每请求 new 一个"避免串数据 | `BaseAgent.currentConversationId/currentQuestion/...` | Handler 无状态，上下文用不可变 `StageContext` 传参 |
| JSON 约束只靠提示词文本 + `stripThinkTags` 后直接 `converter.convert`，**没有截断检测** | `PlanExecuteAgent.generatePlan:734-739` | 继续用 `LlmJsonSupport`（它已有 `finishReason=length` 检测），不退化 |
| 未接上的死代码/死字段：`BaseAgent.extractJsonArray`、`SimpleReactAgent.maxReflectionRounds/advisors` | — | 新代码不留"声明了但没接上"的开关 |

> 一句话：**借鉴它的"计划分层 + 每步执行 + critique 反馈重入"，不借鉴它的"内存态 + 无续跑 + 请求绑定的流"。**
> 面试模块在"状态落库 + SSE 兜底"这两点上本来就比它强，别改差。

---

## 3. 目标架构

### 3.1 分层

```
HTTP 层        InterviewController（upload / stream / status / report / report/download / retry）
                        │
驱动层         InterviewPipeline            取下一个可执行状态 → 执行 → 落库 → 推事件 → 循环/挂起
               InterviewPipelineScheduler   @Scheduled 续跑与超时（复用现有转写轮询的思路）
                        │
状态层         InterviewStage               单枚举（含 phase()/description()/stageKey()/isTerminal()）
               InterviewPipelineState       运行态（每步 status/attempt/latency/tokens/error），落 pipeline_json
                        │
执行层         InterviewStageHandler        接口：supports() / execute(ctx) / validate(outcome)
               stage/RoleResolveHandler、QaListHandler、ReferenceAnswerHandler、
                     TopicSummaryHandler、GapAnalysisHandler、DocumentComposeHandler、ReportStoreHandler
                        │
能力层         LlmJsonSupport（结构化，已有）  LlmTextSupport（长文本 + 截断检测，新增）
               GroundingValidator（防编造，迁移自 InterviewReportGenerator.isGrounded）
                        │
产物层         InterviewReport（report_json）/ 报告 Markdown（MinIO + report_file_url）
```

### 3.2 状态机全表

| stage | 驱动者 | 输入 | 输出（产物） | 落库 | 失败策略 |
|---|---|---|---|---|---|
| `UPLOADED` | 请求线程 | file | MinIO + 记录 | 已有 | FAILED |
| `SUBMITTING` | `@Async` | objectName | `asr_task_id` | 已有 | FAILED |
| `TRANSCRIBING` | **`@Scheduled` 轮询** | task_id | `transcript_json` | 已有 | 超时 → FAILED |
| `TRANSCRIBED` | 事件 | `transcript_json` | — | — | — |
| `RESOLVING_ROLE` | Pipeline | transcript | `interviewer_speaker_id` | 已有列 | 步级重试 ×2 → FAILED |
| `BUILDING_QA` | Pipeline | transcript + role | 问答清单（**纯代码，零 LLM**） | pipeline_json | 同上 |
| `ANSWERING` | Pipeline | 问答清单 | 每条 Q 的**参考回答** + 真提问判定 | pipeline_json | 截断 → 分片重试 → 降级 |
| `SUMMARIZING_TOPICS` | Pipeline | 参考答案（+qaId 映射） | 知识点清单 | pipeline_json | 同 ANSWERING |
| `FINDING_GAPS` | Pipeline | 问答清单（+参考答案） | 薄弱知识点 + 补充方向 | pipeline_json | 同 ANSWERING |
| `COMPOSING_DOC` | Pipeline | ①②③ 产物 | 报告文档（Markdown 章节正文） | pipeline_json | 截断 → 分节重试 |
| `STORING` | Pipeline | ①~④ | `report_json` + MinIO Markdown | `report_json` / `report_file_url` | 上传失败步级重试 ×2 |
| `READY` / `FAILED` | 终态 | — | — | `status` | — |

**关键决定：单枚举，不再保留两个状态字段。**
现状的 `InterviewStatus`（6 值，`ai_interview.status`）升级为 `InterviewStage`（13 值，仍写同一列），并提供一个 `phase()` 方法（`UPLOAD` / `TRANSCRIBE` / `ANALYZE` / `DONE`）给"粗粒度判断"用：

```java
public enum InterviewStage {
    UPLOADED(Phase.UPLOAD), SUBMITTING(Phase.TRANSCRIBE), TRANSCRIBING(Phase.TRANSCRIBE),
    TRANSCRIBED(Phase.ANALYZE), RESOLVING_ROLE(Phase.ANALYZE), BUILDING_QA(Phase.ANALYZE),
    ANSWERING(Phase.ANALYZE), SUMMARIZING_TOPICS(Phase.ANALYZE), FINDING_GAPS(Phase.ANALYZE),
    COMPOSING_DOC(Phase.ANALYZE), STORING(Phase.ANALYZE), READY(Phase.DONE), FAILED(Phase.DONE);
}
```
理由：两个状态字段（粗 + 细）必然出现"粗细不一致"的 bug；`status` 列本来就是 String，升级取值不需要改表结构类型，只需一条迁移 SQL（§6）。

### 3.3 步骤 DAG 与并行层（对应 `PlanTask.order`）

```
order 0: RESOLVING_ROLE → BUILDING_QA           （串行，后者依赖前者）
order 1: ANSWERING  ‖  FINDING_GAPS              （互不依赖 → 可并行）
order 2: SUMMARIZING_TOPICS                      （依赖 ANSWERING）
order 3: COMPOSING_DOC                           （依赖 ①②③）
order 4: STORING
```
- v1 **先串行实现**（同一时刻一个 LLM 调用，日志/排查简单、token 峰值低）；
- 并行开关 `interview.pipeline.parallel=true` 后再放开 order 1 —— `interviewExecutor` 线程池 8 线程，一场面试多占 1 个线程可接受；
- 无论串并行，**依赖关系写在 `InterviewStage` 里**（`dependsOn()`），驱动器按依赖挑选下一个可执行步，这样"计划"是数据而不是调用顺序。

### 3.4 类清单（新增 / 改造 / 删除）

**新增**（`interview/pipeline/`）
```
InterviewStage.java              状态枚举 + phase()/dependsOn()/description()/stageKey()/isTerminal()
InterviewPipelineState.java      运行态 record（steps: Map<Stage, StepState>, artifacts, round…）
StepState.java                   单步状态（PENDING/RUNNING/DONE/SKIPPED/FAILED + attempt/latencyMs/tokens/errorMsg）
StageOutcome.java                单步产物（结构化对象或文本 + 校验结论）
InterviewStageHandler.java       接口：Stage stage(); StageOutcome execute(StageContext ctx); List<String> validate(...)
StageContext.java                只读上下文（record、transcript、qaList、上游产物、SSE 回调）
InterviewPipeline.java           驱动器：抢锁 → 选步 → 执行 → 校验 → 落库 → 推事件 → 循环
InterviewPipelineScheduler.java  @Scheduled 续跑/超时（30 秒一次，条件更新抢锁）
InterviewStageRegistry.java      Spring 注入 List<Handler> → Map<Stage, Handler>（替代静态工厂）
stage/*Handler.java              7 个实现（见 3.1）
validate/GroundingValidator.java 防编造（从 InterviewReportGenerator 抽出）
validate/DocumentValidator.java  文档完整性（必备章节、末节闭合）
```
**改造**
```
service/InterviewTaskService.java  → 拆成 InterviewAsrService（提交/轮询/落库）+ 由 Pipeline 承担分析段
service/InterviewService.java      → 只留 upload/查询/retry（去掉报告装配）
prompts/InterviewPrompts.java      → 拆成 5 组 system + user 构造（见 §4）
config/InterviewProperties.java    → 新增 pipeline 段（并行开关、每步模型/最大输出、重试次数）
progress/InterviewProgressHub.java → stage 取值扩展（协议字段不变）
```
**删除**
```
interview/InterviewReportGenerator.java（职责拆到 TopicSummary/GapAnalysis/ReportStore 三个 handler）
interview/InterviewReportRenderer.java（Markdown 章节正文交给 LLM，装配只有一处 assembler）
front/src/composables/useInterview.ts 里的 reportToMarkdown（前端不再自己拼报告）
```

### 3.5 持久化模型

`ai_interview` 新增两列（`report_json` / `report_file_url` / `report_file_name` 复用）：

```json
// pipeline_json（运行态；每步一条，便于"哪一步慢/哪一步被截断"直接查库）
{
  "planVersion": 1,
  "steps": {
    "RESOLVING_ROLE":    {"status": "DONE", "attempt": 1, "latencyMs": 41000, "tokens": 5200},
    "ANSWERING":         {"status": "DONE", "attempt": 2, "latencyMs": 118000, "tokens": 9300,
                          "validation": ["Q003 缺参考回答，已重试"]},
    "SUMMARIZING_TOPICS":{"status": "DONE", "attempt": 1, "latencyMs": 96000, "tokens": 7100},
    "FINDING_GAPS":      {"status": "SKIPPED", "reason": "整场无技术内容"},
    "COMPOSING_DOC":     {"status": "DONE", "attempt": 1, "latencyMs": 132000, "tokens": 8800},
    "STORING":           {"status": "RUNNING", "attempt": 1}
  },
  "artifacts": {
    "referenceAnswers": [{"qaId": "Q002", "isRealQuestion": true, "referenceAnswer": "..."}],
    "topics": [{"topic": "Redis", "points": ["..."], "relatedQaIds": ["Q002"]}],
    "gaps":   [{"point": "...", "performance": "...", "why": "...", "directions": ["..."]}],
    "documentMarkdown": "## 一、问答清单…"
  },
  "lock": {"owner": "instance-a", "at": "2026-10-11T10:22:31"}
}
```
> `documentMarkdown` 同时进 MinIO（`report_file_url`）与 `report_json`（前端一次请求就能渲染，沿用现在 `complete` 帧带 `report_json` 的做法）。20KB/条 的重复存储在此项目规模下可接受；不引入第三个存储位置。

### 3.6 幂等与抢占（防重复执行）

- **每步幂等**：产物按 stage 覆盖写同一份 `pipeline_json`，重跑不产生重复副作用；
- **条件更新抢锁**（多实例/双触发安全）：
  ```sql
  UPDATE ai_interview SET stage = #{next}, pipeline_json = #{json}
   WHERE interview_id = #{id} AND stage = #{expected}
  ```
  影响行数 = 0 说明别人已经推进过 → 本次放弃执行（乐观锁，无需引入分布式锁组件）；
- **超时续跑**：`@Scheduled` 每 30 秒找 `stage` 非终态且 `update_time < NOW() - 3 分钟` 的记录重新入队（等价于现有 ASR 轮询的兜底，防"线程池丢了/实例重启"）；
- 转写段保持现状（外部任务天然幂等，重复查询无副作用）。

### 3.7 失败、重试、降级

| 情形 | 处理 |
|---|---|
| 单步抛 `LlmOutputTruncatedException` | 该步 `attempt++` 重试；ANSWERING/COMPOSING 走"分片策略"（§4.8） |
| 单步校验不通过（如缺参考答案、引用不存在的 qaId） | 把校验结论作为 **critique 反馈** 拼进下一次 user 消息（借用 deep research 的做法），重试 1 次 |
| 重试仍失败 | `interview.pipeline.degrade-on-failure`：`true` 则跳过该步（记 `SKIPPED` + 日志 + 报告里标注"本场未生成 X"），`false` 则整条 FAILED |
| 整条 FAILED | `/retry` **从失败步继续**（不再从头重跑）；`pipeline_json` 保留已完成步的产物 |
| MinIO 上传失败 | 只重试 `STORING`，不重跑任何 LLM 步 |

这条正是现在最缺的：现在 `/retry` 会把 5 次调用全部重跑一遍。

---

## 4. 四个新 LLM 步骤（提示词与输出契约）

每次调用都有**独立 SystemPrompt**（用户明确要求），统一走 `LlmJsonSupport`（低温 0.1 + 固定种子 + `extraBody` 关思考），并按步覆盖模型。

### 4.1 `RESOLVING_ROLE`（已有，保留）
`ROLE_RESOLUTION_SYSTEM` 不变，输出 `RoleJudgment(interviewerSpeakerId, reason)`。

### 4.2 `ANSWERING` —— 问答参考回答（新，LLM#2）

- **输入**：问答清单（qaId + 面试官原话 + 候选人原话 + 时间戳）
- **SystemPrompt 要点**：
  ```
  你是资深技术面试官。输入是一场面试的问答清单（Q 为面试官原话，A 为候选人原话）。
  对每一条：
  1. 判定 isRealQuestion：这是"真提问"还是寒暄/流程说明/改约协调（后者 false）；
  2. 为真提问写 referenceAnswer：一个能在真实面试中拿到"通过"的回答，150~300 字，
     结构是"结论先行 + 关键点 + 取舍/边界"；**不得超出问题的知识范围**，不要编造候选人的经历；
  3. 给出 keyPoints：该回答必须覆盖的 2~4 个要点（短句）。
  严格约束：只输出 JSON；qaId 必须是输入中出现过的编号；不得改写输入里的 Q/A 原文。
  ```
- **输出（BeanOutputConverter）**：
  ```java
  record ReferenceAnswerDraft(String qaId, boolean isRealQuestion,
                              String referenceAnswer, List<String> keyPoints) { }
  ```
- **校验（确定性）**：每条输入 qaId 都有对应输出；`isRealQuestion=true` 时 `referenceAnswer` 非空且长度 ≥ 阈值；`keyPoints` 非空。
- **副产品**：`isRealQuestion` 让报告可以把 20 条噪声折叠成"2 条真提问 + 18 条流程记录"，一并解决 §1 的第 7 条。

### 4.3 `SUMMARIZING_TOPICS` —— 知识点总结（新，LLM#3）

- **输入**：**上一步的参考答案 + keyPoints**（用户指定），并附 qaId → 原始问答 的映射，便于模型引用
- **SystemPrompt 要点**：
  ```
  你是技术面试复盘助手。输入是这场面试"真提问"的参考答案与要点。
  请归纳候选人**实际展现出来**的知识点，按主题归类：
  - topic：主题名（如 Redis / JVM / MySQL 索引）；
  - points：该主题下具体答到的要点，**必须能在输入里找到依据**，不得编造；
  - relatedQaIds：这些要点出自哪些问答（必须是输入中出现过的编号）。
  只输出 JSON。
  ```
- **输出**：`List<KnowledgeTopic>`（现有 DTO 复用）
- **校验**：`GroundingValidator`（整串命中或 2-gram 覆盖率 ≥ 0.6，逻辑从 `InterviewReportGenerator.isGrounded` 迁来）+ `relatedQaIds` 必须真实存在。

### 4.4 `FINDING_GAPS` —— 薄弱知识点（新，LLM#4）

- **输入**：问答清单（候选人原话为主，可附参考答案作为对照）
- **SystemPrompt 要点**：
  ```
  你是技术面试官。请判断候选人**哪些知识点答得不充分、答错或没答**：
  - point：知识点；performance：本次表现（基于输入的候选人原话，不得臆测）；
  - why：为什么要补；directions：补充方向（只给方向，不给课程名或链接）；
  - 若整场没有技术内容，返回空数组，不要凑数；不做评分、不做总评。
  ```
- **输出**：`List<KnowledgeGap>`（现有 DTO 复用）
- **校验**：不做词面 grounding（它描述的本就是"没答好"，见现有代码注释的取舍），只校验字段非空与数量上限。

### 4.5 `COMPOSING_DOC` —— 文档生成（新，LLM#5）

见 §5（这里是最需要拍板的一步）。

### 4.6 五个 prompt 的组织方式

沿用现有 `InterviewPrompts` 的风格（`public static final String Xxx_SYSTEM` + `static String xxxUser(...)`），但拆文件避免单类膨胀：

```
prompts/InterviewPrompts.java           角色判定（保留）
prompts/InterviewAnswerPrompts.java     参考回答（ANSWERING）
prompts/InterviewTopicPrompts.java      知识点（SUMMARIZING_TOPICS）
prompts/InterviewGapPrompts.java        薄弱点（FINDING_GAPS）
prompts/InterviewDocumentPrompts.java   文档（COMPOSING_DOC）
```

### 4.7 校验与 critique 反馈

- **校验放在 Handler 里、用纯代码**（可单测、零 token）：`List<String> validate(StageOutcome)` 返回问题列表；
- 有问题 → 组装成 `【上一次输出的问题】...` 追加到下一次 user 消息（deep research 的 critique 用法），重试 1 次；
- 是否引入 **LLM 自评 critique**：v1 **不引入**（多一次调用、且自评不会比确定性校验更可靠），留配置 `interview.pipeline.critique-mode=deterministic|llm`。

### 4.8 长文本与截断（必须处理）

现有 `LlmJsonSupport` 的截断检测（`finishReason=length` → `LlmOutputTruncatedException`）只覆盖 JSON 场景，Markdown 需要新增能力：

```
interview/llm/LlmTextSupport.java
  - String generate(systemPrompt, userMessage, ChatOptions)      // 非流式，返回纯文本
  - 同样的 finishReason 截断检测 + token/耗时日志
  - 分片生成：generateSections(systemPrompt, userMessage, List<Section>, options)
    → 每个 Section 一次调用，任一节截断只重试该节，最后按固定顺序拼接
  - 完成性弱校验：末节标题存在、最后一行非空且不以连接词结尾
```

分片边界属于**协议**（固定章节），不属于内容，因此用代码控制不算"用代码拼格式"。

---

## 5. 第 4 步的关键决策：Markdown 归谁生成

### 5.1 硬约束

`spring.ai.openai.chat.options.maxTokens = 10000`（`application.yml:19`）。
20 分钟面试：问答清单约 100 条（Q 50 + A 100 字）≈ 15k 字，加逐字对话（500 句）≈ 30k 字。**一次调用绝无可能输出完整四节报告**，必然截断。

### 5.2 三个选项

| 方案 | 做法 | 优点 | 代价 |
|---|---|---|---|
| **D1（推荐）** | LLM 出**章节正文**（概览/知识点/待补充/参考回答的措辞），代码把"逐字 Q/A + 参考回答 + 完整对话"按固定骨架装进最终 Markdown | 不超 maxTokens；逐字原文零改写（AC-12 保住）；格式定义只剩 LLM prompt + 一处 assembler | 最后一公里的"装配"仍是代码（但不再有 `InterviewReportRenderer` 那套排版逻辑） |
| D2 | 整篇 Markdown 全交给 LLM，代码只存 MinIO | 完全满足"不用代码拼格式" | 必然分 3~4 次调用再拼接（等于又回到代码装配）；逐字原文可能被改写；token ≈ 3 倍；AC-12 失效 |
| D3 | D1 + 让 LLM 决定章节顺序与标题，代码只提供数据块 | 更灵活 | 章节顺序不稳定，前端/下载的观感会跳 |

### 5.3 推荐 D1 的具体形态

```java
// LLM 输出（结构化，一次调用，规模可控）
record DocumentDraft(String title, String overview,          // overview 只做客观概述，不含评分
                     String topicSection, String gapSection) { }

// 代码装配（唯一一处，ReportDocumentAssembler）
"# " + title
+ "\n\n> " + overview
+ "\n\n## 一、问答清单（含参考回答）\n" + 逐字 Q/A + 参考回答 + keyPoints
+ "\n\n## 二、知识点清单\n" + topicSection          // LLM 正文
+ "\n\n## 三、待补充知识点\n" + gapSection            // LLM 正文
+ "\n\n## 四、完整对话\n" + 逐字逐句                  // 纯数据
```

**"完整对话"绝不交给 LLM**（这是我唯一强烈建议不改的一条）：
1. 它是几百行原文，会顶爆 `maxTokens`；
2. 它的价值就是"逐字未改写、可核对"（AC-12），过一遍模型等于自毁；
3. 它是纯数据装配，不存在"排版智能"可借。

如果你坚持 D2，我按 D2 实现，但需要你接受：延迟 +2~4 分钟、token ≈ 3 倍、AC-12 降级为"抽查一致"。

---

## 6. 数据库变更（`sql/interview_step7_pipeline_patch.sql`）

```sql
-- ① 新增：运行态（计划 + 每步状态 + 产物）
ALTER TABLE ai_interview
  ADD COLUMN pipeline_json JSON NULL COMMENT '流水线运行态：每步状态/尝试/耗时/产物',
  ADD COLUMN pipeline_version INT NOT NULL DEFAULT 0 COMMENT '流水线版本，便于将来重跑旧记录';

-- ② 状态取值升级：status 列继续用，取值从 6 个粗状态变为 13 个细状态
UPDATE ai_interview SET status = 'FAILED',
       error_msg = CONCAT(COALESCE(error_msg,''), '（流水线升级前中断，请重试）')
 WHERE status = 'ANALYZING';          -- 唯一没有一一对应关系的旧值（在跑的记录极少）

-- ③ 报告结构升级后需要重新生成（沿用现有约定：/retry 不重新转写）
--    SELECT interview_id, status FROM ai_interview WHERE JSON_EXTRACT(report_json,'$.referenceAnswers') IS NULL;
```

> 若你更希望"零迁移、前端零改动"，可以退而求其次：保留 `status` 粗粒度、新增 `stage` 细粒度列。
> 代价是两个状态字段并存（我在 §3.2 已说明为什么不推荐）。

---

## 7. 接口与前端变更

| 项 | 现在 | 改后 |
|---|---|---|
| `GET /{id}/status` | `status` / `stageDescription` / 句数… | 增加 `stage`（细粒度 key）与 `phase`；`stageDescription` 改为按 stage 出文案 |
| SSE `progress` | `stage`: analyzing/extracting/reporting | 新增 `answering` / `topics` / `gaps` / `composing` / `storing`（协议字段不变） |
| SSE `snapshot` | 已与 status 对齐 | 增加 `stage`/`phase`（前端步骤列表据此恢复） |
| `GET /{id}/report` | 结构化四部分 | 增加 `referenceAnswers`（并入 qaList 项）与 `documentMarkdown` |
| `GET /{id}/report/download` | 后端代码渲染的 Markdown | LLM 生成的 Markdown（含代码装配的逐字附录） |
| 前端报告渲染 | `reportToMarkdown`（再拼一遍） | 直接渲染 `documentMarkdown`（`marked` + `DOMPurify` + `highlight.js` 已在用） |
| 前端步骤列表 | 6 个 key | +4 个 key（`STEP_ICON` 补图标） |

---

## 8. 成本 / 延迟 / 稳定性

| 维度 | 现在 | 改后（串行） | 改后（order1 并行 + 关思考） |
|---|---|---|---|
| LLM 调用次数 | 2 | 5 | 5（并发峰值 2） |
| 预计总时长（20 分钟录音） | 3~5 分钟 | 7~12 分钟 | 5~8 分钟 |
| 截断风险 | 集中在 1 次调用 | 分散到 4 步，可按步重试/分片 | 同左 |
| 单步失败影响 | 整条重跑 | 只重跑该步 | 同左 |
| 输出一致性 | 温度 0.1 + seed，实测一致 | 结构化步骤仍一致；**文档措辞不保证逐字一致** | 同左 |
| token 成本 | 约 2 次（含 8k 思考） | 约 4~5 次，但每步输入更聚焦、输出更短 | `enable_thinking=false` 可省 30%~40% |

压时间的手段（按性价比排序）：
1. `interview.pipeline.steps.*.extra-body.enable_thinking=false`（已有机制，实测省 37% completion）；
2. order 1 两步并行；
3. 步骤 ①②③ 用更快的非推理模型（`interview.pipeline.steps.*.model`），只让 ④ 用强模型；
4. 分片策略只在真截断时触发。

---

## 9. 验收标准变更（必须提前确认）

| 编号 | 现有验收 | 改后 |
|---|---|---|
| AC-12 | 完整对话逐句覆盖、逐字未改写 | **不变**（D1 方案保住；D2 方案会降级） |
| AC-13 | 同一 interviewId 连跑 3 次结果一致 | **结构化产物一致**（角色/清单/参考回答条数/知识点/薄弱点）；**文档措辞允许差异**（LLM 生成，温度 0.1 + seed 只能"接近一致"） |
| 新增 AC-14 | — | 每条真提问都有参考回答，且 qaId 与问答清单一一对应 |
| 新增 AC-15 | — | 任一知识点/薄弱点都能追溯到真实 qaId；无原文依据的要点被剔除并记日志 |
| 新增 AC-16 | — | 页面展示的 Markdown 与 MinIO 下载文件**逐字节一致**（同一份文本） |
| 新增 AC-17 | — | 中途 kill 进程后重启，`@Scheduled` 能从失败步续跑完成（不重跑已完成的 LLM 步） |

---

## 10. 实施批次（每批独立可上线、可回归）

| 批次 | 内容 | 验收 |
|---|---|---|
| **1** | 状态机骨架：`InterviewStage` + `PipelineState` + `InterviewPipeline` + Handler 接口/注册表 + DB patch；把**现有** role/qa/report 三步包成 handler（**行为与现在完全一致**） | 同一条录音，改前改后报告 JSON 一致；kill 重启可续跑 |
| **2** | `ANSWERING` 步（prompt + 校验 + `pipeline_json` 产物 + 报告/前端展示参考答案） | AC-14；噪声条目折叠 |
| **3** | `SUMMARIZING_TOPICS` 改为读参考答案（替换旧的知识点调用） | AC-15 |
| **4** | `FINDING_GAPS` 独立成步 | 与旧实现对比，条目数/质量不降 |
| **5** | `COMPOSING_DOC` + `LlmTextSupport` + MinIO + 前端改渲染 Markdown + 删除 `InterviewReportRenderer` / `reportToMarkdown` | AC-16 |
| **6** | 续跑调度、降级开关、每步模型/超时配置；docs 与 AC 同步 | AC-13/17 + 真机回归 |

工作量估计：**4~5 人日**（批次 1 约 1 天，2~4 各半天，5 约 1 天，6 约 0.5 天，另留 0.5~1 天真机回归）。

---

## 11. 风险与对策

| 风险 | 对策 |
|---|---|
| 延迟从 3~5 分钟涨到 7~12 分钟，用户以为卡死 | 分步 progress 帧（`answering 1/4` 这类文案）+ 前端按步显示；`enable_thinking=false` |
| token 成本上升 | 每步配模型；参考答案只对"真提问"生成；order1 并行 |
| 文档措辞不稳定（AC-13） | 已明确放宽口径；结构化产物仍是确定的 |
| 一次改太多引入回归 | 批次 1 先做"零行为变化"的骨架，后续每批只加一个 LLM 步 |
| 旧记录 `report_json` 结构升级 | 沿用现有约定：`sql` 查出后逐条 `POST /retry`（不重新转写）；`pipeline_version` 记录版本 |
| 并行步导致 token 峰值/限流 | 并行开关默认关闭；`Semaphore` 限制同场并发（借用 deep research 的 `toolSemaphore` 思路，但作用域是单场面试） |

---

## 12. 待确认的 5 个点

1. **第 4 步走 D1（LLM 出章节正文 + 代码装配逐字数据，推荐）还是 D2（整篇全 LLM）？**
2. **前端报告改为直接渲染后端 Markdown、删掉 `reportToMarkdown`（推荐）？** 还是保留前端结构化渲染、只把下载文件换成 LLM Markdown？
3. **状态枚举合并为 `InterviewStage`（推荐，含迁移 SQL + 前端映射改动）还是保守新增 `stage` 列（零迁移、两个状态字段）？**
4. **接受 AC-13 放宽 + 延迟上升到 5~12 分钟？** 是否允许把步骤 ①②③ 换成更快的模型（关口思考）？
5. **是否同意"完整对话"附录始终由代码装配**（不交给 LLM）？
