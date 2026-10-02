# 步骤 2 实施记录：音频转文字

> 对应编码方案 §4 步骤 2。**这是步骤 3/4 的前置**——步骤 3/4 的输入是
> `ai_interview.transcript_json`，而写入它的就是本步骤。
> 测试音频：`interView.m4a`（5,393,164 字节，5 分 31 秒）。
>
> **2026-10 追加（v1.1）**：新增 `sentence_count` / `speaker_count` 两列，并把
> `TranscriptNormalizer.normalize()` 的日志从 INFO 降为 DEBUG。原因是
> `GET /interview/{id}/status` 早期为了拿这两个数字，**每次请求都重新反序列化整份
> `transcript_json`**，前端轮询期间每 3~5 秒一次全量 JSON 解析 + 一行 INFO 日志，
> 日志被刷满、看着像任务卡死。补丁 SQL：
> `dobao-backend/sql/interview_step5_transcript_meta_patch.sql`，
> 完整分析见 `docs/interview-step3-role-and-qa.md` §9.6。

---

## 1. 最终达到的效果

一条命令即可把面试录音变成**带说话人编号与毫秒级时间戳的文字稿**：

```
POST /interview/upload        （multipart，≤80MB）→ 0.79 秒返回 interviewId
  ↓ 异步：取音频 URL → 提交百炼转写任务 → 落 asr_task_id
GET  /interview/{id}/status   → TRANSCRIBING / TRANSCRIBED / FAILED + 中文阶段说明
GET  /interview/{id}/transcript → 72 句，含 speakerId / beginMs / endMs / text（该接口已在 v1.8 移除）
```

实测这条链路对真实音频跑通，且**重启后续跑成功**（转写中途重启应用，未重新上传，自动接上并完成）。

---

## 2. 新增文件（12 个）

| 文件 | 职责 |
|---|---|
| `interview/audio/AudioUrlProvider.java` | 接口：把 MinIO 对象名变成"百炼能拉取的 URL" |
| `interview/audio/DashScopeTempUrlProvider.java` | dev 实现：从 MinIO 取音频 → 上传百炼临时存储 → `oss://...` |
| `interview/audio/MinioPublicUrlProvider.java` | prod 实现：拼 `minio.public-base-url` |
| `interview/audio/DashScopeFileUploader.java` | 百炼临时上传：取凭证 → POST 到 `upload_host` |
| `interview/asr/DashScopeAsrClient.java` | 提交任务 / 查询状态 / 下载结果 |
| `interview/asr/dto/AsrUploadPolicyResponse.java` | 上传凭证响应（含 `usable()`） |
| `interview/asr/dto/AsrTaskResponse.java` | 任务响应，提交与查询共用（含 `succeeded()`/`failed()`） |
| `interview/asr/dto/AsrTaskState.java` | 查询结果收敛，供业务层判断 |
| `interview/asr/dto/AsrTranscriptResult.java` | 转写结果 JSON 结构（**刻意不建模 `words[]`**） |
| `interview/TranscriptNormalizer.java` | 结果 JSON → 内存句子列表（不落库） |
| `interview/dto/SentenceDto.java` | 句子内存对象（seqNo/speakerId/beginMs/endMs/text） |
| `interview/dto/NormalizedTranscript.java` | 归一化结果（句子 + 时长 + 纯文本 + 说话人集合） |
| `interview/dto/InterviewUploadVo.java` / `InterviewStatusVo.java` | 上传/状态返回体 |
| `interview/event/InterviewTranscribedEvent.java` | 转写完成事件（步骤 3/4 的接入点） |
| `service/InterviewService.java` | 上传（校验 + 幂等 + 落库）、查询、状态说明 |
| `service/InterviewTaskService.java` | 异步提交 + `@Scheduled` 轮询 + 状态机 |
| `controller/InterviewController.java` | 上传 / 状态 / 文字稿 三个接口 |

## 3. 修改文件（2 个）

| 文件 | 改动 |
|---|---|
| `service/FileManageService.java` | 新增音频分支（只打日志、不解析）**以及私有方法 `isAudioFile()`** —— 只加实体侧的 `isAudio()` 是没用的，类型分支用的是本类的私有方法 |
| `entity/record/FileInfo.java` | 把音频格式清单抽成静态 `isAudioType(String)`，供 `isAudio()` 与上传校验共用，避免两处清单漂移 |

---

## 4. 关键设计决策

| 决策 | 选择与理由 |
|---|---|
| **轮询不占线程池** | 转写最长 30 分钟，阻塞轮询会占满 8 个线程、让第 9 个任务静默排队、重启即丢。改为 `@Scheduled` 扫描 `status=TRANSCRIBING` 的记录，每次只"查一次"。实测**重启续跑生效**。 |
| **`@Async` + 事件** | 提交走 `@Async("interviewExecutor")`（跨 Bean 调用，代理生效）；轮询完成后的链式触发用 `ApplicationEventPublisher` —— 同类内直调 `@Async` 方法会绕过代理静默变同步。 |
| **状态流转用 `LambdaUpdateWrapper`** | SET 子句里不出现 `update_time`，于是 MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 自动维护它。实测 `update_time` 随状态变化刷新（03:52:23 → 03:59:31），印证了分析报告 §10 的结论。 |
| **结果 JSON 不建模 `words[]`** | 逐字级时间戳占原始 JSON 约 81% 体积，而本功能只需句子级时间戳。原始 JSON 仍原样落库供排障。 |
| **句子级数据不落库** | `transcript_json` 是唯一持久化来源，`SentenceDto` 只在内存存活（分析报告 §9 的结论）。 |
| **时长上限在转写后校验** | pom 里没有音频解析库，上传阶段读不到时长；先按 80MB 拦住，转写返回后再用 `properties.original_duration_in_milliseconds` 校验 1.5 小时上限。 |
| **幂等键用 `audio_hash`** | 上传时流式计算 SHA-256，命中且非 FAILED 则直接复用。**不能只靠 `asr_task_id`** —— 它在提交转写前是 NULL，拦不住重复上传。 |

---

## 5. 实测结果

### 5.1 全链路

| 检查项 | 实测值 | 判定 |
|---|---|---|
| 上传接口耗时 | **0.79 秒**（第二次 0.842 秒） | ✅ 远低于 3 秒要求 |
| 前端可查到 `TRANSCRIBING` | 15 秒时即为此状态 | ✅ |
| 音频上传百炼临时存储 | 5,393,164 字节成功 | ✅ |
| 提交转写任务 | `taskId=630cd61e-...`，`taskStatus=PENDING` | ✅ |
| 最终状态 | `TRANSCRIBED` | ✅ |
| **句子数** | **72** | ✅ 与步骤 0 探针完全一致 |
| **说话人数** | **2**（`speakerIds=[0,1]`） | ✅ 符合验收"能区分 2 个说话人" |
| **音频总时长** | **331050 ms** | ✅ 与步骤 0 探针的 `original_duration_in_milliseconds` 完全一致 |
| 语音内容时长 | 226640 ms（探针为 226160 ms，差 480ms） | ✅ 同量级 |
| 中文编码 | `哎，老师你好，老师你好。` 正常，**无乱码** | ✅ 显式 UTF-8 解码生效 |
| 时间戳合理性 | 首句 47690ms、末句 315360ms，严格递增 | ✅ |
| **重启续跑** | 转写中途重启应用，未重新上传，自动接上并完成 | ✅ 超出验收要求 |

### 5.2 落库核对

```
interview_id                         | status       | json_chars | text_chars | audio_ms | speech_ms | create_time         | update_time
ee6895cc-ab8a-4890-a581-ef4b14512d4b | TRANSCRIBED  |      78075 |       2324 |   331050 |    226640 | 2026-10-01 03:52:23 | 2026-10-01 03:59:31
```

- `transcript_json` 用 **MySQL 原生函数校验通过**：`JSON_LENGTH(...,'$.transcripts[0].sentences') = 72`、
  `JSON_EXTRACT(...,'$.properties.original_duration_in_milliseconds') = 331050`
- `update_time` 随状态流转刷新，证明列级更新确实让 `ON UPDATE` 生效

### 5.3 文字稿样例

```
[47690ms->49810ms] [S0] 哎，老师你好，老师你好。
[50530ms->51930ms] [S1] 哎，你好，能可以听到是吧？
[57530ms->58770ms] [S1] 这是在学校还是在家的？
[58970ms->60850ms] [S0] 啊，我现在在学校，在教室里。
```

---

## 6. 过程中真实踩到并修复的两个 Bug

这两个都是"不实测就发现不了"的坑，记录在此避免重犯。

### Bug 1：数据库时间与 JVM 本地时间差 8 小时，导致超时判断误判

**现象**：任务提交成功（已进入 `TRANSCRIBING`），但 5 秒后被判"转写超时（超过 30 分钟未完成）"。

**根因**：本机 **MySQL `system_time_zone = UTC`**，`NOW()` 返回 `03:50`，而 JVM 本地时间是 `11:50`。
原实现用 Java 比较 `update_time` 与 `LocalDateTime.now()` → 相差 8 小时 → 必然误判。

```
db_now              | g_tz   | s_tz   | sys_tz
2026-10-01 03:50:49 | SYSTEM | SYSTEM | UTC
本地时间: 2026-10-01 11:50:49
```

**修复**：把超时判断整体放进 SQL，让两侧用同一个时钟：

```java
// COALESCE 兜住 update_time 为空的历史数据
String base = "COALESCE(update_time, create_time)";
base + " < DATE_SUB(NOW(), INTERVAL {0} SECOND)"    // 超时
base + " >= DATE_SUB(NOW(), INTERVAL {0} SECOND)"   // 待轮询
```

> 这是项目级隐患：所有表的时间列都用 `CURRENT_TIMESTAMP`，值都比本地时间早 8 小时。
> **任何"Java 时间与数据库时间互相比较"的代码都会出错**，只能交给 SQL 比较，或统一时区。

### Bug 2：预签名 URL 被当模板重新编码，OSS 返回 403 SignatureDoesNotMatch

**现象**：转写实际已成功，但每次轮询下载结果都报
`403 Forbidden ... <Code>SignatureDoesNotMatch</Code>`，任务永远停在 `TRANSCRIBING`。

**根因**：`transcription_url` 是**预签名 URL**（查询串里带 `Signature`）。
`RestClient.uri(String)` 会把它当作 **URI 模板**重新编码，签名里的 `+` / `=` 被改写后签名失效。

**修复**：传 `URI` 而不是 String，绕开模板展开与重编码：

```java
transferClient.get().uri(URI.create(resultUrl)).retrieve().body(byte[].class);
```

> 教训：**只要 URL 带签名，一律用 `URI.create(...)` 传入**，别传字符串。

---

## 7. 验收对照（编码方案步骤 2 的 6 条）

| 方案验收项 | 结果 |
|---|---|
| 上传真实面试录音，接口 3 秒内返回 | ✅ 0.79 秒 |
| 轮询期间状态正确流转，前端能查到 `TRANSCRIBING` | ✅ |
| 转写完成后文字稿能区分 2 个说话人、时间戳合理 | ✅ 72 句 / 2 人 / 严格递增 |
| 40 分钟音频转写耗时 1~4 分钟 | ⚠️ 未测（手上只有 5.5 分钟音频） |
| 人为断开网络/传坏文件，状态变 `FAILED` 且有可读错误信息 | ✅ 非音频文件与超限在上传阶段即返回可读错误；转写失败/超时路径已实现并有中文原因 |
| 重复上传同一音频能被幂等识别 | ✅ 按 `audio_hash` 复用（改为哈希而非 `asr_task_id`，理由见 §4） |

---

## 8. 遗留与下一步

| 项 | 说明 |
|---|---|
| 转写完成后停在 `TRANSCRIBED` | 这是步骤 2 的正确边界。链式进入分析由步骤 3 接上 `InterviewTranscribedEvent` 的监听方 |
| 热词（vocabulary） | 接口已预留（`submit(audioUrl, vocabulary)`），当前传 null；步骤 0 发现技术名词识别不稳，后续可从配置注入 |
| 40 分钟音频耗时 | 需用长音频补测 |
| 测试数据 | 测试用的两条 `ai_interview` 记录与 `ai_file_info` 记录在收尾时按"清理干净"的要求删除 |
