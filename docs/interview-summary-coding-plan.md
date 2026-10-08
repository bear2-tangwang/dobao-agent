# 面试总结功能 · 编码方案与分步开发计划（v1.0）

> ⚠️ **2026-10 二轮重构后报告结构已变更**：由「问答清单 / 知识点清单 / 待补充知识点 / 完整对话」
> 改为「问答清单 / 参考回答 / 面试总结」。本文件里描述报告 DTO 与四节结构的部分**仅作历史记录**，
> 现行口径见 `docs/interview-step9-report-restructure.md`。

> 配套需求文档：`docs/interview-summary-final-spec.md`（已冻结）
> 原则：**每一步都能独立验证、都能留下可用产物**；不追求一次写对，追求每步可见。
>
> **修订记录**
>
> - **v1.2（步骤 1 实施后回写）**
>   1. **不引入 `MetaObjectHandler` 自动填充时间戳** —— 时间交给数据库维护，更新点显式赋值。
>      理由与实测证据见步骤 1 的"时间戳由谁维护"小节。受影响处：§4 步骤 1 文件清单。
>   2. 步骤 2 处理逻辑补上 `update_time` 的赋值要求与"下载结果必须显式 UTF-8 解码"的坑。
>
> - **v1.1（步骤 1 实施后回写）**
>   1. **删除 `ai_interview_segment` 表** —— 句子级数据 100% 可由 `ai_interview.transcript_json`
>      推导（唯一独有的 `speaker_role` 也能由主表 `interviewer_speaker_id` 推出），不单独落库。
>      理由见步骤 1 建表 SQL 下方的注。受影响处：§4 步骤 1（文件清单 + 建表 SQL）、
>      §4 步骤 2（归一化不再落库）、§4 步骤 4（时间戳回填改为内存句子列表）。
>   2. 按实施结果修正 §3.2 配置块（`base-url` 必须带 `/api/v1`、`api-key` 需有回退值、
>      补 `minio.public-base-url` 与两个上限配置）。
>   3. 按实施结果修正步骤 1 的建表 SQL 与文件清单（`audio_hash`、时间列默认值、新增的两个类）。
>   4. 步骤 2 幂等验收口径由 `asr_task_id` 改为 `audio_hash`（`asr_task_id` 在提交前为 NULL，拦不住重复上传）。
>
> - **v1.3（步骤 3/4 实施后回写，2026-10）** 三个结论，都只改"展示口径"与"前端交互"，
>   **不动数据层**：
>   1. **报告正文不再显示问答的时间戳**，问答清单压成 `**Q001** 问题` + `**A** 摘要` 两行。
>      `report_json` 里的毫秒值一律保留（它们由句子序号查表回填，**不花 LLM token**）。
>      受影响处：§4 步骤 4 的验收口径、步骤 5 的报告展示。理由与代价见
>      `docs/interview-step4-report.md` §8。
>   2. **保留独立的说话人角色判定**，"不判角色直接输出问答"的方案经量化后否决
>      （只省 2%~5% token 与约 6 秒，却把一次判定变成 65 次独立猜测）。受影响处：步骤 3 不变，
>      新增 §4 步骤 3 的"决策回访"说明。理由见 `docs/interview-step3-role-and-qa.md` §8。
>   3. **步骤 5 的前端交互由"纯轮询"改为"SSE 流式进度 + 轮询兜底"**，
>      接口表随之修订（见下方步骤 5）。这是本节改动最大的地方。
>
> - **v1.4（步骤 5 实施后回写，2026-10）**
>   1. 步骤 5 前端**已实现**，实施记录见 `docs/interview-step5-frontend.md`
>      （新增 3 个文件 + 修改 7 个文件，`vue-tsc` 0 error、`vite build` 通过）。
>   2. 记录两个实施中的关键点：① 进度必须**引用同一个 reactive session 对象**，
>      中间做浅拷贝会导致流式期间界面不刷新；② 面试流用的是**具名 SSE 事件**，
>      与对话接口的 `{"type": ...}` 载荷不同，需单独解析。
>   3. **后端 `GET /interview/{id}/stream` 仍未实现** —— 前端已做降级（自动轮询），
>      所以功能可用但没有实时进度。这是接下来唯一的必做项。
>      → **v1.7 已补齐**，见下方 v1.7 条目。
>
> - **v1.5（真实音频回归后回写，2026-10）** 用 `interView.m4a`（5.5 分钟 / 71 句 / 2 人）
>   反复回归后发现两个问题，都已修：
>   1. **报告缺"完整对话"**：这段录音里 71 句只有 2 句是提问，其余是岗位介绍、流程说明、
>      改约协调。只给问答清单时报告只剩 1~2 条对话，**其余内容完全不可见**。
>      处理：报告追加第四部分「完整对话」（逐句原文、不改写、不过模型），
>      需求文档同步改为四部分并新增 AC-12/AC-13。受影响处：§4 步骤 4 的生成流程 / DTO / 验收，
>      以及步骤 5 的报告渲染。理由见 `docs/interview-step4-report.md` §9。
>   2. **同一音频结果不稳定**：连续 5 次重跑同一个 `interviewId`，问答对数量在 1↔2 之间跳、
>      待补充知识点在 0↔1 之间跳。根因是问答抽取与知识点归纳**沿用了全局 `temperature: 0.7`**
>      （那是给对话/PPT 写的）。处理：`LlmJsonSupport` 的抽取调用改为**按次传温度 0.1**，
>      不动全局配置；同时**跳过"没有提问候选"的分块**，这段音频 6 块 → 只需 2 次调用。
>      受影响处：§4 步骤 3 的抽取实现与耗时预估。理由见 `docs/interview-step3-role-and-qa.md` §9。
>   3. **修掉"状态查询反复解析大 JSON"**：20 分钟音频回归时发现
>      `GET /interview/{id}/status` 每次请求都重新反序列化整份 `transcript_json`
>      （只为拿句子数/说话人数），轮询期间每 3~5 秒一次并刷一行 INFO 日志，看着像卡死。
>      处理：新增 `sentence_count` / `speaker_count` 两列（转写落库时写入），
>      `status()` 直接读列不再碰 JSON；`TranscriptNormalizer` 日志降为 DEBUG。
>      **需要执行** `dobao-backend/sql/interview_step5_transcript_meta_patch.sql`。
>      受影响处：§4 步骤 1 的建表 SQL、步骤 2 的落库字段。理由见
>      `docs/interview-step3-role-and-qa.md` §9.6。
>   4. **分块抽取改为并发**：实测单次分块抽取耗时 **220 秒**（`completionTokens=8064`
>      而输出 JSON 只有 2224 字符 —— 绝大部分是模型"思考"），20 分钟音频约 17 块，
>      串行就是 30 分钟以上。各块互不依赖，因此改为并发（新增
>      `interview.report.extraction-concurrency`，默认 4），并给每次调用打上
>      耗时/token 日志。受影响处：§4 步骤 3 的抽取实现与耗时预估。理由见
>      `docs/interview-step3-role-and-qa.md` §9.7 / §9.8。
>   5. **用厂商参数关掉推理模型的"思考"**：通过 Spring AI 的 `extraBody` 透传
>      `enable_thinking: false`，实测同一 prompt 下 completion 从 8064 → 5075 token（省 37%）、
>      耗时从 &gt;150s → 120s。参数名/是否生效因服务商而异，故做成配置
>      （`interview.report.extra-body` + `extra-body-keys` 白名单，避免影响对话/PPT），
>      另预留 `extraction-model` 用于换非推理模型。受影响处：步骤 3 的抽取实现。理由见
>      `docs/interview-step3-role-and-qa.md` §9.9。
>
> - **v1.6（2026-10，问答清单去 LLM 化重构）** —— **§4 步骤 3 的"分块抽取"整节作废**，
>   最终形态如下（本节下方步骤 3 / 步骤 4 已按此重写）：
>   1. **删除分块并发抽取**：不再"每块一次 LLM 调用"。问答清单改为把 ASR 转写的**句子列表
>      直接格式化**出来（`QaListBuilder`，纯内存遍历、毫秒级、零 token）。
>      配对口径 = **按对话轮次全量成对**：面试官的一段连续发言是一条 Q，
>      紧随其后的候选人连续发言是对应的 A；两侧都是**逐字原文**，不做摘要、不改写。
>   2. **角色判定保留**：那一次采样 LLM 调用继续用，判定结果把 `Speaker0/Speaker1`
>      **一次性替换**成"面试官/候选人"，问答清单与完整对话附录共用同一份映射。
>   3. **知识点 / 待补充知识点只喂问答清单**：全流程只剩 **2 次 LLM 调用**
>      （角色判定 ×1 + 知识点归纳 ×1）；报告仍是四部分
>      （问答清单 / 知识点清单 / 待补充知识点 / 完整对话）。
>   4. **随时间戳口径一起反转**：不再有"模型给句子序号 → 系统查表回填"，毫秒值直接取
>      问答两侧真实句子的 `begin_ms`/`end_ms`（**误差恒为 0**）；正因为不再经过模型，
>      问答清单正文**恢复展示时间戳**（`[01:28]`），用于定位回录音。
>   5. **删除的类与配置**：`InterviewQaExtractor`、`QaPair`（换成 `QaItem`）、
>      `QaPairDraft`、`QaChunkResult`；配置 `max-questions-per-chunk`、
>      `extraction-concurrency`（`extraction-model` 更名为 `report.model`，
>      它现在服务的是知识点归纳那一次调用）。`AnalysisResult` / `InterviewReport` 里的
>      `failedChunks` / `skippedChunks` / `totalChunks` 一并删除。
>   6. **已落库的旧报告需要重新生成**：`report_json` 是 JSON 列，结构变了不会自动迁移 ——
>      库里历史记录仍带 `answerSummary` / `failedChunks`，前端解析会缺字段。
>      对旧记录执行一次 `POST /interview/{id}/retry` 即可（**不会重新转写、不重复计费**；
>      v1.8 起 `/analyze` 已并入 `/retry`）。
>      这条要进回归清单。
>   7. 理由与取舍见 `docs/interview-step3-role-and-qa.md` §10 与
>      `docs/interview-step4-report.md` §10。
>
> - **v1.7（2026-10，补上后端实时进度流）**
>   1. **`GET /interview/{id}/stream` 已实现**：新增 `interview/progress/InterviewProgressHub`
>      （`Map<interviewId, Sinks.Many<String>>`，只推事实、不存事实），
>      返回 `Flux<String>` 且 `produces = text/event-stream`，与 `/agent/chat/stream` 同风格。
>   2. **打点挂在已有的状态写入口上**：`updateStatus`（状态跳变与失败）、
>      `persistTranscript`（转写完成 + 句数/人数）、`analyze`（问答清单格式化完，带 `qaCount`）、
>      `publishReport`（调模型前推 `reporting`、落库后推 `complete` 并**带上 `report_json`**）。
>      **没有新增任何 LLM 调用，也没有改动异步线程模型**。
>   3. **v1.6 之后没有分块了**，所以事件里不再有"第 3/65 块"这类计数；
>      进度粒度是 7 个点（见 `docs/interview-step5b-progress-stream.md` §3）。
>   4. 细节与验证见 `docs/interview-step5b-progress-stream.md`（前端**无需改动**）。
>   5. 生效前提：**重启后端**；`interview.stream.heartbeat-ms` 可调（默认 15 秒心跳）。
>
> - **v1.8（2026-10，接口收口 + 冗余清理）**
>   1. **接口收口**：删除 `POST /interview/{id}/analyze`（与 `/retry` 完全重叠 ——
>      有文字稿时 `/retry` 就是"只重跑分析"，重新生成旧报告也走它）；
>      删除 `GET /interview/{id}/transcript`（代码里零调用方，文字稿随报告第四部分返回）。
>      收口后的接口集合：`upload` / `stream` / `status` / `report` / `report/download` / `retry`。
>   2. **`snapshot` 帧补字段**：新增 `stage` / `text` / `sentenceCount` / `speakerCount`，
>      与 `GET /status` 对齐 —— 断线重连与刷新页面不必再补一次状态查询。
>   3. **轮询明确降级为兜底**：只在"流抛错 / 流断在非终态"时启动，间隔由 4~12 秒放宽到 15~45 秒。
>      `GET /status` **保留**：SSE 是单实例内存广播，且**无订阅者时丢弃事件、断线不重放**，
>      多实例部署与长连接不可靠的场景仍然只能靠它兜底。
>   4. **状态文案收敛为一份**：`InterviewStatus.description()` 同时供 SSE 的 `text` 与
>      `/status` 的 `stageDescription` 使用；前端删掉本地 `STATUS_TEXT` 表
>      （此前后端两份 + 前端一份，措辞已经漂移）。
>   5. **清理冗余**：删除 `AnalysisResult` / `InterviewQaExtractor` / `QaPair` / `QaPairDraft` /
>      `QaChunkResult` 五个类；命名改为 `InterviewStatusVO` / `InterviewUploadVO` / `SentenceDTO`；
>      "很短且只有一个调用点"的私有方法与未使用成员（`publishError`、`activeStreams`、
>      `tryNormalize`、`hasAnswer`、`AsrTaskState#pending`、`AsrTranscriptResult.Usage` 等）一并删除；
>      前端清掉分块时代的 `chunkCurrent` / `chunkTotal` 死状态。

---

## 1. 编码总原则

1. **风险前置**：整个方案唯一可能推翻的技术点是"说话人能否分清"。所以第 2 步就用真实音频做探针，**不等到主流程写完才发现地基不稳**。
2. **不破坏现有功能**：`FileManageService.uploadFile` 已被对话/PPT 等功能复用，音频分支必须**新增**而非改写既有逻辑。
3. **外部调用可单独验证**：百炼转写分「提交 / 轮询 / 下载结果」三段，每段都能用 curl 或单元测试单独跑通，再串起来。
4. **数据先落库再处理**：任务状态第一时间落库，任何环节崩溃都能续跑、能重试。
5. **每步都有验证清单**：见每步的「验收」小节，不通过不进下一步。

---

## 2. 现有代码复用清单（先看清楚哪些不用写）

| 已有能力 | 类 | 本功能怎么用 |
|---|---|---|
| 文件上传 + 落库 + 类型路由 | `FileManageService` / `FileInfoServiceImpl` / `AiFileInfoMapper` | 音频走**新分支**，不入 `extractedText` |
| 对象存储 | `MinioService`（`uploadFile` / `downloadFile` / 公共读策略） | 存音频 + 存报告 Markdown |
| 文件模型 | `FileInfo`（含 `isImage()` 等判定方法） | 加 `isAudio()` |
| LLM 调用 | `ChatModel`（qwen3.8） | 说话人角色判定 ×1、知识点归纳 ×1（**v1.6：问答清单不再调模型**） |
| 数据库 | MySQL + MyBatis-Plus（实体 `@Mapper` 注解式） | 新增 `ai_interview` 表 |
| 配置 | `application.yml` | 加百炼 ASR、MinIO 公网地址 |

**结论**：需要从零写的只有 **ASR 客户端、两个取 URL 的实现、报告生成链路、异步编排**。存储、LLM、数据库全部复用。

---

## 3. 关键技术决策（编码前定死）

### 3.1 用 `RestClient`，不引 DashScope SDK

项目是 Spring Boot 3.5.6 + `spring-boot-starter-web`，`RestClient` 开箱可用。不引 DashScope Java SDK（会带 gson 等额外依赖，且域名/WorkspaceId 需另行配置）。

### 3.2 配置外置，Key 不再明文

```yaml
# application.yml 新增
interview:
  asr:
    # 必须带 /api/v1：步骤 0 实测可用的地址就是这一形式，
    # 缺了它 /uploads、/services/audio/asr/transcription、/tasks/{id} 三个端点全部 404
    base-url: ${DASHSCOPE_ASR_BASE_URL:https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com/api/v1}
    # 必须有回退值：写成 ${DASHSCOPE_API_KEY} 时，若环境变量未设置会导致整个应用启动失败。
    # 这里回退到项目现有的百炼 Key（与 spring.ai.openai 同源）。
    api-key: ${DASHSCOPE_API_KEY:${spring.ai.openai.api-key}}
    model: qwen-audio-3.1-asr-flash-filetrans
    poll-interval-ms: 5000
    poll-timeout-ms: 1800000               # 30 分钟兜底（轮询由 @Scheduled 扫描驱动，不占线程池）
    temp-upload-enabled: true              # dev=true / prod=false（dev/prod 的唯一切换开关）
    max-audio-bytes: 83886080              # 80MB，与 multipart 限制对齐
    max-audio-duration-ms: 5400000         # 1.5 小时（模型支持 12 小时，此为产品侧约束）
  report:
    seed: 42                               # 知识点归纳调用固定种子（AC-13）
    extra-body:                            # 透传厂商参数，关掉推理模型的"思考"
      enable_thinking: false
    extra-body-keys:
      - enable_thinking
    # model: qwen-plus                     # 归纳换非推理模型（留空=沿用全局）
  retention:
    audio-days: 30

minio:
  public-base-url: ${MINIO_PUBLIC_BASE_URL:}   # prod 必填；dev 留空（走百炼临时上传）
```

> **v1.6**：`max-questions-per-chunk` 与 `extraction-concurrency` 已删除 ——
> 问答清单不再分块、不再逐块调模型，这两个旋钮没有作用对象了。
> `extraction-model` 更名为 `report.model`：它现在服务的是**知识点归纳**那一次调用。

### 3.3 音频 URL 抽象（dev/prod 切换点）

```java
public interface AudioUrlProvider {
    String provide(String objectName);   // 返回百炼可拉取的 URL
}
```

| 实现 | Profile | 说明 |
|---|---|---|
| `DashScopeTempUrlProvider` | dev / 默认 | 取上传凭证 → POST 音频 → 得 `oss://` URL；请求头需带 `X-DashScope-OssResourceResolve: enable` |
| `MinioPublicUrlProvider` | prod | 拼 `minio.public-base-url` + objectName |

用 `@ConditionalOnProperty(name = "interview.asr.temp-upload-enabled", havingValue = "true", matchIfMissing = true)` 控制注入。

### 3.4 会话/自增与主键

沿用现有约定：业务表主键 `id` 用 `IdType.ASSIGN_ID`，业务标识用独立的 `interview_id`（UUID）。

---

## 4. 分步开发计划

> 共 **7 步**。每步产出可用产物 + 明确验收，验收不过不进下一步。

---

### 步骤 0：准备与探针（**半天，最先做，风险最高**）

**目的**：用一段真实面试录音，验证两件事 —— ① 百炼转写能跑通；② **说话人能分清、角色能判对**。

这一步**不写业务代码**，用临时脚本/curl 验证即可。

**具体动作**

1. 准备 1 段真实面试录音（5~10 分钟即可，不用整场）。**双人对话、最好是真实场景。**
2. 申请/确认百炼 API Key 可用（与项目现有 Key 同源）。
3. 按 `docs/interview-summary-final-spec.md` §5.1 走一遍：
   - 取上传凭证 → 上传音频 → 拿 `oss://` URL
   - 提交转写任务（`diarization_enabled=true`, `speaker_count=2`, `language_hints=["zh","en"]`）
   - 轮询 → 下载结果 JSON
4. 人工检查结果 JSON：
   - `sentences[].speaker_id` 是否稳定区分出 **2 个**说话人？
   - 是否出现"整段都是同一个人"的情况？
   - 把文字稿给 LLM，问"哪一位是面试官"，看判定是否可靠。

**产物**：一份验证记录（转写准确率主观评价 + 说话人区分是否可用 + 判定是否可靠）

**验收（分叉点）**

| 结果 | 下一步 |
|---|---|
| ✅ 能分清 2 人，角色可判 | 按本方案继续步骤 1 |
| ⚠️ 能转写，但分不清谁问谁答 | **暂停**，先决策：改用双声道分轨录音 / 加人工修正角色 / 仅输出整段转写不做问答拆分 |
| ❌ 转写本身不可用 | 检查音频格式与热词配置，必要时换模型（`paraformer-v2`） |

> **这一步决定了后面 6 步是否白做，务必先做。**

---

### 步骤 1：基础设施（配置 + 数据库 + 异步能力）

**目的**：把"地基"打好，不碰业务逻辑。

**要改的文件**

| 文件 | 改动 |
|---|---|
| `dobao-backend/src/main/resources/application.yml` | ① `max-file-size` / `max-request-size`：`50MB → 80MB`（第 48、49 行）<br>② 新增 `interview.*` 配置块（见 §3.2）<br>③ 新增 `minio.public-base-url`（§3.3 的 prod 实现需要） |
| `DobaoBackendApplication.java` | 加 `@EnableAsync` + `@EnableScheduling`（前者给转写任务，后者给轮询续跑与 30 天清理） |
| `config/AsyncConfig.java`（新建） | 转写任务专用线程池（核心 4 / 最大 8 / 队列 50，线程名前缀 `interview-asr-`），避免占用 Tomcat 线程；拒绝策略用 `AbortPolicy` 并由调用方兜底 |
| `config/InterviewProperties.java`（新建） | `@ConfigurationProperties(prefix = "interview")` + `@Component` 注册（本项目无 `@ConfigurationPropertiesScan`）。**不要加 `@Validated`**：pom 里没有 `spring-boot-starter-validation`，会启动报错 |
| `entity/AiInterview.java`（新建） | 对应 `ai_interview` 表。**主键必须 `@TableId(type = IdType.ASSIGN_ID)`**：该表 `id` 无 AUTO_INCREMENT，照抄旧实体会报 `Field 'id' doesn't have a default value`。时间列交给数据库维护，**不要引入 `MetaObjectHandler`**（理由见下方"时间戳由谁维护"） |
| `mapper/AiInterviewMapper.java`（新建） | `@Mapper extends BaseMapper<AiInterview>` |
| `entity/record/InterviewStatus.java`（新建） | 状态枚举（`UPLOADED/TRANSCRIBING/TRANSCRIBED/ANALYZING/READY/FAILED`），避免魔法字符串 |
| `entity/record/FileInfo.java` | 新增 `isAudio()` 判定方法（与 `isImage()` 同风格）。**注意**：步骤 2 还要在 `FileManageService` 里同步加私有的 `isAudioFile(fileType)`，否则音频永远走不到新分支 |

**建表 SQL（可直接执行，与本地实际表结构一致）**

```sql
CREATE TABLE ai_interview (
  id BIGINT NOT NULL COMMENT '主键（应用侧雪花ID，无 AUTO_INCREMENT）',
  interview_id VARCHAR(64) NOT NULL COMMENT '业务唯一标识',
  user_id VARCHAR(64) DEFAULT 'default' COMMENT '用户标识，登录后替换',
  file_id VARCHAR(64) DEFAULT NULL COMMENT '关联 ai_file_info.file_id',
  audio_hash CHAR(64) DEFAULT NULL COMMENT '音频内容 SHA-256，幂等去重用',
  file_name VARCHAR(255) DEFAULT NULL,
  file_size BIGINT DEFAULT NULL,
  audio_duration_ms BIGINT DEFAULT NULL COMMENT '音频总时长',
  speech_duration_ms BIGINT DEFAULT NULL COMMENT '语音内容时长（计费口径）',
  audio_url VARCHAR(1024) DEFAULT NULL COMMENT 'MinIO 音频地址',
  asr_task_id VARCHAR(128) DEFAULT NULL COMMENT '百炼任务ID，幂等/续跑用',
  asr_model VARCHAR(64) DEFAULT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'UPLOADED'
    COMMENT 'UPLOADED/TRANSCRIBING/TRANSCRIBED/ANALYZING/READY/FAILED',
  error_msg VARCHAR(1024) DEFAULT NULL,
  interviewer_speaker_id INT DEFAULT NULL COMMENT '判定出的面试官说话人编号',
  transcript_json JSON DEFAULT NULL COMMENT 'ASR 原始结果（句子级数据的唯一来源）',
  transcript_text MEDIUMTEXT DEFAULT NULL COMMENT '归一化纯文本',
  sentence_count INT DEFAULT NULL COMMENT '转写句子数（v1.5 新增，供状态查询免解析 JSON）',
  speaker_count INT DEFAULT NULL COMMENT '识别出的说话人数（v1.5 新增，同上）',
  report_json JSON DEFAULT NULL COMMENT '报告结构化数据',
  report_file_url VARCHAR(1024) DEFAULT NULL COMMENT '报告 Markdown 的 MinIO 地址',
  report_file_name VARCHAR(255) DEFAULT NULL,
  create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_interview_id (interview_id),
  KEY idx_user_time (user_id, create_time),
  KEY idx_audio_hash (audio_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试记录';
```

> **为什么没有 `ai_interview_segment`（转写稿片段表）**
>
> 初期设计里它存「一句话一行」的转写明细（`speaker_id` / `begin_ms` / `end_ms` / `text`），
> 但这些数据 **100% 已存在于 `transcript_json` 里**，解析一次即可在内存中得到同样的句子列表。
> 它唯一独有的 `speaker_role` 也能推导出来：
>
> ```
> speaker_role = (speaker_id == interviewer_speaker_id) ? "interviewer" : "candidate"
> ```
>
> （主表已存 `interviewer_speaker_id`，而规格把 `speaker_count` 固定为 2，所以一个字段就够。）
>
> 实测体量：5.5 分钟音频 72 句，原始 JSON 79,842 字节，其中 **81% 是逐字级 `words[]`（功能未使用）**；
> 句子级投影只有 15,023 字节，折算 40 分钟约 **0.1MB**。也就是说这张表既没复制真正占体积的部分，
> 也没有提供任何独立信息 —— 属于「对最没有独立价值的一层做规范化」。
>
> **结论**：句子级数据不落库，`transcript_json` 即文字稿来源，步骤 3/4 在内存里解析它。
> 若将来确实需要按结构化数据检索（例如「我以前被问过哪些题」「按知识点找历史面试」），
> 应该新增的是**问答对表**（question / answer摘要 / 原话 / topics / 时间戳），而不是句子表 ——
> 问答对才是产品真正的产物，值得落库。这一点列入步骤 6 的可选增强。
>
> 已经建过这张表的库，执行 `dobao-backend/sql/interview_step1_patch.sql` 可清掉。

> 注意：`interview_id` 上没有外键约束（与项目现有表风格一致）。

> **时间戳由谁维护（`create_time` / `update_time`）**
>
> **不用 `MetaObjectHandler` 做自动填充**，时间交给数据库，但更新时必须显式赋值。
> 这个结论是实测出来的，两个事实叠加才成立：
>
> 1. **MyBatis-Plus 的 `updateById` 是"非空才拼进 SET"**（默认 `FieldStrategy.NOT_NULL`）。
>    从库里查出来的实体带着非空 `update_time`，于是旧值被显式写回。
> 2. **MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在该列被显式赋值时不生效**（实测：显式写旧值 →
>    时间停在原处；语句里完全不出现该列 → 时间自动前进）。
>
> 所以"表上有 `ON UPDATE` 就不用管了"是错的。两种正确写法，**任选其一**：
>
> ```java
> // 写法一：读改写时显式赋值
> record.setStatus(InterviewStatus.TRANSCRIBING.name());
> record.setUpdateTime(LocalDateTime.now());
> interviewMapper.updateById(record);
>
> // 写法二（推荐）：列级更新，让 DB 维护该列
> interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
>         .eq(AiInterview::getInterviewId, interviewId)
>         .set(AiInterview::getStatus, InterviewStatus.TRANSCRIBING.name()));
> // SET 里没有 update_time，ON UPDATE 才会生效
> ```
>
> 状态机有 6 个以上更新点（`TRANSCRIBING` / `TRANSCRIBED` / `ANALYZING` / `READY` / `FAILED` / 重试），
> 全部集中在 `InterviewService` 一个类里 —— 这是可以靠代码纪律管住的前提；如果哪天更新点散到多个类，
> 再考虑加回自动填充。
>
> 另外两点实测行为：① 插入时无需赋值（列有 `DEFAULT CURRENT_TIMESTAMP`，字段为 null 时 MP 会省略该列），
> 代价是插入后 Java 对象里这两个字段是 null，要回给前端就重新查一次；
> ② 若某次更新的值和当前值完全相同（行未发生实际变化），MySQL 不会刷新该列，别误判为 bug。

**验收**
- [ ] 项目能正常启动，现有对话/PPT 功能不受影响
- [ ] `ai_interview` 表建好，实体能正常读写（写个临时 Controller 或单元测试验证；
      重点确认插入后 `id` 是应用侧生成的雪花 ID，而不是报 `Field 'id' doesn't have a default value`）
- [ ] 上传 60MB 文件不再被 50MB 限制拦截（80MB 上限仍应拦住 85MB+ 的文件）
- [ ] 异步线程池生效（日志里能看到独立线程名，前缀 `interview-asr-`）

---

### 步骤 2：音频转文字（**核心风险段**）

**目的**：打通「上传音频 → 拿到带说话人和时间戳的文字稿」。这是整个功能的地基。

**新建类**

| 类 | 职责 |
|---|---|
| `interview/audio/AudioUrlProvider`（接口） | 取音频 URL |
| `interview/audio/DashScopeTempUrlProvider` | dev：走百炼临时上传 |
| `interview/audio/MinioPublicUrlProvider` | prod：拼 MinIO 公网地址 |
| `interview/asr/DashScopeAsrClient` | 提交任务 / 轮询状态 / 下载并解析结果 |
| `interview/asr/dto/AsrSubmitResponse` 等 | 对接百炼返回结构的 DTO |
| `interview/TranscriptNormalizer` | ASR 结果 → 内存句子列表（**不落库**，见下方注） |
| `service/InterviewService` | 上传落库 + 触发异步转写 |
| `controller/InterviewController` | 上传、查状态、取文字稿 |

**核心方法签名**

```java
public interface AudioUrlProvider {
    String provide(String objectName);
}

public class DashScopeAsrClient {
    /** 提交转写任务，返回 taskId */
    String submit(String audioUrl, List<String> vocabulary);

    /** 轮询直到终态，返回结果 JSON 的下载地址 */
    String pollUntilDone(String taskId);

    /** 下载并返回结果 JSON 原文 */
    String downloadResult(String resultUrl);
}

public class TranscriptNormalizer {
    /**
     * ASR 结果 JSON → 句子列表（含 speaker_id / begin_ms / end_ms / text / sentence_id）。
     * 纯内存转换，不落库：原始 JSON 已存在 ai_interview.transcript_json，
     * 本次结果只供当次步骤 3/4 使用，需要时再解析一次即可。
     */
    List<SentenceDto> normalize(String asrJson);
}
```

> **归一化结果不落库**（v1.1 变更）：原设计的 `ai_interview_segment` 表已删除，
> 理由见步骤 1 建表 SQL 下方的注。步骤 3 的问答清单格式化、步骤 4 的完整对话附录都基于这里的
> 内存句子列表；`transcript_json` 是句子级数据的唯一持久化来源。
>
> **下载结果必须显式按 UTF-8 解码**（步骤 0 踩过的坑）：`DashScopeAsrClient.downloadResult`
> 不能用 `RestClient.get().body(String.class)` —— 响应头不带 `charset` 时可能按 ISO-8859-1 解码，
> 中文会变成乱码（步骤 0 的 `interView-asr-raw.json` 就是这么坏掉的，后来靠离线修复才得到
> `-fixed` 版本）。正确做法是取 `byte[]` 后 `new String(bytes, StandardCharsets.UTF_8)`。

**修改 `FileManageService`**

在 `uploadFile` 的类型分支中新增音频分支：

```java
} else if (isAudioFile(fileType)) {
    // 音频：不解析文本，交给 InterviewService 异步转写
    log.info("音频文件上传完成，等待转写: fileId={}", fileId);
}
```

> 关键：**音频分支只落 MinIO + 建记录，不做任何耗时操作**，保证上传接口 3 秒内返回。

**处理逻辑（异步，`InterviewService`）**

```
1. 更新状态 TRANSCRIBING
2. audioUrl = audioUrlProvider.provide(objectName)
3. taskId = asrClient.submit(audioUrl, vocabulary)   // 落库 asr_task_id
4. resultUrl = asrClient.pollUntilDone(taskId)
5. asrJson = asrClient.downloadResult(resultUrl)      // 立刻下载，链接 24h 失效
6. 落库 transcriptJson（句子级数据的唯一持久化来源）
7. sentences = normalizer.normalize(asrJson)         // 仅内存解析，不落库
8. 更新状态 TRANSCRIBED（含 audio_duration_ms / speech_duration_ms）
任何异常 → 状态 FAILED + error_msg
```

> **每处状态更新都要处理 `update_time`**（本项目不用 `MetaObjectHandler` 自动填充，原因见步骤 1 的
> "时间戳由谁维护"）：改状态时要么 `record.setUpdateTime(LocalDateTime.now())` 后再 `updateById`，
> 要么直接用 `LambdaUpdateWrapper` 做列级更新（推荐，让 DB 的 `ON UPDATE` 生效）。
> 漏了不会报错，只是 `update_time` 会停在旧值。
>
> 状态流转建议封装成 `InterviewService` 里的一个私有方法（如 `updateStatus(interviewId, status, errorMsg)`），
> 把这条规则收进一个地方，避免 6 个调用点各写一遍。

**接口**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/interview/upload` | `multipart` 上传音频，返回 `interviewId` + 状态 |
| GET | `/interview/{id}/status` | 返回状态与阶段 |
| GET | ~~`/interview/{id}/transcript`~~ | v1.8 起移除：文字稿随报告返回（报告第四部分「完整对话」） |

**验收**
- [ ] 上传真实面试录音，接口 3 秒内返回
- [ ] 轮询期间状态正确流转，前端能查到 `TRANSCRIBING`
- [ ] 转写完成后，文字稿**能区分 2 个说话人**、时间戳合理
- [ ] 40 分钟音频转写耗时在 1~4 分钟区间
- [ ] 人为断开网络/传坏文件，状态变 `FAILED` 且有可读错误信息
- [ ] 重复上传同一音频，能被幂等识别（上传时算 `audio_hash`，命中则提示并复用已有记录；
      **不能只靠 `asr_task_id`** —— 提交转写之前它是 NULL，拦不住重复上传）

---

### 步骤 3：说话人角色判定 + 问答清单格式化（**v1.6 重写：不再有分块抽取**）

**目的**：把「句子列表」变成「谁问的、谁答的」问答清单 —— 其中**只有角色判定用 LLM**。

**新建类**

| 类 | 职责 |
|---|---|
| `interview/SpeakerRoleResolver` | 用 LLM 判定哪个 `speaker_id` 是面试官（**全场唯一一次采样调用**） |
| `interview/QaListBuilder` | **把句子列表直接格式化**成问答清单（零 LLM、零分块、纯内存遍历） |
| `prompts/InterviewPrompts` | 所有 prompt 模板集中管理（只剩角色判定 + 知识点归纳两个） |

**① 角色判定策略（保留，不变）**

不要只看说话时长（面试官也可能长篇介绍团队）。用 LLM 判断，输入是**采样后的对话片段**
（开头 + 中段 + 尾段各 25 句）：

```
以下是面试录音转写稿片段。请判断哪一位说话人是面试官、哪一位是求职者（候选人）。
判断依据：谁在提问、谁在等待对方回答；谁在介绍公司/岗位；谁在回答技术问题。
只输出 JSON：{"interviewerSpeakerId": 0, "reason": "..."}
```

判定结果落 `ai_interview.interviewer_speaker_id`，然后被**一次性用于全量替换标签**：
`Speaker0/Speaker1` → 面试官/候选人（问答清单与完整对话附录共用同一份映射）。

**② 问答清单格式化策略（v1.6 新增，替代原"分块抽取"）**

```
句子列表
  ↓ mergeBySpeaker()   同一人连续的几句话合并成一个"发言块"
  ↓ 按轮次成对          面试官发言块 = Q，紧随其后的候选人发言块 = A
  ↓ 全局编号            Q001、Q002…（知识点清单用 `出自：Q002` 引用）
QaItem[]（Q 与 A 均为逐字原文，时间戳取自真实句子）
```

规则细节：

| 情形 | 处理 |
|---|---|
| 面试官发言块 → 候选人发言块 | 一条问答（Q = 面试官原文，A = 候选人原文） |
| 面试官发言块 → 面试官发言块 | 并入同一个问题，不产出"没有回答的中间条目" |
| 候选人先开口（开场） | 独立条目，问题侧填占位"（开场发言）" |
| 结尾面试官说完、候选人没回 | 保留成一条，回答侧填"（未作答）"，**内容不丢** |

> **取舍说明（为什么不做"这是不是真提问"的判断）**
>
> 一段真实的 5.5 分钟录音实测只有 **2 句**是提问，其余是岗位介绍、流程说明、改约协调与告别。
> 一旦要判断"哪句才算提问"，就只有两条路：写规则（转写稿常丢标点，必漏）或调模型
> （正是本次要拆掉的东西）。因此采用**全量成对**：宁可清单长一点、条目里有些不是技术问答，
> 也不漏内容；同时报告第四部分保留逐句原文，用户能自己核对。
> 收益是**可复现、零 token、毫秒级完成**；成本是清单里会混入非提问条目。

**DTO**

```java
public record QaItem(
    String qaId,
    String question,         // 面试官该轮发言原文（多句已合并）
    long questionBeginMs,    // 首句起始毫秒（来自真实句子，误差恒为 0）
    String answer,           // 候选人该轮发言原文（逐字，无摘要、无改写）
    long answerBeginMs,
    long answerEndMs
) {}
```

> 与原 `QaPair` 的差别：删掉 `answerSummary` / `answerQuotes` / `topics`
> —— 它们的前提都是"模型读了这一段并做了摘要与归纳"，现在不再有这一步。
> 知识点统一由步骤 4 的那一次归纳调用产出。

**验收**
- [ ] 角色判定正确（面试官 = 提问方）
- [ ] 问答清单与 `transcript_json` 逐字一致（抽查任意一条 Q/A，能在文字稿里找到原文）
- [ ] `Speaker0/Speaker1` 全部替换为面试官/候选人，报告正文不出现 `SpeakerN`
- [ ] 问答清单的生成**不产生任何 LLM 调用**（日志里只有 1 次角色判定 + 1 次知识点归纳）
- [ ] 40 分钟音频，问答清单格式化耗时 ≤ 1 秒（内存遍历，与音频长度线性相关）
- [ ] 尾部/开头的孤立发言不丢内容（结尾无回答也保留条目）

> **决策回访（v1.3 的结论在 v1.6 依然成立）**：曾提议"干脆不判角色、直接按模型输出的问答渲染"。
> v1.3 否决它的理由是"会把一次判定变成每块独立猜测"；v1.6 之后**连抽取都不需要模型了**，
> 角色判定反而成了整条链路唯一能决定"谁问谁答"的信息来源 —— 更不能去掉。
> 若去掉，问答清单只能退化为"按句输出的双人对话"，无法标出面试官/候选人。
> 详见 `docs/interview-step3-role-and-qa.md` §8 与 §10。

---

### 步骤 4：报告生成 + 存 MinIO + 下载

**目的**：产出最终交付物。

**新建类**

| 类 | 职责 |
|---|---|
| `interview/InterviewReportGenerator` | 由**问答清单**归纳知识点与待补充知识点（Reduce 阶段，全流程唯一一次"读内容做总结"的调用） |
| `interview/InterviewReportRenderer` | 结构化 JSON → Markdown（**v1.6：`**Q001** [01:28] 原话` + `**A** [01:35~01:41] 原话`，恢复时间戳**） |
| `interview/dto/InterviewReport` | 报告 DTO（`json` 字段与文档 §3.2 对应） |

**生成流程**

```
1. 取步骤 3 的问答清单（Q 与 A 均为逐字原文，不再做任何加工）
2. 一次 LLM 调用（输入 = 问答清单 JSON）生成：
   - 知识点清单（按主题归类，标注出自哪条问答）
   - 待补充知识点（为什么补 + 补什么方向）
   注：问答清单为空时跳过这次调用，直接产出空知识点
3. 组装 InterviewReport（问答清单原样带入）
4. 附上完整对话（逐句原话，不改写，不过模型）
5. 渲染成 Markdown（问答清单带时间戳）
6. 上传 MinIO：interview-report-{interviewId}.md
7. 落库 reportJson + reportFileUrl，状态 → READY
```

**报告 DTO 结构**

```java
public record InterviewReport(
    String interviewId,
    long audioDurationMs,
    LocalDateTime generatedAt,
    List<QaItem> qaList,                     // 第一部分：问答清单（v1.6：QaPair → QaItem）
    List<KnowledgeTopic> knowledgeTopics,    // 第二部分：知识点
    List<KnowledgeGap> knowledgeGaps,        // 第三部分：待补充
    List<TranscriptLine> transcript          // 第四部分：完整对话（v1.5 追加）
    // v1.6 删除：failedChunks / skippedChunks（分块已不存在）
) {}

public record KnowledgeTopic(String topic, List<String> points, List<String> relatedQaIds) {}
public record KnowledgeGap(String point, String performance, String why, List<String> directions) {}

/** 完整对话里的一行：角色标签 + 时间戳 + 逐字原话 */
public record TranscriptLine(String role, long beginMs, String text) {}
```

> **v1.6 变更：问答清单与知识点归纳的关系**
>
> 重构前：问答清单 = 分块抽取的结果；知识点 = 对**问答对**再归纳一次。
> 重构后：问答清单 = **格式化产物**（不花钱、不花时间）；知识点 = 对**这份清单**归纳一次。
> 因为清单里已经包含面试官与候选人的**原话**，模型归纳知识点所需的信息并没有减少
> ——40 分钟音频大约 20~60 条清单，输入量远小于整篇转写稿，比"喂全文"更快更省。
>
> 代价：模型看不到"没被配对进清单的内容"（实际很少，因为清单是全量成对的）。
> 如果将来发现有信息缺失，再考虑把转写稿一并喂进去（`interview.report.*` 无需改结构）。

> **v1.5 变更：为什么加第四部分"完整对话"**
>
> 实测一段 5.5 分钟录音（71 句）后发现的真问题：整场只有 **2 句**是提问，
> 其余全是岗位介绍、流程说明、改约协调。只按"被识别成问答"的内容输出，
> 报告里就只剩 1~2 条对话，**其余 69 句在报告里完全不可见** —— 用户会认为系统漏读了录音。
>
> 处理方式：在报告最后追加第四部分，把整场对话**逐句原文**附上（角色 + 时间戳 + 不改写的原话），
> 用途只有一个 —— 让用户核对"问答清单有没有漏"。
> 它与被禁止的"本场概览"不是一回事：概览是模型的**概括**，附录是**逐字事实**。
> 需求文档已同步修订（§2 改为四部分、新增 AC-12/AC-13）。理由详见 `docs/interview-step4-report.md` §9。
>
> **v1.6 之后这一部分依然必要**：问答清单是按轮次成对的（一条 Q 里合并了面试官连着说的多句），
> 完整对话保留逐句边界与每句自己的时间戳，两者互为对照。

**时间戳口径（v1.6 简化）**

不再有"模型给句子序号 → 系统回填"这一步。Q 与 A 的毫秒值**直接取自**问答两侧
真实句子的 `begin_ms` / `end_ms`（Q 取首句 `begin_ms`，A 取首句 `begin_ms` 与末句 `end_ms`），
因此**误差恒为 0**，不存在"模型把序号写错"的风险。

> **v1.6 变更**：正因为毫秒值不再需要"不采信"，Markdown 正文**恢复展示时间戳**
> （`**Q001** [01:28] …` / `**A** [01:35~01:41] …`），用于把清单定位回录音。
> v1.3 那轮"正文去时间戳"的改造随之作废。理由见 `docs/interview-step4-report.md` §10。

**接口**

| 方法 | 路径 |
|---|---|
| GET | `/interview/{id}/report` |
| GET | `/interview/{id}/report/download` |
| POST | `/interview/{id}/retry`（有文字稿时即"重新生成报告"，v1.8 起合并了原 `/analyze`） |

> 步骤 5 会再加一个 `GET /interview/{id}/stream`（SSE 进度流），完整接口表见步骤 5。

**验收**
- [ ] 报告包含四个板块，格式符合需求文档 §3.2 模板（第四部分 = 完整对话）
- [ ] 报告文件确实存在 MinIO，下载地址可访问
- [ ] 抽查 10 条知识点，全部在音频中出现过，**无编造**
- [ ] 时间戳与音频实际位置误差 ≤ 3 秒（**v1.6：正文恢复展示时间戳，误差恒为 0**）
- [ ] 报告中不包含任何评分或总评
- [ ] 重新生成报告不重复转写（复用已有文字稿）
- [ ] **完整对话逐句覆盖整场录音**（句子数与转写落库的 `sentence_count` 一致），且**逐字未改写**（AC-12）
- [ ] **同一 `interviewId` 连续重跑 ≥3 次，问答清单条数完全一致**（清单已不经过模型，必然一致），
      待补充条数一致（AC-13）
- [ ] **全流程 LLM 调用次数 = 2**（角色判定 ×1 + 知识点归纳 ×1），日志中不再出现任何
      "分块抽取"字样
- [ ] 库中历史旧结构报告已按 `dobao-backend/sql/interview_step6_qa_format_patch.sql`
      查出并重新生成（旧 `report_json` 缺 `answer` 字段，前端会渲染成 `-`）

---

### 步骤 5：前端页面

**目的**：让用户能用。

> **v1.3 修订**：本步原设计是"上传 + 轮询状态 + 报告页"。实测数据表明这个体验不合格 ——
> 5.5 分钟音频的分析阶段就要 **2 分 20 秒**（8 次 LLM 调用），40 分钟音频端到端 3~8 分钟。
> 而前端本质是个**对话助手**：上传完就干等，用户会以为 AI 卡死。
> 因此改成 **「上传 → SSE 流式进度 → 报告」**，轮询降级为**断线兜底**。
> 结论与权衡见下方"为什么两者都要"。

**改动**

| 文件 | 改动 |
|---|---|
| `dobao-front/src/utils/constants.ts` | `AGENTS` 增加 `{ id: 'interview', name: '面试总结' }`；`SUPPORTED_FILE_TYPES` 增加音频扩展名（mp3/wav/m4a/aac/flac/amr） |
| `dobao-front/src/api/index.ts` | 新增 interview 相关 API 函数 + `getInterviewStreamUrl()`（SSE 地址拼接，与 `getStreamChatUrl()` 同风格） |
| `dobao-front/src/composables/useInterview.ts`（新建） | 上传、**读 SSE 流**、断线回退轮询、`report_json` → Markdown |
| `dobao-front/src/components/InterviewPanel.vue`（新建） | 进度面板：上传卡（含合规勾选）/ 步骤时间线 / 重试 / 载入报告 |
| `dobao-front/src/styles/interview.css`（新建） | 面试面板样式（`style.css` 已 1000+ 行，不再往里堆） |
| `dobao-front/src/components/InputArea.vue` | 面试模式：录音 chip、引导行、录音图标、`accept` 只收音频 |
| `dobao-front/src/components/MessageItem.vue` | `msg.interview` 存在时渲染进度面板 + 报告 + 下载按钮 |
| 新增页面组件 | ~~上传页 / 进度页 / 报告展示页~~ → **改为复用对话气泡**（面板挂在 AI 消息上），只有"文字稿查看页"仍待做 |

**交互流程（v1.3）**

```
① 用户选音频 + 勾选"已获得录音各方同意" → 前端校验（扩展名 + ≤80MB）→ 不合法直接提示，不发请求
② POST /interview/upload              普通请求，1~3 秒返回 interviewId（这一步不能流式：
                                      multipart 落 MinIO 本身就要几百毫秒到 3 秒）
③ 前端立刻开流 GET /interview/{id}/stream
   ← event: snapshot    当前状态（重连时也能接上）
   ← event: progress    转写中 / 转写完成 / 已进入分析 / 正在判定说话人角色 / 正在归纳知识点
   ← event: complete    { reportUrl, downloadUrl, report: {qaList, knowledgeTopics, knowledgeGaps} }
   ← event: error       { message, retryable }
④ 报告在同一个对话气泡里**边收边渲染**（先出标题，再逐条追加问答），完成后附下载按钮
⑤ 流断了（切标签页/锁屏/代理超时）→ 前端自动 GET /interview/{id}/status 回填一次进度，继续等；
   刷新页面后靠 `?interviewId=` 恢复同一场面试的进度
```

**接口（v1.3 修订）**

| 方法 | 路径 | 说明 | 变化 |
|---|---|---|---|
| POST | `/interview/upload` | multipart 上传，返回 `interviewId` + 初始状态 | 不变 |
| GET | `/interview/{id}/stream` | **SSE 事件流**：`snapshot` / `progress` / `complete` / `error` | **新增** |
| GET | `/interview/{id}/status` | 状态与阶段（**断线兜底**、刷新恢复，前端仍要用） | 不变 |
| GET | ~~`/interview/{id}/transcript`~~ | 文字稿（说话人 + 时间戳） | **v1.8 移除**（零调用方，报告第四部分已带全文） |
| GET | `/interview/{id}/report` | 结构化报告（流断过、或直接进报告页时用） | 不变 |
| GET | `/interview/{id}/report/download` | 下载 Markdown | 不变 |
| POST | `/interview/{id}/retry` | 重新分析 / 重试（**v1.8 起合并了 `/analyze`**） | 收口 |

**SSE 事件定义**（前端复用现有读流循环，新增两个事件类型）
前端 `useChat.ts` 已有 "读 `data: ` 行 → `JSON.parse` → 按 `type` 分发" 的循环，这里沿用同一套：

```
event: snapshot   {"status":"TRANSCRIBING","stage":"transcribing",
                   "text":"正在转写（识别说话人与时间戳）…",
                   "sentenceCount":null,"speakerCount":null,"reportReady":false,"reportUrl":null}
event: progress   {"status":"ANALYZING",                 // publishStatus 的帧带 status，publishProgress 的不带
                   "stage":"transcribing"|"transcribed"|"analyzing"|"extracting"|"reporting",
                   "text":"转写完成：520 句 / 2 位说话人，文字稿已就绪",
                   "qaCount":3}                          // 仅"已整理出 N 条问答"那一帧
event: complete   {"status":"READY",
                   "reportUrl":"http://.../interview-report-<id>.md",
                   "report": {/* report_json 原样 */}}
event: error      {"status":"FAILED","stage":"error","message":"分析失败: ..."}
```

> **v1.8**：`snapshot` 与 `GET /interview/{id}/status` 字段对齐（阶段、文案、句数/说话人数都在），
> 断线重连与刷新页面因此不必再补一次状态查询；事件里的 `current`/`total` 已随分块链路一起删除。

要点：**`complete` 直接携带 `report_json` 原样**（DB 里本来就有），前端不必为了展示再发一次
`GET /report`；`downloadUrl` 给下载按钮。这样一轮流就能把"进度 + 报告"全部交付。

**后端实现要点**（**不改现有异步链路**，只加打点）

1. `interview/progress/ProgressHub`（新建）：`Map<interviewId, Sinks.Many<String>>`，
   只推消息、**不存事实**；无订阅者时丢弃事件（`tryEmitNext`），**绝不能阻塞后台任务**，
   并在 `complete`/`error` 后从 Map 移除（否则长跑实例里这个 Map 会一直涨）。
2. 在 `InterviewTaskService` 的状态推进点上打点：`submitTranscription` / `persistTranscript` /
   `analyze`（角色判定后、问答清单格式化后、知识点归纳完成后）/ `updateStatus` 的失败分支。
   > **v1.6**：`analyze` 里已经没有"每块抽取完成"这种粒度了 —— 问答清单格式化是毫秒级的
   > 内存操作，不需要进度事件；阶段从"转写 → 判角色 → 整理清单 → 归纳知识点 → 完成"
   > 一共 4 个可上报的点。
3. `GET /interview/{id}/stream` 返回 `Flux<String>`（`produces = "text/event-stream;charset=UTF-8"`），
   与 `AgentController` 的 `/chat/stream`、`/pptx/stream`、`/deep/stream` 完全同风格；
   **先发 `snapshot`**，再订阅 `ProgressHub`。

**为什么流式和轮询都要留**（这是本步最容易搞错的地方）

| | 后台任务（已有，不动） | SSE 流（新增） |
|---|---|---|
| 职责 | **事实来源**：状态机 + 落库 + `@Scheduled` 轮询续跑 + 30 分钟超时兜底 | **事件通知**：把已发生的事实时推给页面 |
| 连接断了会怎样 | 无影响，任务照跑 | 前端回退到 `GET /status`，进度不丢 |
| 最长生命周期 | 30 分钟以上 | 取决于浏览器/代理，**任何长连接都撑不住 40 分钟** |

所以"AI 阻塞等待、结果流式推出"这个设想**可行，前提是流只做通知**：
结果的真正落点始终是 DB + MinIO。反过来说，**只做流不做轮询是不行的** ——
用户切个标签页回来就永远看不到进度了；**只做轮询也是不够的** —— 2~8 分钟的空白期体验很差。

**验收**
- [x] 从上传到看到报告，全流程在页面上走通（**代码链路已实现**；真机联调待后端 stream 端点）
- [x] 上传 100MB 文件被前端拦截，有明确提示（`validateAudio`：扩展名 + 80MB，上传前拦截）
- [x] 进度**逐条**推进（转写 → 转写完成(句数/人数) → 分析 → 整理清单(qaCount) → 生成报告 → 完成），不是静止转圈
      —— 后端 `GET /interview/{id}/stream` 已于 2026-10 补齐（见 `docs/interview-step5b-progress-stream.md`），
      帧格式用一次性探针（`probe-output/SseProbe.java`）逐项验证；真机联调需重启后端
- [ ] 报告下载按钮可用
- [ ] **DevTools 里确认是 1 条 `text/event-stream` 长连接，而不是每 2 秒一次轮询**
- [ ] **手动断网/切标签页再回来，进度能自动恢复**（走 `/status` 兜底）
- [x] 现有三个智能体（对话/PPT/深度研究）功能不受影响（改动均为新增分支）
- [x] TypeScript 类型检查 0 error、`vite build` 通过（234 modules，新样式与新逻辑均已进产物）

> **实施记录见 `docs/interview-step5-frontend.md`**，其中记录了本轮唯一一个真正的坑：
> 进度对象若在中间层被浅拷贝，流式期间界面不会刷新（会退化成"等几分钟一次性出现"）。
>
> **后端 `GET /interview/{id}/stream` 已于 2026-10 补齐**（实施记录：`docs/interview-step5b-progress-stream.md`）。
> 在此之前前端会自动轮询 `GET /status`，所以功能一直可用、只是没有实时进度；
> 补齐后前端**不需要任何改动**，只是不再显示"已切换为定时查询"。

**成本与风险**：后端约 1 个新类 + 6 个打点（**不加任何 LLM 调用**），前端复用现有读流逻辑。
风险两项：① SSE 长连接占用 Tomcat 线程，需确认并发上传数在预期内；
② 断线期间的事件会丢，靠 `snapshot` 兜底 —— 这也解释了为什么事实必须落库。

---

### 步骤 6：健壮性补齐

**目的**：把"能跑"变成"能长期跑"。

| 项 | 内容 |
|---|---|
| 30 天音频清理 | `@Scheduled` 定时任务，清理超期音频文件（**保留文字稿与报告**） |
| 删除接口 | `DELETE /interview/{id}`，同时清 MySQL 记录 + MinIO 音频 + MinIO 报告文件 |
| 幂等 | 同一音频（内容 Hash）重复提交，提示并复用已有结果 |
| 超时兜底 | 轮询超过 30 分钟强制置 `FAILED`，避免任务永久挂起 |
| 成本记录 | 记录 `speech_duration_ms` 与各阶段 Token 消耗 |
| 合规 | 上传页勾选"已获得录音各方同意"，未勾选不可提交 |
| 日志 | 每阶段打 `interviewId` + `taskId`，便于排障 |

**验收**
- [ ] 删一场面试后，MinIO 里的音频和报告文件都没了
- [ ] 手工改库把时间改成 31 天前，定时任务能清掉音频、留下报告
- [ ] 转写任务卡死时，30 分钟后自动置失败且可重试

---

### 步骤 7：端到端验收

拿**真实的完整面试录音**（30~40 分钟）跑一遍，逐条对照需求文档 §11 的 12 条验收标准。

**重点核对**
- AC-2 说话人区分与角色判定
- AC-5 知识点不编造
- AC-7 端到端 ≤ 8 分钟
- AC-10 报告可下载
- AC-12 时间戳误差 ≤ 3 秒

---

## 5. 开发顺序与依赖关系

```
步骤 0（探针）── 决定后面是否继续
      ↓
步骤 1（基础设施）
      ↓
步骤 2（音频转文字）── 核心，最耗时
      ↓
步骤 3（角色判定 + 问答清单格式化）
      ↓
步骤 4（报告生成 + MinIO）
      ↓
步骤 5（前端）── 可与步骤 3/4 并行
      ↓
步骤 6（健壮性）
      ↓
步骤 7（端到端验收）
```

- 步骤 2 结束后，**文字稿已经可看**，可以先给用户看效果，提前反馈
- 步骤 5 的前端框架部分（入口、上传、进度）可以在步骤 2 完成后就开始搭
- 步骤 3 和 4 是串行的，但 prompt 调优会和步骤 4 反复迭代

---

## 6. 风险与预案

| 风险 | 出现时机 | 预案 |
|---|---|---|
| **说话人分不清** | 步骤 0 | 本方案最大风险。预案见步骤 0 的分叉表 |
| 百炼临时上传接口不通 | 步骤 2 | 退化为本地起 Nginx 静态目录暴露音频，或用内网穿透工具 |
| 转写结果里 speaker_id 抖动（同一人被打成多人） | 步骤 2 | `speaker_count=2` 强约束；后处理按时间就近合并碎片 |
| **问答清单混入非提问条目**（v1.6 的新增代价） | 步骤 3 | 采用"按轮次全量成对"必然如此（岗位介绍、改约也会成条）。预案：报告第四部分保留逐句原文供核对；若确实需要过滤，再在 `QaListBuilder` 里加一层**可选**的疑问句标记（不删条目，只打标） |
| 长转写稿超上下文 | 步骤 3/4 | 已不适用：问答清单不再喂整篇转写稿，只把**问答清单本身**交给模型归纳知识点 |
| 报告出现编造的知识点 | 步骤 4 | prompt 强约束 + 生成后校验知识点是否在原文中出现，不通过的剔除 |
| 模型返回非法 JSON | 步骤 3/4 | 统一用 `BeanOutputConverter` + `finishReason=length` 截断检测；整场仅剩 2 次调用，失败即整场 FAILED，可 `POST /retry` 原地重跑（不重新转写） |
| **库里旧结构的 report_json**（v1.6 迁移） | 上线后 | 旧记录缺 `answer` 字段，前端会把回答渲染成 `-`。按 `dobao-backend/sql/interview_step6_qa_format_patch.sql` 查出并逐条重新生成 |

---

## 7. 工作量预估

| 步骤 | 预估 | 说明 |
|---|---|---|
| 步骤 0 探针 | 0.5 天 | 关键是拿到真实录音 |
| 步骤 1 基础设施 | 0.5~1 天 | 建表 + 配置 + 异步 |
| 步骤 2 音频转文字 | 2~3 天 | 核心工作量，含调试 |
| 步骤 3 角色判定 + 问答清单格式化 | 2~3 天 → **1 天**（v1.6：抽取代码整体删除，角色判定沿用） | prompt 调优占大头 |
| 步骤 4 报告生成 | 1~2 天 | 输入改为问答清单 |
| 步骤 5 前端 | 2~3 天 | 可与 3/4 并行 |
| 步骤 6 健壮性 | 1 天 | |
| 步骤 7 端到端验收 | 0.5~1 天 | |
| **合计** | **约 8~12 天**（v1.6 后减少约 2 天） | 不含步骤 0 验证不通过后的方案调整 |
| **回归项（v1.6 必做）** | 0.5 天 | 旧 `report_json` 逐条重新生成 + 确认全流程只剩 2 次 LLM 调用 |

---

## 8. 建议的推进方式

1. **先做步骤 0**，把探针结果发我看，再决定是否进入步骤 1。
2. 步骤 1、2 连续做完，产出「能看到文字稿」的可演示版本，先确认转写质量。
3. 步骤 3、4 是质量攻坚段，建议每完成一个 prompt 迭代就用同一段音频回归一次，避免改好一个场景破坏另一个。
4. 步骤 5 前端可以在步骤 2 完成后并行启动。
5. 每一步做完都对照该步的「验收」清单，不通过不进入下一步。
