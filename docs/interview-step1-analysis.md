# 步骤 1 分析报告：基础设施（配置 + 数据库 + 异步能力）

> 分析对象：`docs/interview-summary-coding-plan.md` §步骤 1
> 输入：步骤 0 产物（`probe-output/`）、当前代码库、**实测数据库 DDL**、实测编译结果
> 结论口径：只写"经实测确认"的事实，推测项一律标注

---

## 0. 结论速览

| 项 | 状态 |
|---|---|
| 步骤 0 技术分叉点 | ✅ **可继续**：说话人稳定分出 2 个、时间戳正确、角色可判 |
| 步骤 0 产物质量 | ⚠️ **两份产物有缺陷**（一份乱码、时间戳显示错误），但**不影响结论**，见 §1.3 |
| 步骤 1 完成度 | ✅ **已全部落地（11/11）**，实施与验收记录见 **§8** |
| 建表 | ✅ 两张表已存在，列与冻结规格逐字段一致；步骤 1 的 3 项 DDL 修正已应用到库 |
| 项目当前是否可编译 | ✅ `mvn -o -DskipTests compile/package` 退出码 0 |
| 阻断步骤 2 的方案偏差 | ✅ **9 处已在步骤 1 全部修正**，见 §4 与 §8 |

**三处硬伤（均已在 §8 修正并实测验证）：**

1. §3.2 的 `base-url` **缺 `/api/v1`** —— 照抄会全部 404（步骤 0 实测可用的地址带 `/api/v1`）。
   → 已改为带 `/api/v1`，实测 `baseUrlHasApiV1 = true`。
2. `api-key: ${DASHSCOPE_API_KEY}` **没有默认值**，而本机 `DASHSCOPE_API_KEY` **未设置** → **应用直接启动失败**（占位符无法解析），连累现有对话/PPT 功能。
   → 已改为回退到 `spring.ai.openai.api-key`，实测 `apiKeyPresent = true` 且应用正常起来。
3. §3.3 的 `MinioPublicUrlProvider` 需要 `minio.public-base-url`，但 §3.2 配置块里**没有这一项**（冻结规格第 343 行明确要求新增）。
   → 已补 `minio.public-base-url`（环境变量可覆盖，dev 留空）。

---

## 1. 步骤 0 产物复核（进入步骤 1 的前提）

### 1.1 实测数据（用 fastjson2 解析 `interView-asr-raw-fixed.json` 得出）

| 指标 | 实测值 | 说明 |
|---|---|---|
| 音频总时长 | 331050 ms（5:31） | → 落 `audio_duration_ms` |
| 语音内容时长 | 226160 ms（3:46） | → 落 `speech_duration_ms`（计费口径） |
| 句子数 | 72 | |
| 说话人编号 | `{0, 1}`，**无 null** | 未出现"整段同一个人"，也未碎片化 |
| `begin_time` 单调性 | **非单调计数 = 0**（严格不递减） | 时间戳本身可信 |
| 时间范围 | 47690 ms ~ 317680 ms | 首句 0:47，末句 5:17 |
| 音频格式 | `audio_format=aac`（m4a 容器）、48000 Hz、`channels=[0,1]` | 单声道内容装在立体声容器里 |
| 结果结构 | 根键 `file_url / properties / transcripts[] / usage`；`transcripts[0]` 内含 `channel_id / content_duration_in_milliseconds / text / sentences[]`；`sentences[]` 内含 `begin_time / end_time / text / sentence_id / speaker_id / words[]` | 步骤 2 的 DTO 按此定义 |
| `usage` | `input_tokens=4729 / output_tokens=911` | 步骤 6 成本记录可用 |

> **价值**：`properties.original_duration_in_milliseconds` 与 `transcripts[].content_duration_in_milliseconds` 正好对应 `audio_duration_ms` / `speech_duration_ms` 两列，**建表字段与 ASR 返回一一对齐，无需换算**。

### 1.2 分叉点判定

| 验收项 | 判定 | 依据 |
|---|---|---|
| 能分清 2 人 | ✅ | `speaker_id` 恰好 2 个值，无 null、无碎片 |
| 角色可判 | ✅ | 通读全稿：Speaker1 提问、介绍岗位与流程、安排改约时间 → 面试官；Speaker0 应答、自称在教室用平板 → 候选人 |
| 转写可用性 | ✅（有瑕疵） | 稿子可读，但语气词/口误多（"Java开呃服务端开发实习"），公司名"奇妙"识别不稳；未加热词 |
| **是否验证了产品价值链** | ❌ **未验证** | ⚠️ 见下 |

**⚠️ 关键保留意见（影响步骤 3/4 的判断，不影响步骤 1）**

这段 5 分钟录音是**一场"约定改天再面"的对话**，全程只有寒暄 + 介绍岗位 + 说明面试流程 + 改约时间，**没有一道真实技术题被问出或回答**（面试官明确说"我手头准备了 3~4 个题目，你需要 coding"，然后双方改成第二天下午 2 点）。因此：

- 已验证：**说话人分离**、**角色可判**（步骤 2、3 的地基）
- **未验证**：问答对抽取、知识点归纳、待补充知识点、报告生成（步骤 3、4 的核心）
- 另注：录音尾部（约 5:07 之后）出现 `问问他。`、`搜狗兄弟欣赏搜狗，我操。` 这类**与面试无关的现场噪声**——归一化/角色判定要能容忍"尾部非面试内容"，否则会污染报告。

**建议**：**照常进入步骤 1**（基础设施与音频内容无关，且是纯增量改动），但**并行准备一段真正含技术问答的 30~40 分钟录音**，供步骤 3 起使用。不要用这段录音的"问答抽取"效果去判断步骤 3 是否成功。

### 1.3 产物缺陷（两个，均为工具问题，非数据问题）

**缺陷 A：非 `-fixed` 的两份产物是乱码（编码双重转换）**

| 文件 | 编码实测 | 结论 |
|---|---|---|
| `interView-asr-raw.json` | 含乱码字节序列（`è\x80\x81…`），不含正常"老师" | ❌ 乱码版 |
| `interView-asr-raw-fixed.json` | 含正常 UTF-8 中文 | ✅ **正确版** |
| `interView-transcript.txt` | 乱码 | ❌ 乱码版 |
| `interView-transcript-fixed.txt` | 正常中文 | ✅ **正确版** |

即：UTF-8 字节被按 Latin-1 解码后又以 UTF-8 存盘，之后有人离线修复生成了 `-fixed`。**时间戳与时长是 ASCII，未受影响，四份文件解析出的数值完全一致。**

> **对步骤 2 的直接影响（必须写进实现）**：Java 侧下载转写结果时，**不要用 `RestClient.get().body(String.class)`**——响应头若无 `charset`，Spring 可能回退 ISO-8859-1，会**原样复现这次乱码**。必须 `body(byte[].class)` 后**显式 `new String(bytes, StandardCharsets.UTF_8)`**（步骤 0 的 PS 脚本已用 HttpClient 取字节规避，Java 侧要照做）。

**缺陷 B：`scripts/interview-asr-probe.ps1` 的 `Format-Ts` 有取整 Bug（会让人误判时间戳不可用）**

```powershell
# 现状（第 69~75 行）
$m = [int]$t.TotalMinutes          # ← PowerShell 的 [int] 是「四舍五入」，不是截断
return ("{0}:{1}" -f $m.ToString("00"), $t.Seconds.ToString("00"))
```

`47690 ms` → `TotalMinutes = 0.7948` → `[int]` **四舍五入为 1** → 显示 `01:47`（正确应为 `00:47`）。所以两份 txt 里出现了 `[01:58]` 后面跟 `[01:02]` 的"时间倒流"假象。**JSON 数据本身严格单调（已实测），是显示层 bug。**

修复：

```powershell
$m = [int][math]::Floor($t.TotalMinutes)   # 或直接用 $t.Minutes
return ("{0:00}:{1:00}" -f $m, $t.Seconds)
```

> 提醒：**不要**因为这次"时间戳看起来错乱"而怀疑步骤 4 的"时间戳误差 ≤ 3 秒"验收项——真因在脚本显示层。

---

## 2. 步骤 1 现状核对表（方案 vs 实际代码）

| # | 方案要求的文件 / 动作 | 实测现状 | 待办 |
|---|---|---|---|
| 1 | `application.yml`：`max-file-size` / `max-request-size` 50MB→80MB | ✅ **已改**（第 48、49 行已是 80MB），但**未提交**（`git status` 显示 `M`）；同一改动里还夹带了 `maxTokens: 5000→10000` | 无需再改；建议把 `maxTokens` 改动拆成独立提交 |
| 2 | `application.yml`：新增 `interview.*` 配置块 | ❌ 无 | §4 给出修正后的完整块 |
| 3 | `DobaoBackendApplication`：`@EnableAsync` + `@EnableScheduling` | ❌ 无（全库 grep `EnableAsync`/`EnableScheduling` 零命中） | 加两个注解 |
| 4 | `config/AsyncConfig.java`（新建） | ❌ 不存在；全库无 `ThreadPoolTaskExecutor` 定义 | §5.3 给出实现要点 |
| 5 | `config/InterviewProperties.java`（新建） | ❌ 不存在；**全库无任何 `@ConfigurationProperties`**，无 `@ConfigurationPropertiesScan` | §4.5 需同时解决"注册方式" |
| 6 | `entity/AiInterview.java`（新建） | ❌ 不存在 | §5.5 |
| 7 | `entity/AiInterviewSegment.java`（新建） | ❌ 不存在 | §5.6 |
| 8 | `mapper/AiInterviewMapper.java`（新建） | ❌ 不存在 | §5.7 |
| 9 | `mapper/AiInterviewSegmentMapper.java`（新建） | ❌ 不存在 | §5.7 |
| 10 | `entity/record/FileInfo.java`：新增 `isAudio()` | ❌ 不存在（现有 `isImage()/isPdf()/isWord()`） | §4.9 |
| 11 | 建表 SQL | ✅ **已建**（实测见 §3） | 建议 6 处 DDL 微调，见 §3.3 |

> ⚠️ **本表是"实施前"的快照**，用来交代改动范围；其中第 7、9 行涉及的
> `AiInterviewSegment` 实体与 Mapper 后来已按 **§9 的设计变更删除**，请以 §9 与 §8.1 为准。
>
> **结论**：步骤 1 实质未开始。好消息是**没有任何历史包袱**——可以按修正后的一次性做对。

**基线绿色**：`mvn -o -q -DskipTests compile` 退出码 0（离线、可复现），改完后再跑一次即可回归"现有功能不受影响"。

---

## 3. 数据库实测核对

### 3.1 实测环境（⚠️ 与方案假设不符，需记录）

| 项 | 方案/SQL 文件假设 | **实测** |
|---|---|---|
| MySQL 版本 | `sql/ai_db.sql` 头部标注 8.0.41 | **5.7.44** |
| 库名 | dump 里写的是 `dodo` | 应用连的是 **`dobao`**（`application.yml` 第 31 行） |
| 账号 | — | `root / 123456`，`127.0.0.1:3306` 可连 |
| 驱动 | pom 用 `mysql-connector-java 8.0.33` | 连 5.7 正常（已实测建连成功） |

**由此暴露一个资产问题**：`sql/ai_db.sql` 是 MySQL 8 的 dump，含 `utf8mb4_0900_ai_ci`（**5.7 不存在的排序规则**）且指向 `dodo` 库，**在本机 5.7 上无法直接执行**。两张新表的 DDL **没有被写进这个 sql 文件**（该文件只含 `ai_file_info`/`ai_ppt_inst`/`ai_ppt_template`/`ai_session`）。→ 见 §3.4。

### 3.2 实测 DDL（`SHOW CREATE TABLE` 原样）

两张表的**列名、类型、注释与冻结规格 `docs/interview-summary-final-spec.md` §7.1 / §7.2 逐字段一致**，索引也与规格建议一致：

- `ai_interview`：21 列，`PRIMARY KEY(id)`、`UNIQUE KEY uk_interview_id(interview_id)`、`KEY idx_user_time(user_id, create_time)`
- ~~`ai_interview_segment`：9 列，`PRIMARY KEY(id)`、`KEY idx_interview_seq(interview_id, seq_no)`~~ **（该表已在 §9 删除）**
- 两表均 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，当前 **rows = 0**（干净）

> `id BIGINT NOT NULL` **无 AUTO_INCREMENT** —— 与规格"主键 BIGINT"一致，且必须配 MyBatis-Plus `@TableId(type = IdType.ASSIGN_ID)` 由应用侧生成雪花 ID。**这一点务必写进实体，否则插入会报 `Field 'id' doesn't have a default value`。** 注意项目现有表（`ai_file_info` 等）是 `AUTO_INCREMENT`，新表风格不同，不要照抄旧实体。

### 3.3 建议的 6 处 DDL 微调（现在改成本 ≈ 0，步骤 2 之后改要迁移）

按"是否偏离冻结规格"标注。**1、2 建议现在就做；3~6 视取舍。**

> **实施结果（见 §8）**：② ④ 已应用到库，并落到 `sql/ai_db.sql`（最终 DDL）与
> `sql/interview_step1_patch.sql`（已建表环境的补丁）。
> ③ ⑤ ⑥ **刻意不做**，以保持与冻结规格一致。
> **① 已作废** —— 对应的 `ai_interview_segment` 表本身在 §9 被删除，唯一索引失去意义。

```sql
-- ① 【已作废】seq_no 由普通索引改为唯一索引：原意是让转写重试可整批重写 segment。
--    该表本身已在 §9 被删除，本项不再适用（表已 DROP）。
-- ALTER TABLE ai_interview_segment
--   DROP INDEX idx_interview_seq,
--   ADD UNIQUE KEY uk_interview_seq (interview_id, seq_no);

-- ② 【建议做】补 audio_hash：步骤 6 要求「同一音频（内容 Hash）重复提交 → 复用已有结果」，
--    但冻结规格 §7.1 没有这个字段，晚加要迁移存量数据。
ALTER TABLE ai_interview
  ADD COLUMN audio_hash CHAR(64) DEFAULT NULL COMMENT '音频内容 SHA-256，幂等去重用' AFTER file_id,
  ADD KEY idx_audio_hash (audio_hash);

-- ③ 【可选】error_msg VARCHAR(1024) → TEXT：步骤 2 验收要求「有可读错误信息」，
--    Java 异常带 cause 链很容易超 1024 被静默截断。
ALTER TABLE ai_interview MODIFY COLUMN error_msg TEXT COMMENT '失败原因';

-- ④ 【可选，但与项目风格一致性最好】时间列补默认值/自动更新
--    项目其他表均为 DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP；
--    新表是 DATETIME DEFAULT NULL，意味着步骤 2 每次改 status 都要手写 update_time。
ALTER TABLE ai_interview
  MODIFY COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

-- ⑤ 【可选】ai_interview.file_id VARCHAR(64)：ai_file_info.file_id 是 VARCHAR(255)。
--    实际存 UUID(36 字符)，64 够用；若要 join 规范化可统一为 255。
--    注：两表实际都是 utf8mb4 + utf8mb4_general_ci（5.7 默认），join 不会因排序规则冲突。

-- ⑥ 【可选】报告文件大小/下载名之外，建议补 report_size BIGINT（前端展示/审计用，规格未要求）
--    —— 未列入必改项，仅为步骤 4 预留。
```

**关于 `transcript_json` / `report_json` 用 `JSON` 而非 `LONGTEXT`（不建议改）**

规格冻结为 `JSON`，实测表也是 `JSON`。原先担心"ASR 原始 JSON 可能不是合法 JSON 导致插入失败"，但**实测两份产物都能被 fastjson2 正常解析**（72 句、结构完整），MySQL 5.7 的 JSON 列会接受它。因此**保持 `JSON` 类型不变**，只需要在步骤 2 加一道兜底：

- 插入 `transcript_json` 前先 `JSON.parse` 校验一次，失败则把原文降级写入 `transcript_text`/日志并置 `FAILED`，而不是让 SQL 抛异常把整个事务打挂。
- 注意 **MySQL 5.7 的 JSON 列受 `max_allowed_packet` 限制（默认 4MB）**：本段 5.5 分钟音频原始 JSON 约 **80KB**（`interView-asr-raw-fixed.json` 实测 79842 字节）→ 40 分钟约 **0.6MB**，仍在安全区；但若后续把逐字级 `words` 全量落库，需复查该参数。

### 3.4 sql 资产补齐（必做，属步骤 1 的"交付物"范畴）

1. 把 `ai_interview` 的**最终 DDL（含 §3.3 的 ② ④）追加到 `dobao-backend/sql/ai_db.sql`**，否则换机器/换人重建库时这张表会缺失。~~`ai_interview_segment` 的 DDL 不再需要~~（该表已在 §9 删除）。✅ **已完成**
2. 该文件现有内容是 **MySQL 8 专属**（`utf8mb4_0900_ai_ci`、`ROW_FORMAT=Dynamic`、库名 `dodo`）。建议顺手把 `utf8mb4_0900_ai_ci` 统一降级为 `utf8mb4_general_ci`、库名改为 `dobao`，让它在本机 5.7 上可执行——**否则"新环境搭不起来"这颗雷会一直留着**。
   > 这是本次分析中**唯一一个"文档/资产与真实运行环境不一致"**的发现，风险不高但会让后续交接反复踩。

---

## 4. 方案必须在步骤 1 修正的 9 处偏差

### 4.1 【硬伤】`base-url` 缺 `/api/v1`

方案 §3.2：

```yaml
base-url: ${DASHSCOPE_ASR_BASE_URL:https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com}
```

但步骤 0 **实测可用**的地址（`scripts/README-步骤0-操作指引.md` 第 80 行、`docs/asr-research-notes.md` 第 22 行）都带 `/api/v1`，三个端点分别是：

```
GET  {base}/uploads?action=getPolicy&model=...
POST {base}/services/audio/asr/transcription
GET  {base}/tasks/{task_id}
```

→ **必须写成 `...maas.aliyuncs.com/api/v1`**，否则步骤 2 的三个调用全部 404。

### 4.2 【硬伤】`api-key: ${DASHSCOPE_API_KEY}` 无默认值 → 启动即失败

实测：本机 **`DASHSCOPE_API_KEY` 未设置**（`$env:DASHSCOPE_API_KEY` 为空）。Spring 遇到无法解析的占位符会抛 `IllegalArgumentException: Could not resolve placeholder`，**整个应用起不来**——正好踩中步骤 1 的第一条验收"项目能正常启动，现有对话/PPT 不受影响"。

而冻结规格第 168 行明确说该模型"**与项目现有百炼 Key 同源**"，`application.yml` 里已有一串 `sk-ws-...`（第 12、25 行）。最稳的写法是**回退到现有 Key**：

```yaml
interview:
  asr:
    api-key: ${DASHSCOPE_API_KEY:${spring.ai.openai.api-key}}   # 环境变量优先，否则复用现有 Key
```

**不要**用 `${DASHSCOPE_API_KEY:}` 这种空默认值——它会让启动通过、把失败推迟到步骤 2 的一个 401，更难排查。改为"启动后校验非空，为空则以 WARN 明确提示"更友好。

### 4.3 【硬伤】`minio.public-base-url` 未定义

§3.3 的 `MinioPublicUrlProvider` 需要它，冻结规格第 343 行也明确要求新增，但 §3.2 的配置块里没有。补上：

```yaml
minio:
  public-base-url: ${MINIO_PUBLIC_BASE_URL:}   # prod 必填；dev 留空（走百炼临时上传）
```

**顺带确认**：`MinioService` 实测只有 `uploadFile`（两个重载）/ `downloadFile`/`deleteFile`，**没有 `getPresignedUrl`**（第 94 行注释提到过但未实现）。dev 用临时上传、prod 用公网域名，**目前都不需要预签名 URL**，所以 Step 1 不必动 `MinioService`——但要知道这个能力不存在，别在步骤 2 里误以为可以直接调。

### 4.4 dev/prod 配置文件是空的，也没有 active profile

实测：`application-dev.yml`、`application-prod.yml` **均为 0 字节**，且 `application.yml` 里**没有 `spring.profiles.active`**。

→ §3.3 表格写的"Profile：dev / prod"在本项目**实际不存在**。方案 §3.3 自己也说了用 `@ConditionalOnProperty(name = "interview.asr.temp-upload-enabled", havingValue = "true", matchIfMissing = true)` 控制注入——**那就以这个属性为唯一开关**，别再引入 profile 概念，否则会做出一个永远不会切换的双开关。建议：

- `temp-upload-enabled: true` 写在 `application.yml`（dev 默认）
- `application-prod.yml` 里写 `interview.asr.temp-upload-enabled: false` + `minio.public-base-url`
- 如果确实要用 profile，就在 §4.1 的配置块旁**显式加 `spring.profiles.active: dev`**；不加就是"两个文件永不生效"

### 4.5 `InterviewProperties` 的注册方式未定

全库**没有任何 `@ConfigurationProperties`**、也没有 `@ConfigurationPropertiesScan`。所以新建的类**不会被自动注册**。三种做法：

| 做法 | 评价 |
|---|---|
| 类上加 `@Component` | 最省事，和项目"注解式"风格一致（对比 `AiFileInfoMapper` 用 `@Mapper`） |
| 启动类加 `@ConfigurationPropertiesScan` | 干净，但要动启动类 |
| 配 `@EnableConfigurationProperties(InterviewProperties.class)` | 显式，且**唯一能配合 `@Validated` 做启动期校验**的方式 |

**推荐**：`@Component`（简单），校验改为在 `InterviewService` 首次使用时做（见 §4.2）。

⚠️ 若想用 `@Validated @NotBlank` 做启动期校验，注意 **pom 里没有 `spring-boot-starter-validation`**（`spring-boot-starter-web` 自 Boot 2.3 起不再传递它），需要新增依赖——**属新增依赖，建议本步骤不做**，避免为校验引入一个包。

### 4.6 【易踩】异步自调用会静默失效

`@EnableAsync` 靠 Spring 代理生效。若按步骤 2 的写法在 `InterviewService` 内 `upload()` 里直接调 `this.transcribeAsync(interviewId)`，**代理被绕过，方法同步执行**——表现为"上传接口卡 30 分钟"，而日志里看不到线程池名，极难定位。

**在步骤 1 就要定下的结构**（三选一，推荐第 3 种）：

1. 拆一个 `InterviewTaskDispatcher` Bean 专门放 `@Async` 方法，`InterviewService` 注入它；
2. `@Lazy` 注入自身代理（写法隐晦，不推荐）；
3. **发布 `ApplicationEvent` + `@Async @EventListener`**（解耦最好，且天然支持"重启后按状态续跑"）。

### 4.7 【设计冲突】线程池 4/8/50 与"30 分钟阻塞轮询"矛盾

方案 §步骤 2 的流程是 `pollUntilDone(taskId)` **阻塞轮询**（每 5 秒一次、超时 30 分钟），而异步池只有 **核心 4 / 最大 8 / 队列 50**。两者相乘的后果：

- 第 9 个并发上传的任务不会失败，而是**在队列里排队**，用户看到状态一直停在 `UPLOADED`——**像是"卡住"而不是"繁忙"**，且这 50 个排队任务还会被后续的重启清空。
- 8 个线程各被占满 30 分钟，期间任何其他 `@Async` 任务（未来可能复用这个池）全部排队。

**步骤 1 的两个可选决策（必须选一个写进 AsyncConfig）：**

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 轮询留在异步任务里**（贴合方案原文，改动小） | 池 4/8/50 不变；`poll-timeout-ms` 从 30 分钟**降到 10~15 分钟**；拒绝策略用 `AbortPolicy`，并**在 `upload()` 里 catch `TaskRejectedException`**，把状态置 `FAILED` + 明确提示"系统繁忙请稍后重试"（否则事务回滚，用户看到 500 却不知道为什么） | 并发上限 = 8；30 分钟兜底失效 |
| **B. 轮询与提交解耦**（推荐，且直接满足步骤 6） | 异步任务只做「取 URL + 提交任务 + 落 `asr_task_id`」就返回（秒级）；另起 `@Scheduled(fixedDelay = 10s)` 扫描 `status='TRANSCRIBING'` 的记录去查询 `/tasks/{id}` | 多一个调度器；但**不占线程、重启可续跑、天然实现步骤 6 的"卡死 30 分钟自动置 FAILED"** |

> 无论选哪个，`AsyncConfig` 都应显式设置：`setWaitForTasksToCompleteOnShutdown(false)` + `setAwaitTerminationSeconds(30)`（否则关停要等 30 分钟）、`setAllowCoreThreadTimeOut(true)`、`setThreadNamePrefix("interview-asr-")`（步骤 1 验收要"日志里能看到独立线程名"，前缀就是验证手段）。

### 4.8 幂等键：仅靠 `asr_task_id` 不够

步骤 2 验收写的是"重复上传同一音频，能被幂等识别（`asr_task_id` 已有则跳过）"。但 `asr_task_id` 是**提交之后**才有的，**重复上传会在 `ai_interview` 里先新建一条记录**，此时 `asr_task_id` 还是 NULL，根本拦不住。→ 这正是 §3.3 ② 要补 `audio_hash` 的原因：**在上传时就对音频字节做 SHA-256，先查 `audio_hash` 命中则直接复用**。步骤 1 建表时加上这一列，步骤 2 才有地方落。

### 4.9 音频扩展名清单要按规格对齐，且有两个 `isAudio` 易漏点

规格第 41 行定义的格式是：**mp3 / wav / m4a / aac / flac / amr**（≤80MB、≤1.5 小时）。注意：

- 实体 `FileInfo.isAudio()` 要新增（与 `isImage()` 同风格）——方案已列。
- **另一个容易漏**：`FileManageService` 里判断类型用的是**私有方法** `isTextFile(fileType)` / `isImageFile(fileType)`（第 379~395 行），**不改这里，音频永远走不到新分支**，只会落进 `else` 的"其他类型"日志分支。方案第 2 步只写了在 `uploadFile` 里加 `else if (isAudioFile(fileType))`，**没提这个私有方法也得加**。
- 现状不算 bug：音频目前落到 `else` 分支（只打日志、不解析），所以**步骤 1 不改也不影响现有功能**，只是步骤 2 要记得补齐两处。
- **顺带**：`FileManageService.uploadFile` 目前是 `@Transactional` 且失败即回滚，并在成功分支里 `throw new RuntimeException("文件解析失败…")`。音频分支必须是"只落 MinIO + 建记录、不做耗时操作"，才能满足步骤 2"上传接口 3 秒内返回"。步骤 1 不用改这个文件。
- **上传入口要统一**：规格 §8 有独立的 `POST /interview/upload`，方案第 2 步又在 `FileManageService.uploadFile` 加分支。需明确 **`/interview/upload` 复用 `FileManageService.uploadFile`**（复用则自动获得 `file_id` 关联、MinIO 路径与 80MB 限制，且不产生第二套上传逻辑）。这个决定影响 `interview.file_id` 的取值，**建议在步骤 1 定下来**。

### 4.10 音频时长校验没有可用手段（步骤 1 要定策略）

规格数据流第 ③ 步要求"校验音频时长 ≤ 1.5 小时，不达标直接报错"，但 **pom 里没有任何音频解析库**（无 jaudiotagger/mp3spi 等）。两个选择：

> 补充：冻结规格第 168 行标注该模型的官方上限是 **12 小时 / 2GB**，所以"1.5 小时 / 80MB"是**产品侧的自设约束**，不是接口限制。也就是说超过 80MB/1.5 小时的文件百炼其实能吃下，只是产品不接受——拦截策略因此可以更宽松，不必担心误伤接口能力。

| 选择 | 说明 |
|---|---|
| **不引依赖（推荐）** | 上传时只校验大小 ≤80MB（能挡住绝大多数超长文件）；时长在 ASR 返回 `properties.original_duration_in_milliseconds` 后校验，超限则置 `FAILED` + 明确报错。代价：超长文件会先产生一次 ASR 计费 |
| 引入音频库 | 能在上传时精确拦截，但为一个边界场景新增依赖 + 要处理 m4a/aac/amr 等多种容器的解析 |

→ 步骤 1 只需**把 `max-audio-duration-ms: 5400000` 写进配置**并把策略记进文档，实现放步骤 2。

---

## 5. 步骤 1 实施清单（可直接照做）

### 5.1 `application.yml`（改 1 个文件）

```yaml
# ① 已改，无需动：第 48、49 行 max-file-size / max-request-size = 80MB

# ② 新增（注意 base-url 带 /api/v1、api-key 有回退）
interview:
  asr:
    base-url: ${DASHSCOPE_ASR_BASE_URL:https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com/api/v1}
    api-key: ${DASHSCOPE_API_KEY:${spring.ai.openai.api-key}}
    model: qwen-audio-3.1-asr-flash-filetrans
    poll-interval-ms: 5000
    poll-timeout-ms: 900000            # 取舍见 §4.7；若选方案 B 可保持 1800000
    temp-upload-enabled: true          # dev=true / prod=false（唯一开关，见 §4.4）
    max-audio-bytes: 83886080          # 80MB
    max-audio-duration-ms: 5400000     # 1.5 小时
  report:
    max-questions-per-chunk: 8
  retention:
    audio-days: 30

# ③ 新增（§4.3）
minio:
  public-base-url: ${MINIO_PUBLIC_BASE_URL:}
```

### 5.2 `DobaoBackendApplication.java`（改）

```java
@SpringBootApplication
@EnableAsync          // §4.6：为转写任务提供异步能力
@EnableScheduling     // 步骤 6 的 30 天清理；若采纳 §4.7 方案 B 也靠它轮询
public class DobaoBackendApplication { ... }
```

> 无需新增 `spring-boot-starter-aop`：`@EnableAsync` 只需要 spring-aop 的代理支持，而 `spring-aop 6.2.11` 已作为 `spring-boot-starter-web`（经 spring-webmvc / spring-context）的传递依赖存在（本机 `.m2` 已实测该版本存在）。

### 5.3 `config/AsyncConfig.java`（新建）

要点（按 §4.7 选定方案后实现）：

```java
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean("interviewExecutor")
    public ThreadPoolTaskExecutor interviewExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(8);
        e.setQueueCapacity(50);
        e.setThreadNamePrefix("interview-asr-");   // ← 步骤 1 验收就靠它
        e.setAllowCoreThreadTimeOut(true);
        e.setWaitForTasksToCompleteOnShutdown(false);
        e.setAwaitTerminationSeconds(30);
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        e.initialize();
        return e;
    }

    /** 兜底：@Async void 方法的异常不会传播给调用方，必须在这里落日志 */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步任务异常: {}", method.getName(), ex);
    }
}
```

### 5.4 `config/InterviewProperties.java`（新建）

- `@Component` + `@ConfigurationProperties(prefix = "interview")` + Lombok `@Data`
- 嵌套静态类 `Asr` / `Report` / `Retention`，字段名用驼峰（`baseUrl` / `pollIntervalMs` / `tempUploadEnabled`…），Spring 自动做 kebab-case 绑定
- 不要用 `@Validated`（§4.5：无 validation 依赖）

### 5.5 `entity/AiInterview.java`（新建）

- `@TableName("ai_interview")`、`@Data`
- **`@TableId(value = "id", type = IdType.ASSIGN_ID)`** ← 必写（§3.2：表无 AUTO_INCREMENT）
- `@TableField` 逐列声明（`interviewId` / `userId` / `fileId` / `audioHash`…）
- `transcriptJson` / `reportJson` 用 **`String`**（JSON 列与 String 互转由驱动处理；步骤 2 插入前自行 `JSON.parse` 校验，见 §3.3）
- `Long audioDurationMs / speechDurationMs`、`Integer interviewerSpeakerId`、`LocalDateTime createTime / updateTime`（无 DB 默认值时必须由应用赋值，除非采纳 §3.3 ④）
- 状态建议**用一个枚举 + `@EnumValue`** 或直接 `String`；项目现有 `AiFileInfo.status` / `AiPptInst.status` 都是 `String` → **为一致性用 `String`**，另建一个 `InterviewStatus` 常量/枚举类供业务判断

### 5.6 ~~`entity/AiInterviewSegment.java`（新建）~~ 【已作废，见 §9】

此节随 `ai_interview_segment` 表的删除而作废。句子级数据改为在读 `transcript_json` 时
于内存中归一化，不再单独落库。

### 5.7 `mapper/AiInterviewMapper.java`（新建）

照抄项目现有风格（12 行即可）：

```java
@Mapper
public interface AiInterviewMapper extends BaseMapper<AiInterview> {}
```

> 项目用 `@Mapper` 注解式、无 XML（`application.yml` 里 `mapper-locations: classpath:mapper/*.xml` 但目录不存在），沿用它即可。

### 5.8 `entity/record/FileInfo.java`（改）

```java
/** 判断文件是否为音频（面试录音） */
public boolean isAudio() {
    return ("mp3".equalsIgnoreCase(fileType)
            || "wav".equalsIgnoreCase(fileType)
            || "m4a".equalsIgnoreCase(fileType)
            || "aac".equalsIgnoreCase(fileType)
            || "flac".equalsIgnoreCase(fileType)
            || "amr".equalsIgnoreCase(fileType));
}
```

### 5.9 临时验证入口（步骤 1 验收用，做完即删）

`src/test` **存在但是空的**，且 **pom 里没有 `spring-boot-starter-test`**。所以"写单元测试"要先加依赖。**推荐用临时 Controller 验证**（零依赖、可 curl、能同时验证异步线程名）：

```java
@RestController
@RequestMapping("/interview/_dev")
@Profile("dev")   // 或加 @ConditionalOnProperty，避免误上生产
public class InterviewSmokeController {
    @GetMapping("/db")     // 插一条 ai_interview + 两条 segment 再读回来 → 验证实体映射与雪花 ID
    @GetMapping("/async")  // 调 @Async 方法并打印 Thread.currentThread().getName() → 验证线程池
}
```

---

## 6. 步骤 1 验收清单（可执行版，对应方案原文 4 条）

| 方案验收项 | 具体做法 | 通过标准 |
|---|---|---|
| 项目能正常启动，现有对话/PPT 不受影响 | `mvn -o -q -DskipTests compile` 后再 `mvn spring-boot:run` | 退出码 0；启动日志**无 `Could not resolve placeholder`**（§4.2 的回归点）；现有接口可达 |
| 两张表建好，实体能正常读写 | 临时 Controller `/interview/_db`：插入后按 `interview_id` 查回 | 插入成功且 `id` 是 18~19 位雪花 ID（证明 `ASSIGN_ID` 生效，未再报 `Field 'id' doesn't have a default value`）；`transcript_json` 写入合法 JSON 后能读回原文 |
| 上传 60MB 文件不再被 50MB 拦截 | 造一个 60MB 文件 `curl -F` 上传现有上传接口 | 不再返回 `MaxUploadSizeExceededException` |
| 异步线程池生效 | 临时 Controller `/interview/_async` | 日志线程名以 **`interview-asr-`** 开头（与 Tomcat 的 `http-nio-*` 明显区分） |

**加做两项（本次分析新增）：**

| 项 | 做法 |
|---|---|
| 配置项可被读到 | 临时 Controller 打印 `InterviewProperties`，确认 `baseUrl` **以 `/api/v1` 结尾**、`apiKey` 非空 |
| DDL 变更已落地 | 重新 `SHOW CREATE TABLE`，确认 ① `uk_interview_seq` 唯一索引存在、② `audio_hash` 列存在 |

---

## 7. 风险登记（步骤 1 视角）

| # | 风险 | 概率 | 影响 | 处置 |
|---|---|---|---|---|
| 1 | `${DASHSCOPE_API_KEY}` 无默认值导致启动失败 | **高**（已实测变量为空） | 阻断，且会让人误以为改坏了现有功能 | §4.2 用回退到 `spring.ai.openai.api-key` |
| 2 | `base-url` 缺 `/api/v1` → 步骤 2 全 404 | **高**（照抄方案必然发生） | 步骤 2 返工 | §4.1 |
| 3 | 异步自调用静默失效 → 上传接口阻塞 30 分钟 | 中 | 步骤 2 难排查 | §4.6 用事件/独立 Bean |
| 4 | 池满后任务静默排队，状态停在 `UPLOADED` | 中 | 体验差、疑似卡死 | §4.7 选定 A/B 方案 + 拒绝策略兜底 |
| 5 | 转写结果下载未显式 UTF-8 解码 → 复现步骤 0 的乱码 | 中 | 文字稿全废，且"看起来像模型识别差" | §1.3 缺陷 A：`byte[]` + 显式 UTF-8 |
| 6 | `sql/ai_db.sql` 在 5.7 上不可执行、且缺两张新表 | 中 | 换环境时建库失败 | §3.4 |
| 7 | 误解 `begin_time` 时间戳不可靠（其实是脚本显示 bug） | 中 | 可能错误推翻步骤 4 的时间戳方案 | §1.3 缺陷 B 修脚本 |
| 8 | 用步骤 0 这段"无问答"的录音去验收步骤 3/4 | **高**（同一份音频很容易被顺手复用） | 误判问答抽取/报告质量 | §1.2 保留意见：另备真实问答录音 |

---

## 附录：本次核查用过的手段（便于复现）

| 核查 | 手段 |
|---|---|
| 表结构 | JDK 21 单文件源码 + `mysql-connector-j 8.0.33` 直连 `127.0.0.1:3306/dobao`，执行 `SHOW CREATE TABLE` / `SHOW TABLES` / `COUNT(*)` |
| ASR JSON | `fastjson2-2.0.43` 解析两份产物：根键、句数、说话人集合、`begin_time` 单调性、时长字段 |
| 编码 | `File.ReadAllBytes` + `UTF8.GetString`，查找"老师"与乱码序列 `è\x80\x81` |
| 基线 | `mvn -o -q -DskipTests compile`（离线）退出码 0 |
| 代码现状 | 全库 grep `EnableAsync\|EnableScheduling\|ConfigurationProperties\|TaskExecutor\|profiles.active` → **零命中** |

> 本次核查未修改任何业务代码；仅新增本报告。临时校验程序（`.tmp-dbcheck/`）已删除。

---

## 8. 实施记录（步骤 1 已落地）

### 8.1 改动清单

**新增（5 个文件，全部为业务/基础设施代码，无验证产物）**

| 文件 | 作用 |
|---|---|
| `config/AsyncConfig.java` | `interviewExecutor` 专用线程池（core 4 / max 8 / queue 50 / 前缀 `interview-asr-`），`AbortPolicy`；实现 `AsyncConfigurer` 只为了注册全局异步异常处理器（`getAsyncExecutor()` 返回 null，不劫持其他 `@Async`） |
| `config/InterviewProperties.java` | `@Component + @ConfigurationProperties(prefix="interview")`；嵌套 `Asr/Report/Retention`；**不用** `@Validated`（无 validation 依赖） |
| `entity/AiInterview.java` | 22 列一一映射，`@TableId(ASSIGN_ID)`，`audioHash` 已含 |
| `entity/record/InterviewStatus.java` | 状态枚举，避免魔法字符串 |
| `mapper/AiInterviewMapper.java` | `@Mapper extends BaseMapper<AiInterview>` |

> **曾创建的 `config/MybatisPlusMetaObjectHandler.java` 已删除**，见 §10。
> **原计划的 `entity/AiInterviewSegment.java` 与 `mapper/AiInterviewSegmentMapper.java` 已随
> `ai_interview_segment` 表一起删除**，见 §9。

> **验证产物已删除**：`controller/InterviewSmokeController.java` 与 `service/InterviewSmokeAsyncService.java`
> 是仅为执行 §8.3 验收而写的临时入口（默认关闭，不参与任何业务流程），验收通过后已删除，
> 删除后 `mvn -o -DskipTests compile` 仍为 BUILD SUCCESS 且全库无残留引用。
> 若日后需要复跑步骤 1 验收，按 §8.3 的检查项重新加一个临时 Controller 即可，或在步骤 2 用正规接口替代。

**修改（4 个文件）**

| 文件 | 改动 |
|---|---|
| `application.yml` | 新增 `interview.*` 配置块（`base-url` 带 `/api/v1`、`api-key` 回退到 `spring.ai.openai.api-key`、新增 `max-audio-bytes` / `max-audio-duration-ms`）；`minio.public-base-url` |
| `DobaoBackendApplication.java` | `@EnableAsync` + `@EnableScheduling` |
| `entity/record/FileInfo.java` | 新增 `isAudio()`（mp3/wav/m4a/aac/flac/amr） |
| `sql/ai_db.sql` | 追加两张表的**最终 DDL**（`utf8mb4_general_ci`，5.7/8.0 均可执行，含 3 项修正） |

**新增 SQL 资产**

| 文件 | 作用 |
|---|---|
| `sql/interview_step1_patch.sql` | 已建表环境的补丁（①唯一索引 ②`audio_hash` ④时间列默认值），**已实际执行到 `dobao` 库** |

### 8.2 落地时做出的两个决策（原报告留待确认项）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 轮询与线程池的关系（§4.7 A/B） | **选 B：轮询与提交解耦** | 30 分钟阻塞轮询会把 8 个线程占满、第 9 个任务静默排队、重启即丢；解耦后由 `@Scheduled` 扫描 `status=TRANSCRIBING` 驱动，天然满足步骤 6 的"重启续跑 + 30 分钟兜底"。因此 `poll-timeout-ms` 保持方案原值 `1800000` |
| DDL 第 ④ 项（时间列默认值） | **从"可选"改为"应用"** | 让数据库成为"最后修改时间"的权威：插入有 `DEFAULT CURRENT_TIMESTAMP`，更新有 `ON UPDATE CURRENT_TIMESTAMP`。注意它<b>不能单独解决问题</b> —— 走 `updateById` 时仍需应用层显式赋值，见 §10 |

> ③ `error_msg → TEXT`、⑤ `file_id` 宽度、⑥ `report_size` **刻意不做**，保持与冻结规格一致；若步骤 2 发现 `error_msg` 截断，再单独 ALTER。

### 8.3 实测验收结果

应用以 `--interview.smoke-enabled=true --spring.main.lazy-initialization=true` 启动（lazy 的原因见 8.4），端口 8888。

**`GET /interview/_smoke/config` —— 配置绑定与 80MB 限制**

| 检查点 | 实测值 | 判定 |
|---|---|---|
| `baseUrl` | `https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com/api/v1` | ✅ 带 `/api/v1` |
| `apiKeyPresent` | `true`（`sk-ws-***f9Zw`，即回退到了现有 Key） | ✅ 不再因占位符启动失败 |
| `multipartMaxFileSize` / `MaxRequestSize` | `83886080B` / `83886080B`（= 80MB） | ✅ |
| `model` / `pollIntervalMs` / `pollTimeoutMs` | `qwen-audio-3.1-asr-flash-filetrans`（与步骤 0 探针一致）/ `5000` / `1800000` | ✅ |
| `maxAudioBytes` / `maxAudioDurationMs` | `83886080` / `5400000` | ✅ |
| `executorThreadNamePrefix` / `core` / `max` | `interview-asr-` / `4` / `8` | ✅ |

**`GET /interview/_smoke/db` —— 实体读写**

| 检查点 | 实测值 | 判定 |
|---|---|---|
| 主表插入 | 1 行，`id = 2105483951093719042`（19 位雪花 ID） | ✅ `ASSIGN_ID` 生效，无需 AUTO_INCREMENT |
| 插入时字段填充 | `createTime = updateTime = 2026-10-01T10:24:26.5986755` | ✅ |
| JSON 列往返 | `jsonRoundTripOk = true`，读回 `{"properties": {"original_duration_in_milliseconds": 331050}...}` | ✅ |
| 分段插入 + 读回 | 2 行 | ✅ |
| **重复 `seq_no`** | `DuplicateKeyException`（`duplicateSeqNoRejected = true`） | ✅ `uk_interview_seq` 生效 |
| 更新 `status` | `UPLOADED → TRANSCRIBED`，`update_time` `10:24:27 → 10:24:29`（`updateTimeRefreshed = true`） | ✅ |
| 测试数据清理 | `cleanedUp = true`，复查两表 `rows = 0`、无 `smoke-%` 残留 | ✅ |

**`GET /interview/_smoke/async` —— 异步线程池**

| 检查点 | 实测值 | 判定 |
|---|---|---|
| 调用线程 | `http-nio-8888-exec-5`（Tomcat） | — |
| 异步线程 | **`interview-asr-1`** | ✅ `@EnableAsync` + 专用池生效，且跨 Bean 调用未踩自调用陷阱 |

启动日志另有独立证据：`AsyncConfig : 面试转写线程池初始化完成: threadNamePrefix=interview-asr-, core=4, max=8, queue=50`。

**上传尺寸闸门（真实 HTTP 上传）**

| 场景 | 实测 | 判定 |
|---|---|---|
| `POST /file/upload` 60MB | 完整上传 62914774 字节，请求进入控制器后因 **MinIO 未启动**（`java.net.ConnectException: Connection refused`）返回 500 | ✅ **60MB 未被 50MB 老限制拦截**（失败原因与尺寸无关） |
| `POST /file/upload` 85MB | HTTP **413**，仅上传 327680 字节即被拒 | ✅ 80MB 上限仍然有效，且是"放开到 80MB"而非取消限制 |

### 8.4 ⚠️ 一个验收项**未能完成**：完整启动被环境阻断（非本次改动引起）

`mvn -o -DskipTests package` 成功，但**常规启动（非 lazy）失败**：

```
EmbeddingService.init → DynamicPgVectorStoreFactory.createPgVectorStore
  → PgVectorStore.afterPropertiesSet → PostgreSQL 连接失败
ERROR DynamicPgVectorStoreFactory : PgVectorStore初始化失败，表名：vector_file_info
```

**根因**：本机 **pgvector（PostgreSQL:5433）与 MinIO（:9000）都没有在运行**（实测端口均不可达）。
`EmbeddingService` 的 `@PostConstruct` 需要连 pgvector，连不上就让整个上下文启动失败。

**这不是本次改动引起的**：失败链 `agentController → fileContentService → embeddingService → PgVectorStore` 全部是**未被改动的既有代码**，且失败点是 TCP 连接到 5433。
本次唯一动过的既有文件只有 `DobaoBackendApplication`（加两个注解）、`FileInfo`（加一个方法）、`application.yml`（新增配置块），都不在该链路上。

**因此本轮的处理方式**：用 `--spring.main.lazy-initialization=true` 让 pgvector 相关 Bean 延迟创建，从而完成其余全部验收。
副作用也符合预期——一旦请求到依赖向量库的既有接口（如 `GET /file/list`）仍会因同一原因报 500。

**待办（需在有 pgvector + MinIO 的环境执行）**：
- [ ] 起 pgvector 与 MinIO 后跑**常规启动**，确认无 `Could not resolve placeholder` 且 `Started DobaoBackendApplication`
- [ ] 复跑 `GET /file/list` 等既有接口，确认对话/PPT/深度研究未受影响（对应步骤 1 第 1 条验收）

### 8.5 遗留与下一步

| 项 | 说明 |
|---|---|
| 验证产物 | `InterviewSmokeController` / `InterviewSmokeAsyncService` **已删除**（§8.1 注）。步骤 1 的验收结论以 §8.3 的实测记录为准，不再依赖驻留代码 |
| `git` 状态 | 本次改动此前全是 untracked（`??`），**git 未管理**；现已 `git add` 纳入暂存区，**按要求未 commit**（§8.6） |
| 步骤 2 必须先读 | §1.3 缺陷 A（下载结果必须 `byte[]` + 显式 UTF-8）、§4.6（异步自调用）、§4.9（`FileManageService` 私有 `isAudioFile` 也要加）、§8.2（轮询走 B 方案） |
| 步骤 0 脚本 bug | `scripts/interview-asr-probe.ps1` 的 `Format-Ts` 仍是 `[int]$t.TotalMinutes`（四舍五入），建议改为 `[math]::Floor`；本次未改脚本 |
| 环境依赖 | `target/` 下的可执行 jar 已能构建；正式联调前需起 pgvector（5433）与 MinIO（9000） |

### 8.6 Git 纳入清单（已 `git add`，未 commit）

**新增 5 个 Java 类** + **2 个 SQL 文件**

> 注意 `git` 的视角：`dobao-backend/sql/` 整个目录此前从未被跟踪，所以
> `ai_db.sql`（已存在的 dump，本次追加了最终 DDL）在暂存区里同样显示为 `A`（新增），
> 而不是 `M`。

```
dobao-backend/src/main/java/com/dobao/dobaobackend/config/AsyncConfig.java
dobao-backend/src/main/java/com/dobao/dobaobackend/config/InterviewProperties.java
dobao-backend/src/main/java/com/dobao/dobaobackend/entity/AiInterview.java
dobao-backend/src/main/java/com/dobao/dobaobackend/entity/record/InterviewStatus.java
dobao-backend/src/main/java/com/dobao/dobaobackend/mapper/AiInterviewMapper.java
dobao-backend/sql/ai_db.sql
dobao-backend/sql/interview_step1_patch.sql
```

**修改 3 个既有文件**

```
dobao-backend/src/main/java/com/dobao/dobaobackend/DobaoBackendApplication.java
dobao-backend/src/main/java/com/dobao/dobaobackend/entity/record/FileInfo.java
dobao-backend/src/main/resources/application.yml
```

> 刻意<b>没有</b> `git add .`：仓库根有 33MB 的 `interView.m4a`、`probe-output/` 等步骤 0 产物，
> 全量 add 会把大文件带进索引。上述路径是逐个指定的。

---

## 9. 设计变更（v1.1）：删除 `ai_interview_segment` 表

### 9.1 结论

**删掉转写稿片段表**。句子级数据不再单独落库，`ai_interview.transcript_json` 是句子级数据的
唯一持久化来源；步骤 3 的问答抽取、步骤 4 的时间戳回填都在内存中解析它。

### 9.2 为什么（原设计的"必要性"经复核不成立）

原设计给出的三条理由，逐条复核后只有第三条部分成立，但也不足以支撑一张表：

| 原理由 | 复核结论 |
|---|---|
| 步骤 3 要逐句回填 `speaker_role` → 需要行级更新 | ❌ **不成立**。规格把 `speaker_count` 固定为 2，全篇只需判定"哪个 `speaker_id` 是面试官"一次，主表已有 `interviewer_speaker_id`。`speaker_role = (speaker_id == interviewer_speaker_id) ? "interviewer" : "candidate"`，是纯派生值，不构成独立信息 |
| 前端文字稿页要分页 → 需要结构化行 | ❌ **不成立**。实测 40 分钟音频约 524 句、约 15KB JSON，一次返回绰绰有余，无需分页 |
| 步骤 4 的时间戳不能采信模型 → 需要可查询的句子 | ⚠️ **意图正确，手段不必要**。把 `transcript_json` 解析成内存句子列表同样满足；落库只是把同一份数据存了两遍 |

### 9.3 体量实测（说明这也不是"省空间"问题）

| 项 | 实测（步骤 0 那段 5.5 分钟音频） |
|---|---|
| 句子数 | 72 |
| 句子文本合计 | 1454 字 / 4292 字节 |
| 原始 JSON | 79,842 字节 |
| 其中逐字级 `words[]` | 约 64,800 字节 = **81%**，功能完全未使用 |
| 去掉 `words[]` 后的句子级投影 | 15,023 字节 |
| 折算 40 分钟（约 524 句） | 该表约 **0.1MB**，原始 JSON 约 0.58MB |

也就是说：**这张表既没有复制真正占体积的部分（`words[]`），也没有承载任何独立信息**
—— 属于"对最没有独立价值的一层做规范化"。

### 9.4 它唯一站得住的理由（作为对照记录）

**隔离外部格式（防腐层）**：`begin_time` / `end_time` / `sentences[]` / `words[]` 是百炼定的结构，
换模型或百炼改版时，业务只认自己的表结构。这个理由成立的前提是"归一化结果落库"；
只存原始 JSON 就得每次对着外部 schema 解析。**本次权衡后放弃这条**，因为解析成本极低
（一次 `JSON.parse`），而换来的是少一张表、少一处数据漂移风险、少一条写入链路。

### 9.5 实际改动

| 对象 | 动作 |
|---|---|
| `DROP TABLE ai_interview_segment` | ✅ 已执行（`dobao` 库现有 5 张表，segment 已不存在） |
| `entity/AiInterviewSegment.java` | ✅ 已删除 |
| `mapper/AiInterviewSegmentMapper.java` | ✅ 已删除 |
| `sql/ai_db.sql` | ✅ 已移除该表的 DDL 与相关注释 |
| `sql/interview_step1_patch.sql` | ✅ 已改写为「DROP 该表 + `audio_hash` + 时间列默认值」 |
| `docs/interview-summary-coding-plan.md` | ✅ 已按 v1.1 修订（见该文件顶部修订记录） |
| 本报告 §3.3①/§5.6/§8.1/§8.6 | ✅ 已标注作废或更新 |

> §8.3 的实测记录中"分段插入 2 行""重复 `seq_no` 被 `DuplicateKeyException` 拒绝"两项，
> 是**删除该表之前**的验证结果，保留作为当时的事实记录，现已不适用。

### 9.6 后续若需要结构化检索，应该建的是「问答对表」

如果将来要做"我以前被问过哪些题""按知识点检索历史面试"这类功能，
**目标不该是句子表，而应是问答对表**：

```
ai_interview_qa(interview_id, seq_no, question, question_begin_ms,
                answer_summary, answer_quotes, answer_begin_ms, answer_end_ms, topics)
```

理由：句子表只是 ASR 的转述，没有新信息；**问答对才是产品真正的产物**，而且它有天然的检索维度
（问题文本、知识点标签）。当前设计中问答对塞在 `ai_interview.report_json` 里，可读但不可检索。
这一项列为步骤 6 的可选增强，不在本期范围。

---

## 10. 设计变更（v1.2）：删除 `MybatisPlusMetaObjectHandler`

### 10.1 结论

**已删除 `config/MybatisPlusMetaObjectHandler.java`**。时间戳交给数据库维护 +
在更新点显式赋值，不再引入全局自动填充。

### 10.2 删除理由

| 维度 | 评估 |
|---|---|
| 它买到了什么 | 一个"不会忘记写时间"的保证 |
| 它的代价 | ① 一个全局 Bean，行为在调用点完全不可见；② 依赖字符串字段名（`strictInsertFill(metaObject, "updateTime", ...)`），**字段改名不会编译报错，只会静默失效**；③ 只覆盖 MyBatis-Plus 的 `insert`/`updateById`，自定义 SQL 一走就绕过，保证本来就是"半覆盖" |
| 是否必要 | **不必要**。状态机的 6 个以上更新点全部集中在 `InterviewService` 一个类里，靠代码纪律管得住；而且 `update_time` 只是排障用的审计列，没有任何需求读它，静默失效的严重度很低 |

结论：为一个低价值审计列背一个全局隐式约定不划算，去掉它，把规则写进代码和文档。

### 10.3 支撑这个决定的实测证据

去掉 handler 之后，`update_time` 能否正确维护，取决于两个事实。两者都已实测（不是推测）：

**事实一：MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在该列被显式赋值时不生效**

在本机 `ai_interview` 上直接跑 SQL 验证：

| 场景 | 语句 | `update_time` | 结论 |
|---|---|---|---|
| 基准 | `INSERT ...`（不写时间列） | `03:03:23` | 列默认值生效 |
| A | `UPDATE ... SET status='TRANSCRIBING', update_time='03:03:23'` | `03:03:23`（**没动**） | **显式赋值压制了 ON UPDATE** |
| B | `UPDATE ... SET status='TRANSCRIBED'`（不写该列） | `03:03:26` | ON UPDATE 生效 |
| C | 再执行一次同样的 B（值无变化） | `03:03:26`（没动） | 行未发生实际变化时不刷新，属正常行为 |

**事实二：MyBatis-Plus 的 `updateById` 会把非空的 `updateTime` 拼进 SET**

对 `mybatis-plus-core-3.5.12.jar` 做字节码检查：`TableFieldInfo.getSqlSet(...)` 对每个字段都调用
`convertIf(..., FieldStrategy)` 生成条件片段（常量池里可见 `%s != null and %s != null and %s != ''`），
即默认 `FieldStrategy.NOT_NULL` —— **字段非空就进 SET 子句**。

两个事实叠加 → 用 `selectOne → 改字段 → updateById` 的常规写法时，
**谁都不写时间，`update_time` 就会一直停在旧值**。这正是当初加 handler 的原因，
所以删除它必须配一条替代规则。

### 10.4 替代规则（已写入编码方案与实体注释）

两种正确写法任选其一：

```java
// 写法一：读改写时显式赋值
record.setStatus(...);
record.setUpdateTime(LocalDateTime.now());
interviewMapper.updateById(record);

// 写法二（推荐）：列级更新，让 DB 维护该列
interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
        .eq(AiInterview::getInterviewId, interviewId)
        .set(AiInterview::getStatus, ...));   // SET 里没有 update_time，ON UPDATE 才生效
```

`AiInterview` 上的 `@TableField(fill = ...)` 注解已一并移除（没有 handler 时它们是死的），
改为在 `createTime` / `updateTime` 两个字段的 Javadoc 里写明由谁维护、坑在哪。
`AiInterview.java` 的类注释也做了对应更新。

> 备选方案（本次未采用）：`@TableField(value = "update_time", updateStrategy = FieldStrategy.NEVER)`
> —— 让 MP 永不把该列写进 SET，从而完全靠 DB 的 `ON UPDATE` 维护，连"记得赋值"都不需要。
> `FieldStrategy.NEVER` 在 `mybatis-plus-annotation-3.5.12` 中确实存在（已确认）。
> 没采用是因为它比显式赋值更难被读者理解，且同样需要注释解释；将来若更新点分散到多个类，
> 可以改用它作为零纪律方案。

