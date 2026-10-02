# 步骤 0 操作指引：用你的 m4a 验证音频转写

## 这份指引要达成什么

在写任何业务代码之前，用你手上那段几分钟的 m4a 音频，先验证一件**决定整个方案成立与否**的事：

> 百炼能不能把这段面试音频转成文字，并且**分清哪句是面试官说的、哪句是你说的**。

如果这一步不成立，后面 6 步开发都是白做。所以先花 10 分钟验证。

---

## 你要准备的东西

| 项 | 说明 |
|---|---|
| 音频文件 | 你已有的 m4a，几分钟即可，**不用整场** |
| 百炼 API Key | 从 `dobao-backend/src/main/resources/application.yml` 里那一串 `sk-ws-...` 复制即可，项目已经在用它 |
| 电脑能上网 | 需要能访问 `dashscope.aliyuncs.com`（有代理也没关系，脚本会自动识别） |

---

## 第 1 步：打开 PowerShell，进入项目目录

```powershell
cd D:\java-code\dobao-agent
```

---

## 第 2 步（推荐）：先跑一次 Dry-Run，确认配置没写错

这一步**不消耗任何额度、不调用 API**，只把请求长什么样打印出来：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "你的音频完整路径.m4a" -ApiKey "sk-ws-你复制的那串" -DryRun
```

例如：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "D:\audio\面试录音.m4a" -ApiKey "sk-ws-xxxxx" -DryRun
```

**期望看到**：文件大小、模型名、API 地址、代理识别结果、以及一个 `GET .../uploads?action=getPolicy...` 的请求，API Key 被显示为 `sk-***hidden***`。

如果这里报"找不到文件"，说明路径写错了 —— 路径含空格要用引号包起来。

---

## 第 3 步：正式跑探测

去掉 `-DryRun` 即可：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "D:\audio\面试录音.m4a" -ApiKey "sk-ws-xxxxx"
```

脚本会自动完成 7 个阶段：

```
0. 前置检查（文件、大小）
1. 取上传凭证
2. 上传音频到百炼临时存储
3. 提交转写任务（已开启说话人分离）
4. 轮询任务状态（每 5 秒一次）
5. 下载识别结果 JSON
6. 分析说话人分布
7. 输出结论与下一步建议
```

**耗时**：几分钟的音频，大约 30 秒到 2 分钟。

### 如果想用项目里那个业务空间域名

你 `application.yml` 里的地址是业务空间专属域名，如果公共域名不通，加上 `-ApiBase`：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "D:\audio\面试录音.m4a" -ApiKey "sk-ws-xxxxx" `
    -ApiBase "https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com/api/v1"
```

### 如果想提升技术名词识别准确率

把常见技术名词作为热词传进去：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "D:\audio\面试录音.m4a" -ApiKey "sk-ws-xxxxx" `
    -Vocabulary "Kafka,Redis,MySQL,微服务,分布式"
```

### 如果报"连接被关闭"或"由于目标计算机积极拒绝"

脚本会自动读 Windows 系统代理设置。如果还是失败，手动指定：

```powershell
.\scripts\interview-asr-probe.ps1 -AudioPath "..." -ApiKey "..." -Proxy "http://127.0.0.1:7890"
```

你的机器上文代理端口是 **7892**（我从系统设置里读到的），所以大概是：

```powershell
-Proxy "http://127.0.0.1:7892"
```

想强制不走代理，用 `-Proxy none`。

---

## 第 4 步：看结果，回答三个问题

脚本跑完后会在当前目录生成 `probe-output/` 文件夹：

| 文件 | 用途 |
|---|---|
| `{音频名}-asr-raw.json` | 百炼返回的原始结果（完整数据） |
| `{音频名}-transcript.txt` | 可读转写稿，格式 `[时间] [说话人] 文本` |

脚本自身会打印**说话人分布**和**开头 15 句预览**。你要人工判断三件事：

### 问题 1：转写准确率如何？

看预览里的文字。技术名词、人名、公司名错得多吗？

- 错得多 → 用 `-Vocabulary` 加热词重跑一次
- 基本能读 → 通过

### 问题 2：说话人分开了吗？（**最关键**）

看脚本打印的说话人分布，以及预览里的 `S0` / `S1` 标记：

| 脚本输出 | 含义 | 结论 |
|---|---|---|
| 识别到 **2 个** speaker_id | 两人被正确区分 | ✅ 通过 |
| 只有 **1 个** speaker_id | 模型认为全场只有一个人说话 | ❌ **方案需调整** |
| **3 个以上** speaker_id | 同一个人被打成多个 ID（碎片化） | ⚠️ 可通过后处理合并解决 |

只有 1 个 speaker_id 是最坏情况，通常发生在：你戴耳机、面试官声音从电脑外放出来，两个人录音时音质差异极大。

**如果是这种情况，把音频和输出结果发我**，需要重新讨论方案（比如改成双声道分轨录音，或只做整段转写不做问答拆分）。

### 问题 3：面试官能被认出来吗？

打开 `{音频名}-transcript.txt`，取**前 100 行**，贴给任意一个 LLM（Cursor 里的、ChatGPT、或者项目自己的对话助手都行），问：

```
以下是一场面试的转写稿片段。请判断哪一位说话人是面试官（提问方），
哪一位是求职者（回答方）。只输出 JSON：{"interviewer": "Speaker0", "reason": "..."}
```

看它判断得准不准、有没有犹豫。这一步是在验证**步骤 3 的角色判定逻辑能不能成立**。

---

## 第 5 步：把结果告诉我

把这三样发我，我判断是否可以进入编码步骤 1：

1. 脚本的完整输出（含说话人分布）
2. `transcript.txt` 的前 30~50 行
3. 你对上面三个问题的判断

---

## 常见问题

**Q: 会花多少钱？**
几分钟音频，量级在**几分钱**以内。转写按语音内容时长计费。

**Q: API Key 会被写进文件吗？**
不会。脚本只在内存中使用，打印时也会隐藏成 `sk-***hidden***`。但**别把 Key 提交到 git**。

**Q: 上传的音频会被百炼留存吗？**
会。音频上传到百炼临时存储，URL 有效期 48 小时。这是官方的临时存储机制。如果音频涉及敏感内容，请确认你已获得录音各方同意。

**Q: 脚本能处理多长的音频？**
技术上支持 12 小时。但脚本自己会警告超过 80MB 的文件，因为最终功能限制是 80MB / 1.5 小时。探针阶段用几分钟的片段就够。

**Q: 模型名报错说不支持怎么办？**
把 `-Model` 换成 `paraformer-v2` 试试（另一个支持说话人分离的模型）：

```powershell
-Model "paraformer-v2"
```

---

## 附：脚本参数速查

| 参数 | 必填 | 说明 |
|---|---|---|
| `-AudioPath` | ✅ | 音频文件路径 |
| `-ApiKey` | ✅ | 百炼 API Key（也可设环境变量 `DASHSCOPE_API_KEY`） |
| `-ApiBase` | | API 地址，默认公共域名；业务空间需替换 |
| `-Model` | | 默认 `qwen-audio-3.1-asr-flash-filetrans` |
| `-Vocabulary` | | 热词，逗号分隔 |
| `-Proxy` | | 代理地址；默认自动读系统设置，`none` 表示直连 |
| `-DryRun` | | 只打印请求，不调用 API |
| `-PollIntervalSec` | | 轮询间隔，默认 5 秒 |
| `-TimeoutSec` | | 轮询超时，默认 900 秒 |
