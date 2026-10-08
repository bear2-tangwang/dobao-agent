# 步骤 5·后端补齐：面试进度实时流（SSE）

> ⚠️ **2026-10 修复**：本文档里"帧文本自己拼"的做法**是错的** —— 它导致 Spring MVC 把整段文本
> 当成一条 data 二次包装，前端一条事件都收不到。现在改为输出结构化 `ServerSentEvent`，
> 并补齐了转写/角色判定/报告阶段的进度与"已用 N 秒"心跳。以
> `docs/interview-step10-progress-push-fix.md` 为准。

> 前端（`docs/interview-step5-frontend.md`）当时已经按协议实现了"读 SSE + 断线降级轮询"，
> 但后端 `GET /interview/{id}/stream` 一直没有实现。本文记录补上这一半的过程。

---

## 1. 当时的实际表现

上传后页面上只有"每 4~12 秒刷新一次"的进度，并且一直挂着一条黄色提示：

> 实时进度连接已断开，已切换为定时查询（任务仍在后台运行，进度不会丢失）

实测运行中的后端：

```
GET /interview/{id}/stream  -> HTTP 404   # 路由不存在，前端 streamInterview() 直接抛错
GET /interview/{id}/status  -> HTTP 200   # 于是每 4~12 秒轮询一次
```

`useInterview.runProgress()` 的逻辑是"先试流，流不可用就转轮询"，
所以这条 404 被当成"流断了"，`degraded = true`，提示就出现了 —— **不是网络问题，是端点不存在**。

真正的体验损失不是那条提示，而是**阶段之间的空白**：转写要几分钟、报告归纳又要 1~3 分钟，
轮询间隔内页面只能显示一条静止的"正在分析"，看起来像卡死了。

---

## 2. 改动（4 个文件，不动异步链路）

| 文件 | 改动 |
|---|---|
| `interview/progress/InterviewProgressHub.java`（新建） | 内存广播：`interviewId → Sinks.Many<String>`，产出 `snapshot/progress/complete/error` 四种具名 SSE 事件；含心跳与回收 |
| `controller/InterviewController.java` | 新增 `GET /interview/{id}/stream`，返回 `Flux<String>`（`produces = text/event-stream;charset=UTF-8`） |
| `service/InterviewTaskService.java` | 在既有状态推进点打点（见 §3），**不新增任何 LLM 调用、不改线程模型** |
| `config/InterviewProperties.java` + `application.yml` | 新增 `interview.stream.heartbeat-ms`（默认 15 秒） |

写法与项目既有的三条流式接口（`/agent/chat/stream`、`/pptx/stream`、`/deep/stream`）完全同风格：
Servlet 栈 + `Flux<String>` 返回 + 自己拼 SSE 帧。没有引入 WebFlux 或任何新依赖。

---

## 3. 打点位置（状态机 → 事件）

| 打点位置 | 事件 | 前端看到的那一行 |
|---|---|---|
| `updateStatus(TRANSCRIBING)` | `progress` / `transcribing` | 正在转写（识别说话人与时间戳）… |
| `persistTranscript`（转写落库后） | `progress` / `transcribed` | 转写完成：520 句 / 2 位说话人，文字稿已就绪 |
| `updateStatus(ANALYZING)` | `progress` / `analyzing` | 正在分析（判定说话人角色、整理问答清单、归纳知识点）… |
| `analyze`（问答清单格式化完） | `progress` / `extracting` + `qaCount` | 已整理出 N 条问答（Q/A 均为逐字原文），正在归纳… |
| `publishReport`（调模型之前） | `progress` / `reporting` | 正在生成总结报告（问答清单 / 知识点清单 / 待补充知识点）… |
| `publishReport`（落库之后） | `complete` | 报告已生成（**同帧带 `report_json`**，前端直接渲染正文） |
| 任一 `FAILED`（`updateStatus` / 异常兜底） | `error` | 失败原因 + 面板上的"重试" |
| 每次连接建立 | `snapshot` | 补齐"订阅之前已经发生的事"：`status`/`stage`/`text`/`sentenceCount`/`speakerCount`/`reportReady`/`reportUrl`（**v1.8 起与 `GET /status` 字段对齐**，重连与刷新因此不必再补一次状态查询） |

> **v1.6 备注**：分块抽取在 2026-10 已整体移除，问答清单是毫秒级内存操作，
> 所以没有"第 3/65 块"那种 `current/total` 计数了 —— 进度粒度就是上表这 7 个点。
> 其中 `extracting` / `reporting` 是新增的两点：它们把原本"一次静止 1~3 分钟"的
> 归纳调用拆成两行可见的进度，纯前端展示，不产生额外调用。

`updateStatus` 是**唯一的状态写入口**（`persistTranscript` / `publishReport` 是两处直更），
所以推送全部挂在这三处之后，不必在业务代码里散落 `publish` 调用。

---

## 4. 关键取舍

| 取舍 | 理由 |
|---|---|
| **只推"已经落库的事实"** | 推送一律放在 DB 更新之后。流是通知，不是事实来源 —— 断流、刷新页面后重新连接时的 `snapshot` 一律从库里读，不会出现"流说有、库里没有" |
| **无订阅者就不建 sink** | `publish*` 时 Map 里没有该 id 就直接丢弃（连接时的 `snapshot` 能补齐当前状态）。没有连接的面试不会在内存里留任何东西 |
| **终态主动收尾 + 无订阅者回收** | `complete`/`error` 之后 `tryEmitComplete()` 并从 Map 移除；连接断开时在 `doFinally` 里判断订阅者数量回收。否则长跑实例里这个 Map 会一直涨 |
| **心跳用注释帧** | 转写阶段可能几分钟没有任何跳变，静默的长连接会被代理/浏览器判死。每 15 秒发一帧 `: ping`（SSE 注释），前端解析时直接跳过 |
| **`complete` 带 `report` 但不带 `downloadUrl`** | 报告原文 `report_json` 本来就在库里，带上它前端一次就能渲染正文（省一次 `GET /report`）。而下载地址必须由**前端**用它自己的 `backendUrl` 拼（后端不知道对外可达的域名）—— 下发相对路径会让浏览器的 `<a>` 指向前端域名而 404，所以这一项刻意留空，走前端的兜底拼接 |
| **已经是终态的记录不挂长连接** | `READY` 直接回一帧带报告的 `complete`、`FAILED` 直接回一帧 `error`，连接立刻收尾。刷新页面/重连不会白占一条连接 |
| **单实例内存广播** | 多实例部署时，前端可能连到"没在跑这个任务"的那台，此时只能靠 `snapshot` + 轮询兜底。真要跨实例需要换成 Redis pub/sub 之类的总线 —— 当前是单实例部署，先不引入中间件 |

**为什么流和轮询都留着**：流负责"实时"，轮询负责"可靠"。
前端仍然保留 `GET /status` 兜底（切标签页、锁屏、代理超时都会断流），
两条通道共用同一份库里的状态，所以不会出现两边不一致。

---

## 5. 验证

| 检查项 | 结果 |
|---|---|
| `mvn -o -DskipTests compile` | ✅ exit 0 |
| SSE 帧与前端解析口径逐项对齐 | ✅ `probe-output/SseProbe.java`（一次性探针，用动态代理伪造 mapper，不连库），**24 项断言全部通过** |
| 其中覆盖 | 已 `READY` 只发一帧 `complete`（含 `reportUrl`/报告正文/以空行结尾）且不残留 sink；`FAILED` 只发一帧 `error`（含原因）；记录不存在发可读 `error`；进行中为 `snapshot → progress(transcribing) → progress(extracting+qaCount) → complete` 且终态后 sink 被回收；心跳发 `: ping`，断开后 sink 被回收 |
| CORS | ✅ 既有 `CorsConfig` 对 `/**` 放行 GET/OPTIONS + `allowedHeaders("*")`，跨域直连 8888 可用 |
| 运行中的旧实例行为 | ✅ `curl /interview/xxx/stream` → 404（本文 §1 的结论来源） |
| 真机联调（上传 → 看进度 → 出报告） | ⚠️ **未做**：需要重启后端（见 §6），并依赖运行中的 MySQL/MinIO/百炼 Key，本机未在本次改动中重启服务 |

探针是"协议级"的：它断言的是**字节流长什么样**，而不是"函数返回了什么"，
因为这一层的风险全在"前端能不能解析"上（具名事件、`data:` 单行 JSON、`\n\n` 分隔、注释帧）。

---

## 6. 生效方式与遗留

| 项 | 说明 |
|---|---|
| **需要重启后端才生效** | 运行中的实例是改动前启动的，`/stream` 仍是 404。重启后前端**不需要任何改动**（协议早已实现），黄色降级提示会自动消失 |
| Tomcat 线程占用 | 每个进行中的面试占一条异步连接（Servlet 3 async，不占工作线程，但占连接）。并发上传数远小于 Tomcat 连接上限时无影响 |
| 跨实例进度 | 见 §4"单实例内存广播"。若以后多实例部署，`ProgressHub` 需要换成外部总线；前端的 `snapshot` + 轮询已经能保证"不会看不到结果"，只是不再实时 |
| 断线期间的事件 | 不重放。重连时靠 `snapshot` 拿当前状态（这也是为什么事件里只放"已落库的事实"）。**v1.8**：正因为 snapshot 已经够用，前端的兜底轮询只在"流抛错 / 流断在非终态"时启动，间隔也从 4~12 秒放宽到 15~45 秒 |
