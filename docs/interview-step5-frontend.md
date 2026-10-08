# 步骤 5 实施记录：前端页面（面试总结）

> 对应编码方案 §4 步骤 5。后端接口（上传 / 状态 / 文字稿 / 报告 / 重试）在步骤 2~4 已完成；
> 本轮按 v1.3 方案实现「上传 → SSE 流式进度 → 报告」的前端交互。

---

## 1. 最终达到的效果

```
用户在输入框工具栏选中「面试总结」
  → 点录音图标选音频（前端先校验扩展名 + ≤80MB，不合法直接提示，不发请求）
  → 勾选「已获得录音各方同意」
  → 点「开始总结」
  → 气泡里逐条出现进度：已接收录音 → 正在提交转写 → 转写完成（520 句 / 2 位说话人）
                      → 正在分析：第 3/65 块 → 报告已生成
  → 报告正文直接渲染在同一个气泡里（问答清单 / 知识点清单 / 待补充知识点）
  → 气泡底部出现「下载面试总结报告（Markdown）」
```

进度是**实时逐条推进**的，不是转圈等到最后；断流会自动降级为定时查询并明确告知用户。

---

## 2. 新增文件（4 个）

| 文件 | 职责 |
|---|---|
| `dobao-front/src/composables/useInterview.ts` | 上传、读 SSE 流、兜底轮询、报告 → Markdown、前端校验 |
| `dobao-front/src/components/InterviewPanel.vue` | 进度面板（上传卡 / 步骤时间线 / 重试 / 载入报告） |
| `dobao-front/src/styles/interview.css` | 面试相关样式（单独一份，不往 `style.css` 里继续堆） |
| 本文件 | 实施记录 |

## 3. 修改文件（6 个）

| 文件 | 改动 |
|---|---|
| `src/utils/constants.ts` | `AGENTS` 增加 `interview`；新增 `AUDIO_EXTENSIONS` / `MAX_AUDIO_BYTES` / `INTERVIEW_STREAM_EVENTS` / `INTERVIEW_STATUS` |
| `src/types/index.ts` | 新增 `InterviewSession` / `InterviewStep` / `InterviewReport` / `InterviewQaPair` / `InterviewKnowledgeTopic` / `InterviewKnowledgeGap` / `InterviewStatusVo` / `InterviewUploadVo`；`Message` 增加可选的 `interview` 字段 |
| `src/api/index.ts` | 新增 `uploadInterviewAudio` / `streamInterview` / `getInterviewStreamUrl` / `getInterviewStatus` / `getInterviewReport` / `getInterviewDownloadUrl` / `retryInterview` |
| `src/composables/useChat.ts` | 面试模式分支：选文件、开始总结、停止、重试、载入报告；`canSend` 与 `removeFile` 适配；`selectAgent` 切换时清理 |
| `src/components/InputArea.vue` | 面试模式：录音 chip、引导行替代输入框、录音图标、`accept` 只收音频、发送按钮换图标 |
| `src/components/MessageItem.vue` | `msg.interview` 存在时渲染 `InterviewPanel` + 报告 Markdown + 下载按钮 |
| `src/App.vue` | 串联上面这些 props / events |
| `src/main.ts` | 引入 `styles/interview.css` |

---

## 4. 关键设计决策

| 决策 | 理由 |
|---|---|
| **进度面板挂在 AI 消息上**（`Message.interview`），而不是自建一套会话列表 | 复用现有气泡排版、复制按钮、滚动逻辑；面试和对话/PPT 走同一个消息栈，用户体感一致 |
| **上传是普通请求，不是流式** | 后端 `POST /interview/upload` 本身就是"落 MinIO + 建记录"后同步返回（1~3 秒）。真正的长耗时在转写与分析，那部分才需要流 |
| **流只做通知，事实来源仍是 DB** | 任何长连接都撑不住 40 分钟（切标签页/锁屏/代理超时都会断）。流断掉时前端自动切 `GET /status` 每 3~10 秒轮询，任务在后台照跑 |
| **步骤按语义 key 去重** | 流与兜底轮询可能报同一件事（都报"转写完成"），用 `InterviewStepKey` 保证同一阶段只有一条，只更新文案 |
| **前端先校验再上传** | 扩展名 + ≤80MB，与后端 `InterviewService.validate` 同口径，避免用户等完一次 80MB 上传才被告知格式不对 |
| **合规勾选放在面板里，未勾选不可提交** | 需求文档 §6 合规项：面试录音属敏感个人信息，必须确认已获得各方同意 |
| **报告 Markdown 由前端从 `report_json` 渲染** | 流里的 `complete` 事件直接带结构化报告，不必再请求一次；渲染口径与后端 `InterviewReportRenderer` 对齐（同样不显示时间戳） |
| **`useInterview` 的 session 由调用方持有并传入（每条消息各自一份）** | 见下方"踩过的坑"，这是本轮最容易出错的地方 |
| **样式单独一份 `styles/interview.css`** | `style.css` 已 1000+ 行且是整份深色主题，面试面板是独立 UI 块，混在一起只会更难维护 |

---

## 5. 踩过的坑：session 对象不能拷贝

**现象**：第一版把 `useInterview` 内部的 `session` 用 `{ ...session.value }` 拷进 `message.interview`，
结果**流式进度完全不刷新**，界面要等整场跑完（几分钟）才一次性出现 —— 等于把流式退化成同步等待。

**原因**：`pushStep()` 改的是 composable 内部的 `session.value.steps`，
而消息气泡渲染的是那份**拷贝**。拷贝之后两者再无关系，Vue 自然收不到更新。

**修法（初版）**：整个应用只保留**一个** session 对象（在 `useChat` 里 `ref` 出来，传给 `useInterview`），
`message.interview` 直接引用它本身；每场新面试用 `Object.assign(session.value, emptySession())`
**就地重置字段**，而不是换一个新对象（换对象同样会切断引用）。

**修法（2026-10 修订，多场面试）**：只保留一个 session 会踩第二个坑 ——
一个会话里做第二场面试时，`Object.assign` 会把**第一场的报告就地清空**，
第一场的气泡只剩一个空壳（表现为"上一场的 AI 气泡总结直接消失，但用户气泡还在"）。
现在改成 **每条面试消息各自持有一个 session**（`Message.interview`），
`useInterview` 的所有方法都接收目标 session、只就地改字段、从不替换对象：
既保住了第一条的响应式前提，又让多场面试互不影响。

两个实现细节：

- `message.interview` 用 `reactive()` 包一层。裸对象塞进响应式数组后，
  拿原始引用改字段**不会**触发重渲染（Vue 只在通过代理读的时候收集依赖）；
  显式做成代理后，谁引用它都是同一份响应式对象。
- 会话里同时存在多条面试消息，所以"谁在跑、谁在等提交"必须按消息区分：
  `useChat` 用 `pendingInterviewMsgId` / `runningInterviewMsgId` 记 id（不记对象，
  避免 reactive 数组里 raw / proxy 不相等），`App.vue` 再把
  `isInterviewProcessing(msg)` / `isInterviewBusy(msg)` 逐条传下去。

**教训**：只要进度是"一点点推到 UI 上"的，就绝不能在中间层做浅拷贝；
但"全局只留一份"同样不是正解 —— 该按消息隔离的状态就得隔离，隔离时保持**引用不变、就地改字段**即可。

---

## 5.1 气泡顺序：AI 面板必须排在它回答的用户气泡之后

**现象**：点"开始总结"后，AI 面板出现在**用户气泡前面**（截图里就是 AI 空气泡在上、两条用户气泡在下）。

**原因**：上传卡是"选完文件"就建好的（那时还没有用户气泡），用户气泡是点"开始总结"时才补上的，
于是列表顺序是 `[AI 面板, 用户气泡]`；第二场面试又复用了第一场那条消息，
第一场的报告被就地重置，视觉上就成了"上一场的 AI 气泡消失了"。

**修法**：

1. `sendMessage` 的面试分支先 push 用户气泡，再调 `takeInterviewMessage(chat)`
   把等待中的面板**挪到列表末尾**（`splice` + `push`），保证顺序是 `[用户气泡, AI 面板]`。
2. 上传卡只在"待提交"期间存在，且**只复用它自己那一条**；已经开始处理或已经出过报告的消息
   一律不复用（`handleInterviewFile` 只看 `pendingInterviewMsgId` / 未开始的空卡）。
3. 面板消息按 `msg.id` 做 key（原来用数组下标 `index`）：面板被挪动时 Vue 会移动 DOM
   而不是重建 / 错配组件实例。
4. "重新选择"只丢弃**还没开始**的那条上传卡（`discardPendingInterview` 把它从列表里摘掉），
   不再把 `interview.interviewId` 清空 —— 清空会让气泡变成一个只剩复制按钮的空壳。
5. 上传期间（`interview.uploading`）面板显示"正在上传"，不再重复露出一张上传卡：
   那张卡上的"重新选择 / 开始总结"会打断正在跑的场次，也是上一版把气泡清空的入口之一。
6. 用户点"停止"会在这一场上打 `stopped` 标记：面板不再一直挂着"处理中"的转圈，
   abort 引起的异常也不再弹窗；后端任务照旧在跑，用户可点"载入报告内容"取回结果
   （那个按钮的显示条件从"READY 且无报告"放宽到"有 interviewId、还没报告、且没在跑"）。

---

## 6. 与后端的接口契约

前端按下面这份契约实现。**后端的 `GET /interview/{id}/stream` 当时尚未实现**，
因此前端在流不可用时会降级为轮询 —— 这一步是刻意设计的，不是兜底代码。
> 2026-10 补齐：该端点已在 `interview/progress/InterviewProgressHub` 中实现，
> 实施记录见 `docs/interview-step5b-progress-stream.md`（前端无需任何改动）。

```
POST /interview/upload                     → { code, data: { interviewId, status, fileName, fileSize, reused } }
GET  /interview/{id}/stream                → text/event-stream，具名事件：
       event: snapshot                     → { status, reportReady, reportUrl }
       event: progress                     → { stage, text, current, total, qaCount }
                                             stage ∈ transcribing|transcribed|analyzing|extracting|reporting
       event: complete                     → { status:"READY", reportUrl, downloadUrl, qaList..., report:{...} }
       event: error                        → { message, retryable }
GET  /interview/{id}/status                → { status, stage, errorMsg, sentenceCount, speakerCount, reportReady }
GET  /interview/{id}/report                → 结构化报告（report_json 原样）
GET  /interview/{id}/report/download       → Markdown 文件
POST /interview/{id}/retry                 → 触发重试
```

**注意**：面试流用的是**具名事件**（`event: xxx` + `data: {...}`），
与对话接口的 `{"type": "text", "content": ...}` 载荷不同，所以 `useInterview` 里单独写了一套
SSE 解析（支持 `\n\n` 与 `\r\n\r\n` 两种分隔符、多行 `data:` 拼接、`[DONE]` 忽略），
没有复用 `useChat.processStreamData`。

---

## 7. 验证

| 检查项 | 结果 |
|---|---|
| TypeScript 类型检查（`vue-tsc --noEmit -p tsconfig.app.json`） | ✅ 0 error（`tsconfig.node.json` 同样 0 error） |
| 生产构建（`vite build`） | ✅ 234 modules transformed，2.77s，exit 0 |
| 新样式进入产物 | ✅ `dist/assets/index-*.css` 中含 `interview-panel` / `interview-consent` / `interview-step` |
| 新逻辑进入产物 | ✅ `dist/assets/index-*.js` 中含 `面试总结` / `/stream` / `已获得录音` |
| 从上传到看到报告全流程 | ⚠️ **需后端 `GET /interview/{id}/stream` + 一个可用后端实例**，本轮未做真机联调（见 §8） |
| 上传 100MB 文件被前端拦截 | ✅ 代码路径就绪（`validateAudio`），未做真机点击验证 |
| 进度逐条推进、不是静止转圈 | ⚠️ 同上（依赖流或轮询实际返回） |
| 报告下载按钮可用 | ⚠️ 依赖后端 `report/download`（步骤 4 已实测 HTTP 200） |
| 现有三个智能体不受影响 | ✅ 改动均为新增分支（`selectedAgent === 'interview'`），对话/PPT/深度研究链路未触碰 |
| 多场面试：第一条气泡的报告不被第二场清空（2026-10 修订） | ✅ 每条消息各自持有 session；用一次性脚本验证过"就地改字段能触发重渲染、两个 session 互不覆盖"（已删除脚本） |
| 气泡顺序：AI 面板排在用户气泡之后（2026-10 修订） | ✅ 上传卡在 `sendMessage` 里被挪到用户气泡之后；面板消息改用 `msg.id` 做 key |
| 重复验证（2026-10 修订后） | ✅ `vue-tsc --noEmit -p tsconfig.app.json` / `tsconfig.node.json` 均 0 error；`vite build` 234 modules，exit 0 |

> **构建命令必须在沙箱外执行**：Vite 在 Windows 上会调用 `net use` 子进程做 realpath 兜底，
> 受限沙箱下 stdio 管道被禁会报 `spawn EPERM`。这是环境限制，不是代码问题。

---

## 8. 遗留

| 项 | 说明 |
|---|---|
| **后端 SSE 端点未实现** | ~~`GET /interview/{id}/stream` 属后端改动（约 1 个 `ProgressHub` 类 + 6 个打点）。**在此之前前端会自动降级为轮询，功能可用但没有实时进度**。实施要点见 `docs/interview-summary-coding-plan.md` 步骤 5~~ → **2026-10 已补齐**，见 `docs/interview-step5b-progress-stream.md`；前端未改动 |
| **兜底轮询间隔（2026-10 调整）** | 由 3~10 秒放宽到 **4~12 秒**。起因：20 分钟音频回归时，后端每次 `/status` 都要全量解析 `transcript_json`，轮询越密越放大这个浪费。后端已同步修掉"状态查询解析 JSON"（见 `docs/interview-step3-role-and-qa.md` §9.6），间隔放宽只是叠加的优化 |
| 刷新页面恢复进度 | `useInterview` 已提供 `readPersistedId()` / `tryRestore()`，但**尚未接到 `onMounted`**（需要同时恢复历史消息，属于会话持久化范畴） |
| 文字稿查看页 | 本轮未做。原计划用 `GET /interview/{id}/transcript`（**v1.8 已移除**）；文字稿现在随报告的第四部分「完整对话」返回，按说话人分色 + 时间戳渲染即可 |
| 多场面试历史 | 面试记录存在 `ai_interview` 表，但没有像 `ai_session` 那样进入左侧会话列表；目前只在当前会话内可见 |
| 长连接并发 | 每个进行中的面试占一条 SSE 连接（Tomcat 线程）。并发上传数远小于线程池时无影响，需要在上量后观察 |
