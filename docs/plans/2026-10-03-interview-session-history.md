# 面试总结接入会话模型 · 实施计划

> **For implementer:** Use TDD throughout. Write failing test first. Watch it fail. Then implement.
> **设计依据：** [`2026-10-03-interview-session-history-design.md`](./2026-10-03-interview-session-history-design.md)（已确认：零 DDL / `fileid` 存 `interviewId` / 上传即写·异步回填 / 级联删除）

**Goal:** 让"面试总结"成为 `ai_session` 里可被列表、详情、跨设备恢复的一条普通会话记录，从而复用既有的会话历史能力。

**Architecture:** 一场面试 = 一行 `ai_session`（`agent_type='interview'`、`question`=录音文件名、`answer`=状态摘要、`fileid`=`interviewId`）。新增 `InterviewSessionRecorder` 作为唯一写入者，被上传快路径（`InterviewService`）与异步回填（`InterviewTaskService`）共同依赖，避免这两个类互相依赖。读取侧由 `SessionController` 把 `fileid` 解读为 `interviewId` 交给前端，前端复用早已写好但从未接线的 `tryRestore` / `startById`。

**Tech Stack:** Java 21 / Spring Boot / MyBatis-Plus 3.5.12 / Maven 3.9；Vue 3 + TypeScript + Vite。

---

## 前置事实（implementer 必读）

| 项 | 值 |
|---|---|
| 后端目录 | `dobao-backend` |
| 后端测试命令 | `mvn -q -Dtest=<测试类名> -DfailIfNoTests=false test`（在 `dobao-backend` 下执行；已实测可用） |
| 后端全量测试 | `mvn test`（在 `dobao-backend` 下执行） |
| 前端目录 | `dobao-front`（用 npm，仓库里是 `package-lock.json`） |
| 前端校验命令 | `npm run type-check`（即 `vue-tsc --build`） |
| 测试设施 | 后端：JUnit 5 + Mockito + `spring-test`（无 H2、无集成测试数据库）；**前端没有任何测试设施，也没有 test script** |
| 关键约定 | `ai_session.fileid` 在本表里是"按 `agent_type` 解释的业务指针"（见 `AiSession.fileid` 注释）；`agent_type='interview'` 时它存的是 `interviewId` |
| 通用返回 | `BaseResult.newSuccess(data)` / `newError(msg)`，成功码 `200` |
| MyBatis-Plus | 版本 **3.5.5**（`pom.xml` 的 `mybatis.version`）。两个实测坑：① `IService#update(Wrapper)` 返回 **`boolean`**（不是 `int`）；② `list(any())` 会撞 `list(Wrapper)` / `list(IPage)` 重载歧义，测试里必须写成 `ArgumentMatchers.<Wrapper<AiSession>>any()` |
| 测试工具 | `dobao-backend/src/test/java/com/dobao/dobaobackend/testsupport/MybatisPlusTableInfo.ensure(Class...)`：要断言 `LambdaQueryWrapper` 生成的 SQL / 绑定参数时，必须先在 `@BeforeAll` 里注册实体的 `TableInfo`，否则抛 `can not find lambda cache for this entity`（Task 1 已建立，Task 5 复用） |

## 执行状态

**执行顺序（2026-10-03 用户调整）**：`1 → 2 → 3 → 7 → 4 → 8 → 5 → 6 → 9`。
先把"前端传 conversationId（7）→ 读取侧给 interviewId（4）→ 历史回放接线（8）"做出来，让页面上最早能看到"面试会话出现在列表、点开能还原报告"；
再收尾列表分页修正（5）与删除级联（6）。**代价**：Task 5 之前，同一会话多场面试时列表的数量与 total 会不准（已在下面各任务里注明）。

| 任务 | 状态 | 提交 |
|---|---|---|
| 前置：测试依赖 | ✅ | `2869951` |
| Task 1 面试会话写入器 | ✅ 规范 PASS + 质量 APPROVE（5 条 Important 整改后复审 5/5 变异被杀死） | `27e1845` → `ecb9df5` → `162e83b` |
| Task 2 上传即写会话行 | ✅ 已实现（含最终审查整改：幂等命中按状态回填摘要） | 未提交 |
| Task 3 异步回填摘要 | ✅ 已实现（含最终审查整改：发布终态加防护） | 未提交 |
| Task 4 读取路径带 interviewId | ✅ 已实现 | 未提交 |
| Task 5 会话列表分页去重 | ✅ 已实现 | 未提交 |
| Task 6 删除会话级联清理 | ✅ 已实现（含最终审查整改：仍被别的会话引用时只解引用） | 未提交 |
| Task 7 前端带 conversationId | ✅ 已实现 | 未提交 |
| Task 8 前端还原面试面板 | ✅ 已实现（含最终审查整改：分支改为按消息判定） | 未提交 |
| Task 9 端到端手工验收 | ⏳ 待用户环境验收（需要百炼 key + MinIO + 浏览器） | — |

**当前状态（2026-10 最终整体审查后）**：Task 1~8 均已落地并只有单元测试覆盖，**全部尚未提交**；Task 9 的浏览器手工验收需要用户环境。最终审查提出的 5 项修复已处理完毕（混排会话按消息判定、幂等命中回填摘要、删除会话的引用保护、`fileid` 多态语义注释、日志忽略与终态推送防护）。

**为什么 Task 2~8 的提交压后**：工作区里有一批**上一轮未提交的重构**（工作区版 `InterviewReport` 是新结构 `qaList/referenceAnswers/summary`，HEAD 还是旧的 `knowledgeTopics/transcript`；另有 `InterviewProgressHub`、`InterviewTaskService`、`useInterview.ts`、`types/index.ts` 等）。
用户明确"先不提交"这批改动，因此：
- Task 3 若单独提交，其补丁引用 `report.referenceAnswers()`，在 HEAD 上编译不过 → 会产出坏提交，故**实现与测试照做，提交压后**；
- Task 7/8 的文件（`useInterview.ts`/`useChat.ts`）混着同一批未提交改动，同样只改工作区、不提交；
- Task 2/4/5/6 的工作区文件同样已与上一轮重构混在一起，一律等基线落地后**按整文件**提交。

**⚠️ 验证方式的例外（需要你知情）**：Task 7 / Task 8 是前端改动，仓库里没有测试框架。本计划**不引入 vitest**（那会把一个"接上历史"的需求膨胀成"搭一套前端测试基建"）。这两个任务的验证方式是 `npm run type-check` + Task 9 的浏览器手工验收。若你希望引入前端测试框架，请在开始前告知，我会把 Task 7/8 改写成 TDD 形式。

**⚠️ 不要 `git add -A`**：工作区里有大量与本需求无关的未提交改动（面试模块重构、`docs/` 里其它文档）。每个任务的提交都只 `git add` 本任务列出的文件。

---

## Task 1: `InterviewSessionRecorder` —— 会话行的写入者

**Files:**
- Create: `dobao-backend/src/main/java/com/dobao/dobaobackend/interview/InterviewSessionRecorder.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/interview/InterviewSessionRecorderTest.java`

**Step 1: 写失败测试**

```java
package com.dobao.dobaobackend.interview;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.service.AiSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 面试会话写入器的规则测试。
 *
 * <p>用 mock 顶替 {@link AiSessionService}：这里要证明的是"写什么、按什么条件写"，
 * 不是 MyBatis-Plus 的 SQL 能力（项目里没有测试库，见实施计划前置事实）。
 */
class InterviewSessionRecorderTest {

    private AiSessionService sessionService;
    private InterviewSessionRecorder recorder;

    @BeforeEach
    void setUp() {
        sessionService = mock(AiSessionService.class);
        recorder = new InterviewSessionRecorder(sessionService);
    }

    @Test
    @DisplayName("首次上传：写一行面试会话，question=文件名、fileid=interviewId、agentType=interview")
    void recordUploaded_insertsSessionRow() {
        when(sessionService.getOne(any())).thenReturn(null);

        recorder.recordUploaded("conv-1", "iv-1", "interView.m4a");

        ArgumentCaptor<SaveQuestionRequest> captor = ArgumentCaptor.forClass(SaveQuestionRequest.class);
        verify(sessionService).saveQuestion(captor.capture());
        SaveQuestionRequest saved = captor.getValue();
        assertEquals("conv-1", saved.getSessionId());
        assertEquals("interView.m4a", saved.getQuestion());
        assertEquals("iv-1", saved.getFileid());
        assertEquals("interview", saved.getAgentType());
    }

    @Test
    @DisplayName("同一 (会话, 面试) 已存在：只刷新时间，不重复插行")
    void recordUploaded_existingRow_onlyTouchesTime() {
        AiSession existing = new AiSession();
        existing.setId(11L);
        existing.setUpdateTime(LocalDateTime.now().minusDays(1));
        when(sessionService.getOne(any())).thenReturn(existing);

        recorder.recordUploaded("conv-1", "iv-1", "interView.m4a");

        verify(sessionService, never()).saveQuestion(any());
        ArgumentCaptor<AiSession> captor = ArgumentCaptor.forClass(AiSession.class);
        verify(sessionService).updateById(captor.capture());
        assertTrue(captor.getValue().getUpdateTime().isAfter(LocalDateTime.now().minusMinutes(1)),
                "应把 update_time 刷新到现在，列表排序才会浮上来");
    }

    @Test
    @DisplayName("没传 conversationId（老调用方）：完全不碰会话表")
    void recordUploaded_withoutConversationId_doesNothing() {
        recorder.recordUploaded(null, "iv-1", "interView.m4a");
        recorder.recordUploaded("", "iv-1", "interView.m4a");

        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("回填完成摘要：按 agent_type + fileid 定位，写问答/参考回答条数")
    void markReady_writesSummary() {
        AiSession row = new AiSession();
        row.setId(7L);
        when(sessionService.list(any())).thenReturn(List.of(row));

        recorder.markReady("iv-1", 12, 8);

        ArgumentCaptor<UpdateAnswerRequest> captor = ArgumentCaptor.forClass(UpdateAnswerRequest.class);
        verify(sessionService).updateAnswer(captor.capture());
        assertEquals(7L, captor.getValue().getId());
        assertNotNull(captor.getValue().getAnswer());
        assertTrue(captor.getValue().getAnswer().contains("12"), "摘要里应出现问答条数");
        assertTrue(captor.getValue().getAnswer().contains("8"), "摘要里应出现参考回答条数");
    }

    @Test
    @DisplayName("回填失败摘要：带上失败原因")
    void markFailed_writesReason() {
        AiSession row = new AiSession();
        row.setId(8L);
        when(sessionService.list(any())).thenReturn(List.of(row));

        recorder.markFailed("iv-1", "分析失败: 模型超时");

        ArgumentCaptor<UpdateAnswerRequest> captor = ArgumentCaptor.forClass(UpdateAnswerRequest.class);
        verify(sessionService).updateAnswer(captor.capture());
        assertTrue(captor.getValue().getAnswer().contains("分析失败: 模型超时"));
    }

    @Test
    @DisplayName("回填只认面试类型的会话行（不能误伤 fileid 恰好相同的 chat 行）")
    @SuppressWarnings("unchecked")
    void updateSummary_filtersByAgentTypeAndFileid() {
        when(sessionService.list(any())).thenReturn(List.of());

        recorder.markReady("iv-1", 1, 1);

        ArgumentCaptor<Wrapper<AiSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).list(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("agent_type"), "过滤条件必须含 agent_type，实际: " + sql);
        assertTrue(sql.contains("fileid"), "过滤条件必须含 fileid，实际: " + sql);
    }
}
```

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=InterviewSessionRecorderTest -DfailIfNoTests=false test`
Expected: FAIL —— 编译不过，`找不到符号: 类 InterviewSessionRecorder`

**Step 3: 最小实现**

`dobao-backend/src/main/java/com/dobao/dobaobackend/interview/InterviewSessionRecorder.java`：

```java
package com.dobao.dobaobackend.interview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.service.AiSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试总结 → {@code ai_session} 的写入器（本需求唯一的会话行写入点）。
 *
 * <p><b>一场面试 = 一行会话记录</b>：
 * <ul>
 *   <li>{@code session_id} = 前端 conversationId（一个会话可含多场面试 → 多行）</li>
 *   <li>{@code agent_type} = {@value #AGENT_TYPE}</li>
 *   <li>{@code question} = 录音文件名（侧边栏标题取 question，正好显示成文件名）</li>
 *   <li>{@code answer} = 状态摘要（报告正文的唯一来源仍是 {@code ai_interview.report_json}）</li>
 *   <li>{@code fileid} = interviewId —— 沿用本表"按 agent_type 解释的业务指针"约定
 *       （见 {@link AiSession#getFileid()} 的注释）</li>
 * </ul>
 *
 * <p><b>为什么单独成 Bean</b>：上传写入在 {@link com.dobao.dobaobackend.service.InterviewService}
 * （快路径），回填在 {@link com.dobao.dobaobackend.service.InterviewTaskService}（异步线程），
 * 而这两个类被明确要求互不依赖（见 InterviewService 类注释）。本类作为第三方被两者依赖。
 *
 * <p>回填按 {@code interviewId} 命中<b>所有</b>引用它的会话行：同一段录音可能出现在多个会话里
 * （幂等命中同一场分析），这些行都该拿到同一份摘要。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSessionRecorder {

    /** 会话表里标识"这是一场面试"的 agent_type */
    public static final String AGENT_TYPE = "interview";

    /** 处理中的占位摘要（上传后、重试时） */
    private static final String RUNNING_SUMMARY = "面试总结进行中…";

    private final AiSessionService sessionService;

    /**
     * 记录"某会话里开始了某场面试"。幂等：同一 (会话, 面试) 只保留一行。
     *
     * @param conversationId 前端会话ID；为空表示老调用方，退化为"只写 ai_interview"
     */
    public void recordUploaded(String conversationId, String interviewId, String fileName) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(interviewId)) {
            log.info("缺少会话ID或面试ID，跳过会话记录: conversationId={}, interviewId={}",
                    conversationId, interviewId);
            return;
        }
        AiSession existing = find(conversationId, interviewId);
        if (existing != null) {
            // 同一会话重复提交同一段录音（音频哈希幂等命中）：只把这场顶到列表最前
            existing.setUpdateTime(LocalDateTime.now());
            sessionService.updateById(existing);
            log.info("面试会话已存在，仅刷新时间: conversationId={}, interviewId={}", conversationId, interviewId);
            return;
        }
        sessionService.saveQuestion(SaveQuestionRequest.builder()
                .sessionId(conversationId)
                .question(StringUtils.hasText(fileName) ? fileName : interviewId)
                .fileid(interviewId)
                .agentType(AGENT_TYPE)
                .build());
        log.info("面试会话已创建: conversationId={}, interviewId={}, fileName={}",
                conversationId, interviewId, fileName);
    }

    /** 处理中：把上一次留下的失败摘要换掉（重试时用） */
    public void markRunning(String interviewId) {
        updateSummary(interviewId, RUNNING_SUMMARY);
    }

    /** 报告就绪 */
    public void markReady(String interviewId, int qaCount, int referenceCount) {
        updateSummary(interviewId, String.format("面试总结已完成：共 %d 条问答、%d 条参考回答",
                qaCount, referenceCount));
    }

    /** 处理失败（转写失败 / 报告生成失败 / 超时都走这里） */
    public void markFailed(String interviewId, String reason) {
        updateSummary(interviewId, StringUtils.hasText(reason)
                ? "面试总结失败：" + reason
                : "面试总结失败");
    }

    /** 按 interviewId 回填所有引用它的面试会话行 */
    private void updateSummary(String interviewId, String answer) {
        if (!StringUtils.hasText(interviewId)) {
            return;
        }
        List<AiSession> rows = sessionService.list(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getAgentType, AGENT_TYPE)
                .eq(AiSession::getFileid, interviewId));
        for (AiSession row : rows) {
            sessionService.updateAnswer(UpdateAnswerRequest.builder()
                    .id(row.getId())
                    .answer(answer)
                    .build());
        }
        log.info("面试会话摘要已回填: interviewId={}, rows={}, answer={}", interviewId, rows.size(), answer);
    }

    private AiSession find(String conversationId, String interviewId) {
        return sessionService.getOne(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, conversationId)
                .eq(AiSession::getFileid, interviewId)
                .eq(AiSession::getAgentType, AGENT_TYPE)
                .last("LIMIT 1"));
    }
}
```

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=InterviewSessionRecorderTest -DfailIfNoTests=false test`
Expected: PASS（6 个用例）

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/interview/InterviewSessionRecorder.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/interview/InterviewSessionRecorderTest.java
git commit -m "feat(interview): 新增面试会话写入器，一场面试写一行 ai_session"
```

**✅ 已完成（`27e1845`，质量审查整改后 `ecb9df5`）**，与上面这份草稿有三处必须知道的偏差（后续任务照此办理）：

1. 测试里 `sessionService.list(any())` 必须写 `ArgumentMatchers.<Wrapper<AiSession>>any()`，否则 MyBatis-Plus 3.5.5 的 `list(Wrapper)` / `list(IPage)` 重载歧义会让测试编译不过。
2. 断言 wrapper 的 SQL/参数前，要在 `@BeforeAll` 里 `MybatisPlusTableInfo.ensure(AiSession.class)`（工具类已建立）；不注册会抛 `can not find lambda cache for this entity`。
3. 质量审查把回填从"`list` 查出 N 行 → 逐行 `updateAnswer`"改成了**一次批量 `UPDATE`**（`AiSessionServiceImpl.updateAnswer` 内部还要 `getById`，等于 1+2N 条 SQL 且是整行读-改-写），并且**显式 `set(AiSession::getUpdateTime, LocalDateTime.now())`** —— `ai_session.update_time` 在本仓库全部来自 JVM 时钟，省掉它会写 UTC、比 JVM 早 8 小时，`update_time desc` 排序就错了。

---

## Task 2: 上传即写会话行（`InterviewService.upload` + 接口参数）

**Files:**
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewService.java`
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/controller/InterviewController.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewServiceTest.java`（新建）

**Step 1: 写失败测试**

```java
package com.dobao.dobaobackend.service;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.interview.dto.InterviewUploadVO;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传快路径：既要落 ai_interview，也要落一行面试会话（ai_session）。
 */
class InterviewServiceTest {

    private InterviewProperties properties;
    private AiInterviewMapper interviewMapper;
    private FileManageService fileManageService;
    private MinioService minioService;
    private InterviewTaskService taskService;
    private InterviewSessionRecorder sessionRecorder;
    private InterviewService service;

    @BeforeEach
    void setUp() {
        // 用真实的 InterviewProperties：里面的上限值就是配置默认值，不需要打桩
        properties = new InterviewProperties();
        interviewMapper = mock(AiInterviewMapper.class);
        fileManageService = mock(FileManageService.class);
        minioService = mock(MinioService.class);
        taskService = mock(InterviewTaskService.class);
        sessionRecorder = mock(InterviewSessionRecorder.class);
        service = new InterviewService(properties, interviewMapper, fileManageService,
                minioService, taskService, new ObjectMapper(), sessionRecorder);
    }

    private MultipartFile audio() {
        return new MockMultipartFile("file", "interView.m4a", "audio/m4a",
                "fake-audio".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("新上传：写会话行（question=文件名、fileid=interviewId），并提交转写")
    void upload_writesSessionRow() {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(fileManageService.uploadFile(any())).thenReturn(FileInfo.builder()
                .fileId("file-1").fileName("interView.m4a").fileType("m4a").fileSize(10L).build());

        InterviewUploadVO uploaded = service.upload("conv-1", audio());

        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> interviewId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        verify(sessionRecorder).recordUploaded(conversationId.capture(), interviewId.capture(), fileName.capture());
        assertEquals("conv-1", conversationId.getValue());
        assertEquals(uploaded.interviewId(), interviewId.getValue());
        assertEquals("interView.m4a", fileName.getValue());
        verify(taskService).submitTranscriptionAsync(anyString(), anyString());
    }

    @Test
    @DisplayName("幂等命中（同一音频重复上传）：复用 interviewId，但必须补齐目标会话的会话行")
    void upload_duplicateHit_stillRecordsSessionRow() {
        AiInterview existing = new AiInterview();
        existing.setInterviewId("iv-9");
        existing.setStatus("READY");
        existing.setFileName("interView.m4a");
        existing.setFileId("file-9");
        when(interviewMapper.selectOne(any())).thenReturn(existing);

        InterviewUploadVO uploaded = service.upload("conv-2", audio());

        assertTrue(uploaded.reused(), "命中已有记录时应标记 reused");
        verify(fileManageService, never()).uploadFile(any());
        verify(sessionRecorder).recordUploaded("conv-2", "iv-9", "interView.m4a");
        verify(taskService, never()).submitTranscriptionAsync(anyString(), anyString());
    }

    @Test
    @DisplayName("没传 conversationId：照常落 ai_interview，交给 Recorder 决定不写会话行")
    void upload_withoutConversationId_stillUploads() {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(fileManageService.uploadFile(any())).thenReturn(FileInfo.builder()
                .fileId("file-2").fileName("interView.m4a").fileType("m4a").fileSize(10L).build());

        service.upload(null, audio());

        verify(sessionRecorder).recordUploaded(org.mockito.ArgumentMatchers.isNull(),
                anyString(), anyString());
    }
}
```

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=InterviewServiceTest -DfailIfNoTests=false test`
Expected: FAIL —— 编译不过：`InterviewService` 的构造器没有 `InterviewSessionRecorder` 参数、也没有 `upload(String, MultipartFile)`

**Step 3: 最小实现**

3a. `InterviewService`：加字段、改 `upload` 签名、两处调用 Recorder。

字段（加在 `private final ObjectMapper objectMapper;` 之后）：

```java
    private final InterviewSessionRecorder sessionRecorder;
```

`upload` 方法整体替换（只列改动后的完整方法）：

```java
    /**
     * 上传面试录音。
     *
     * <p>幂等规则：先算音频内容 SHA-256，命中已有记录且那条记录不是 FAILED 时直接复用，
     * 不重复上传、不重复计费。注意<b>不能只靠 {@code asr_task_id} 判重</b>——它在提交转写
     * 之前是 NULL，拦不住重复上传。
     *
     * <p>本方法同时负责"让这场面试出现在会话列表里"：拿到 interviewId 后立刻写一行
     * {@code ai_session}（见 {@link InterviewSessionRecorder}）。<b>幂等命中的分支也要写</b>——
     * 否则把已上传过的录音传到另一个会话里，那个会话永远不会出现这场面试。
     *
     * @param conversationId 前端会话ID（可选；为空时退化为"只写 ai_interview"）
     * @param file           上传的音频（mp3/wav/m4a/aac/flac/amr，≤80MB）
     * @return 上传结果（含 interviewId）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewUploadVO upload(String conversationId, MultipartFile file) {
        // 上传前置校验：格式 + 大小
        validate(file);

        String audioHash = sha256(file);
        AiInterview existing = findByAudioHash(audioHash);
        // 去重：已有记录且不是 FAILED 状态时直接复用，不重复转写、不重复计费
        if (existing != null && !InterviewStatus.FAILED.name().equals(existing.getStatus())) {
            log.info("音频内容命中已有记录，直接复用: audioHash={}, interviewId={}, status={}",
                    audioHash, existing.getInterviewId(), existing.getStatus());
            sessionRecorder.recordUploaded(conversationId, existing.getInterviewId(), existing.getFileName());
            return new InterviewUploadVO(existing.getInterviewId(), existing.getStatus(),
                    existing.getFileName(), existing.getFileSize(), true);
        }

        // 落 MinIO + ai_file_info 表 + ai_interview 记录表
        FileInfo fileInfo = fileManageService.uploadFile(file);
        String objectName = FileManageService.generateObjectName(fileInfo.getFileId(), fileInfo.getFileType());

        AiInterview record = new AiInterview();
        record.setInterviewId(UUID.randomUUID().toString());
        record.setUserId(DEFAULT_USER_ID);
        record.setFileId(fileInfo.getFileId());
        record.setAudioHash(audioHash);
        record.setFileName(fileInfo.getFileName());
        record.setFileSize(fileInfo.getFileSize());
        record.setAudioUrl(fileInfo.getMinioPath());
        record.setAsrModel(properties.getAsr().getModel());
        record.setStatus(InterviewStatus.UPLOADED.name());
        interviewMapper.insert(record);

        // 让这场面试出现在会话列表里（与 ai_interview 同一个事务）
        sessionRecorder.recordUploaded(conversationId, record.getInterviewId(), record.getFileName());

        log.info("面试记录已创建: interviewId={}, fileId={}, objectName={}, size={}B",
                record.getInterviewId(), fileInfo.getFileId(), objectName, fileInfo.getFileSize());

        // 提交异步任务：取音频 URL（dev 要上传到百炼临时存储）+ 提交转写任务
        taskService.submitTranscriptionAsync(record.getInterviewId(), objectName);

        return new InterviewUploadVO(record.getInterviewId(), record.getStatus(),
                record.getFileName(), record.getFileSize(), false);
    }
```

新增 import：

```java
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
```

3b. `InterviewController.upload` 加参数：

```java
    /**
     * 上传面试录音，返回 interviewId 与初始状态
     *
     * @param conversationId 前端会话ID（可选）：带上它，这场面试才会出现在会话列表里
     */
    @PostMapping("/upload")
    public BaseResult<InterviewUploadVO> upload(@RequestParam("file") MultipartFile file,
                                                @RequestParam(value = "conversationId", required = false)
                                                String conversationId) {
        log.info("收到面试录音上传请求: fileName={}, size={}, conversationId={}",
                file == null ? null : file.getOriginalFilename(),
                file == null ? null : file.getSize(),
                conversationId);
        try {
            return BaseResult.newSuccess(interviewService.upload(conversationId, file));
        } catch (IllegalArgumentException e) {
            // 参数类错误（格式/大小/空文件）是可预期的，按业务错误返回，不打堆栈
            log.warn("面试录音上传参数校验失败: {}", e.getMessage());
            return BaseResult.newError(e.getMessage());
        } catch (Exception e) {
            log.error("面试录音上传失败", e);
            return BaseResult.newError("面试录音上传失败: " + e.getMessage());
        }
    }
```

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=InterviewServiceTest -DfailIfNoTests=false test`
Expected: PASS（3 个用例）

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewService.java \
        dobao-backend/src/main/java/com/dobao/dobaobackend/controller/InterviewController.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewServiceTest.java
git commit -m "feat(interview): 上传即写会话行，幂等命中分支同样补齐"
```

---

## Task 3: 异步回填摘要（`InterviewTaskService`）

**Files:**
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewTaskService.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewTaskServiceTest.java`（新建）

**关键设计**：失败回填**不要**散落在 8 个 `catch`/失败分支里——`updateStatus` 已经是"状态流转的唯一入口"（类注释原文：状态流转一律走 `updateStatus`），把失败摘要挂在那里，所有失败路径（提交失败、重试失败、转写超时、转写失败、JSON 非法、超时长、分析异常）自动覆盖。

**Step 1: 写失败测试**

```java
package com.dobao.dobaobackend.service;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewReportGenerator;
import com.dobao.dobaobackend.interview.InterviewReportRenderer;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
import com.dobao.dobaobackend.interview.progress.InterviewProgressHub;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试状态机 → 会话摘要的回填。
 *
 * <p>只测两个汇合点：报告就绪（{@code publishReport}）与失败（{@code updateStatus}）。
 * 中间过程（转写、角色判定、LLM）不在本需求的范围内。
 */
@ExtendWith(MockitoExtension.class)
class InterviewTaskServiceTest {

    /** 真实配置对象：pollTimeoutMs 等默认值即真实值，不需要打桩 */
    @Spy
    private InterviewProperties properties = new InterviewProperties();

    @Mock
    private AiInterviewMapper interviewMapper;
    @Mock
    private InterviewReportGenerator reportGenerator;
    @Mock
    private InterviewReportRenderer reportRenderer;
    @Mock
    private MinioService minioService;
    @Mock
    private InterviewProgressHub progressHub;
    @Mock
    private InterviewSessionRecorder sessionRecorder;
    @Mock
    private ObjectMapper objectMapper;

    /** 其余构造器依赖（转写 / 归一化 / 事件发布）在本测试里用不到，Mockito 会传 null */
    @InjectMocks
    private InterviewTaskService taskService;

    @Test
    @DisplayName("报告就绪：回填完成摘要（问答数 / 参考回答数）")
    void publishReport_marksReady() throws Exception {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");

        InterviewReport report = new InterviewReport("iv-1", 1000L, LocalDateTime.now(),
                List.of(), List.of(), new ReportSummary(List.of(), List.of(), "总结"));
        when(reportGenerator.generate(eq(record), any())).thenReturn(report);
        when(reportRenderer.render(report)).thenReturn("# 报告");
        when(minioService.uploadFile(anyString(), any(), anyString())).thenReturn("http://minio/report.md");

        taskService.publishReport(record, List.of());

        verify(sessionRecorder).markReady("iv-1", 0, 0);
    }

    @Test
    @DisplayName("转写超时：走 updateStatus 的失败路径，会话摘要被标记为失败")
    void pollTranscribingTasks_timeout_marksFailed() {
        AiInterview timedOut = new AiInterview();
        timedOut.setInterviewId("iv-1");
        // 第一次 selectList = 已超时的那批；第二次 = 尚未超时的那批（空）
        when(interviewMapper.selectList(any())).thenReturn(List.of(timedOut), List.of());

        taskService.pollTranscribingTasks();

        verify(sessionRecorder).markFailed(eq("iv-1"), contains("转写超时"));
        verify(progressHub).publishStatus(eq("iv-1"), eq(InterviewStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("没有失败发生时不写失败摘要")
    void pollTranscribingTasks_noTasks_noSummaryWrite() {
        when(interviewMapper.selectList(any())).thenReturn(List.of(), List.of());

        taskService.pollTranscribingTasks();

        verify(sessionRecorder, never()).markFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("回填会话摘要失败时不能把已就绪的面试翻成失败")
    void publishReport_sessionWriteFailure_isSwallowed() throws Exception {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");
        InterviewReport report = new InterviewReport("iv-1", 1000L, LocalDateTime.now(),
                List.of(), List.of(), new ReportSummary(List.of(), List.of(), "总结"));
        when(reportGenerator.generate(eq(record), any())).thenReturn(report);
        when(reportRenderer.render(report)).thenReturn("# 报告");
        when(minioService.uploadFile(anyString(), any(), anyString())).thenReturn("http://minio/report.md");
        // 会话表写失败：不能影响面试本身
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(sessionRecorder).markReady(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> taskService.publishReport(record, List.of()));

        // 报告仍然照常推给前端
        verify(progressHub).publishComplete(eq("iv-1"), anyString(), anyString());
    }
}
```

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=InterviewTaskServiceTest -DfailIfNoTests=false test`
Expected: FAIL —— 编译不过：`publishReport` 是 private（测试看不到）、构造器没有 `InterviewSessionRecorder`

**Step 3: 最小实现**

3a. 字段（加在 `private final ObjectMapper objectMapper;` 之后）+ import：

```java
    private final InterviewSessionRecorder sessionRecorder;
```

```java
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
```

3b. 新增"回填摘要"的容错包装 + 改 `updateStatus`。

```java
    /**
     * 回填会话摘要，**并且吞掉它自己的异常**。
     *
     * <p>摘要只是"让这场面试在会话列表里看得见"的辅助信息，它不能反过来影响面试本身：
     * {@code publishReport} 里任何异常都会被 {@code runAnalysis} 的 catch 接住并把状态置成
     * FAILED —— 也就是说，如果回填抛异常而这里不拦，一场报告已经生成好的面试会被翻成"失败"。
     */
    private void recordSessionSummary(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("回填面试会话摘要失败（不影响面试流程本身）: {}", e.getMessage(), e);
        }
    }
```

`updateStatus` 完整替换：

```java
    /**
     * 统一的状态流转入口（列级更新，让数据库维护 {@code update_time}）。
     *
     * <p>这里同时是 SSE 的推送点：状态是"已经落库的事实"，推给前端只是通知，
     * 所以推送放在 update 之后，推失败也不会影响状态机（前端还有轮询兜底）。
     * 改状态只有本类会做，故不对外开放。
     *
     * <p><b>这里也是会话摘要的回填点</b>：失败分支有八处（提交失败、重试失败、转写超时、
     * 转写失败、JSON 非法、超时长、分析异常…），逐个挂会漏；挂在唯一入口上，"失败"
     * 这件事与"摘要写失败"这件事不可能不一致。转写/分析开始时顺手把上一次的失败摘要
     * 换成"进行中"，避免重试后列表里还挂着旧失败文案。
     *
     * @param errorMsg 失败原因；传 null 会显式清空该列（重试时用得上）
     */
    private void updateStatus(String interviewId, InterviewStatus status, String errorMsg) {
        int rows = interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getStatus, status.name())
                .set(AiInterview::getErrorMsg, errorMsg));
        log.info("状态更新: interviewId={}, status={}, rows={}", interviewId, status, rows);

        if (status == InterviewStatus.FAILED) {
            recordSessionSummary(() -> sessionRecorder.markFailed(interviewId, errorMsg));
        } else if (status == InterviewStatus.TRANSCRIBING || status == InterviewStatus.ANALYZING) {
            recordSessionSummary(() -> sessionRecorder.markRunning(interviewId));
        }

        progressHub.publishStatus(interviewId, status, errorMsg);
    }
```

3c. `publishReport`：可见性改为包级（为了单测）+ 就绪回填。只改签名与末尾两处：

```java
    /**
     * 报告落库与落盘。
     *
     * <p>包级可见（而不是 private）是为了让 {@code InterviewTaskServiceTest} 能直接
     * 验证"报告就绪 → 会话摘要回填"这一条规则，不需要绕异步入口。
     */
    void publishReport(AiInterview record, List<QaItem> qaItems) {
        ...
        //  5. 落库：更新面试记录状态为就绪
        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getReportJson, reportJson)
                .set(AiInterview::getReportFileUrl, reportUrl)
                .set(AiInterview::getReportFileName, objectName)
                .set(AiInterview::getStatus, InterviewStatus.READY.name())
                .set(AiInterview::getErrorMsg, null));

        // 会话摘要回填：报告正文仍在 report_json，这里只写一行可读摘要给会话列表/详情兜底。
        // 用 recordSessionSummary 包一层：回填失败绝不能把这场已经生成好报告的面试翻成 FAILED
        recordSessionSummary(() -> sessionRecorder.markReady(interviewId,
                report.qaList() == null ? 0 : report.qaList().size(),
                report.referenceAnswers() == null ? 0 : report.referenceAnswers().size()));

        // 落库之后再推终态：前端拿到 complete 就能直接渲染报告正文（不必再请求一次报告接口）
        progressHub.publishComplete(interviewId, reportUrl, reportJson);
        ...
    }
```

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=InterviewTaskServiceTest -DfailIfNoTests=false test`
Expected: PASS（4 个用例）

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewTaskService.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewTaskServiceTest.java
git commit -m "feat(interview): 报告就绪/失败时回填会话摘要"
```

---

## Task 4: 读取路径 —— 会话详情带上 `interviewId`

**Files:**
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/entity/vo/MessageVO.java`
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/controller/SessionControllerTest.java`（新建）

**Step 1: 写失败测试**

```java
package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.MessageVO;
import com.dobao.dobaobackend.entity.vo.SessionDetailVO;
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import com.dobao.dobaobackend.service.InterviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话读取路径：面试会话要把它那行的 fileid 解读成 interviewId 交给前端。
 */
class SessionControllerTest {

    private AiSessionService sessionService;
    private AiFileInfoMapper aiFileInfoMapper;
    private AiPptInstMapper aiPptInstMapper;
    private InterviewService interviewService;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(AiSessionService.class);
        aiFileInfoMapper = mock(AiFileInfoMapper.class);
        aiPptInstMapper = mock(AiPptInstMapper.class);
        interviewService = mock(InterviewService.class);
        controller = new SessionController();
        ReflectionTestUtils.setField(controller, "aiSessionService", sessionService);
        ReflectionTestUtils.setField(controller, "aiFileInfoMapper", aiFileInfoMapper);
        ReflectionTestUtils.setField(controller, "aiPptInstMapper", aiPptInstMapper);
        ReflectionTestUtils.setField(controller, "interviewService", interviewService);
    }

    private AiSession interviewRow() {
        AiSession row = new AiSession();
        row.setId(5L);
        row.setSessionId("conv-1");
        row.setAgentType("interview");
        row.setQuestion("interView.m4a");
        row.setAnswer("面试总结已完成：共 12 条问答、8 条参考回答");
        row.setFileid("iv-1");
        row.setCreateTime(LocalDateTime.now());
        return row;
    }

    @Test
    @DisplayName("面试会话详情：interviewId=fileid、fileName=question，且不去查文件表")
    void getSession_interview_exposesInterviewId() {
        // 注意：IService#list 有 list(Wrapper) / list(IPage) 两个重载，裸 any() 会编译不过
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(interviewRow()));

        BaseResult<SessionDetailVO> result = controller.getSession("conv-1");

        assertEquals(200, result.getCode());
        assertEquals("interview", result.getData().getAgentType());
        MessageVO message = result.getData().getMessages().get(0);
        assertEquals("iv-1", message.getInterviewId());
        assertEquals("interView.m4a", message.getFileName());
        assertEquals("iv-1", message.getFileid());
        verifyNoInteractions(aiFileInfoMapper);
    }

    @Test
    @DisplayName("普通会话详情：interviewId 为空")
    void getSession_chat_hasNoInterviewId() {
        AiSession row = new AiSession();
        row.setId(6L);
        row.setSessionId("conv-2");
        row.setAgentType("chat");
        row.setQuestion("介绍一下 node.js");
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(row));

        BaseResult<SessionDetailVO> result = controller.getSession("conv-2");

        assertNull(result.getData().getMessages().get(0).getInterviewId());
    }
}
```

（`AiInterview` 的 import 在本测试里用不到，可删。）

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=SessionControllerTest -DfailIfNoTests=false test`
Expected: FAIL —— 编译不过：`MessageVO` 没有 `interviewId`、`SessionController` 没有 `interviewService` 字段

**Step 3: 最小实现**

3a. `MessageVO` 加字段（放在 `fileid` 之后）：

```java
    /** 面试会话的面试ID（agent_type=interview 时 = fileid；其它类型为 null） */
    private String interviewId;
```

3b. `SessionController.convertToMessageVO` 完整替换：

```java
    /**
     * 将会话记录转换为消息VO，并补齐关联文件信息。
     *
     * <p>面试会话要单独走一条分支：它的 {@code fileid} 存的是 interviewId（见
     * {@link InterviewSessionRecorder}），不是 ai_file_info.file_id —— 拿它去查文件表只会白查一次。
     * 文件名直接用 {@code question}（= 录音文件名），前端据此渲染录音 chip，并用 interviewId
     * 去还原进度与报告。
     */
    private MessageVO convertToMessageVO(AiSession session) {
        boolean interview = InterviewSessionRecorder.AGENT_TYPE.equals(session.getAgentType());
        AiFileInfo fileInfo = null;
        if (!interview && StringUtils.hasText(session.getFileid())) {
            fileInfo = aiFileInfoMapper.selectOne(new LambdaQueryWrapper<AiFileInfo>()
                    .eq(AiFileInfo::getFileId, session.getFileid())
                    .last("LIMIT 1"));
        }

        return MessageVO.builder()
                .id(session.getId())
                .question(session.getQuestion())
                .answer(session.getAnswer())
                .thinking(session.getThinking())
                .tools(session.getTools())
                .reference(session.getReference())
                .createTime(session.getCreateTime())
                .fileid(session.getFileid())
                .interviewId(interview ? session.getFileid() : null)
                .fileName(interview ? session.getQuestion() : (fileInfo != null ? fileInfo.getFileName() : null))
                .fileType(fileInfo != null ? fileInfo.getFileType() : null)
                .fileSize(fileInfo != null ? fileInfo.getFileSize() : null)
                .recommend(session.getRecommend())
                .build();
    }
```

新增 import：

```java
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
```

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=SessionControllerTest -DfailIfNoTests=false test`
Expected: PASS（2 个用例）

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/entity/vo/MessageVO.java \
        dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/controller/SessionControllerTest.java
git commit -m "feat(session): 会话详情为面试消息补 interviewId 与文件名"
```

---

## Task 5: 修掉会话列表的分页去重缺陷

**Files:**
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/controller/SessionControllerTest.java`（续写）

**为什么必须在本需求里修**：原实现是"先 `page()` 再按 `sessionId` 去重"，`total` 也来自错误的去重结果。以前一个会话在短时间内只有一行，缺陷看不出来；本需求让一个会话在一次上传里就产生一行、并且 `update_time desc` 排序把它们顶到第一页，缺陷会立刻变成"列表里会话变少、总数不对"。

**Step 1: 写失败测试**

```java
    @Test
    @DisplayName("会话列表：每个会话只取一行（SQL 分组），total 来自分页结果")
    @SuppressWarnings("unchecked")
    void getSessionList_oneRowPerSession() {
        AiSession first = interviewRow();
        AiSession second = interviewRow();
        second.setId(9L);
        second.setSessionId("conv-3");
        second.setUpdateTime(LocalDateTime.now());

        Page<AiSession> page = new Page<>(1, 10);
        page.setRecords(List.of(second, first));
        page.setTotal(2);
        when(sessionService.page(any(Page.class), any())).thenReturn(page);

        BaseResult<PageResult<SessionListVO>> result = controller.getSessionList(1, 10, null);

        assertEquals(2, result.getData().getRecords().size());
        assertEquals(2L, result.getData().getTotal());

        ArgumentCaptor<Wrapper<AiSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).page(any(Page.class), captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("MAX(id)"), "应在 SQL 里去重，实际: " + sql);
        assertTrue(sql.toLowerCase().contains("group by session_id"), "应按会话分组，实际: " + sql);
    }
```

（本用例需要新增 import：`com.baomidou.mybatisplus.core.conditions.Wrapper`、`com.baomidou.mybatisplus.extension.plugins.pagination.Page`、`com.dobao.dobaobackend.entity.vo.PageResult`、`com.dobao.dobaobackend.entity.vo.SessionListVO`、`org.mockito.ArgumentCaptor`、`org.junit.jupiter.api.Assertions.assertTrue`、`org.mockito.Mockito.verify`。）

同时给 `SessionControllerTest` 补一个 `@BeforeAll`（**否则 `getTargetSql()` 会抛 `can not find lambda cache for this entity [AiSession]`**，这是 Task 1 实测踩过的坑）：

```java
    @BeforeAll
    static void initTableInfo() {
        // LambdaQueryWrapper 要把 AiSession::getId 解析成列名，依赖 MyBatis-Plus 的 TableInfo，
        // 而单测没有 Spring 上下文，所以这里只注册元数据（不连库）
        MybatisPlusTableInfo.ensure(AiSession.class);
    }
```

所需 import：`org.junit.jupiter.api.BeforeAll`、`com.dobao.dobaobackend.testsupport.MybatisPlusTableInfo`。

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=SessionControllerTest -DfailIfNoTests=false test`
Expected: FAIL —— `getTargetSql()` 里没有 `MAX(id)`（当前是纯 `ORDER BY update_time DESC`）

**Step 3: 最小实现**

`SessionController.getSessionList` 的查询与结果组装段替换为：

```java
        try {
            // 一个会话只保留最新一行：先在 SQL 里挑出每个 session_id 的最大 id，再交给分页。
            // 原实现是"先分页、再在内存里去重"，同一会话有多行时会挤占首页、total 也是错的
            // （面试总结一次上传就会给一个会话新增一行，这个缺陷藏不住了）。
            LambdaQueryWrapper<AiSession> queryWrapper = new LambdaQueryWrapper<AiSession>()
                    .inSql(AiSession::getId, "SELECT MAX(id) FROM ai_session GROUP BY session_id")
                    .orderByDesc(AiSession::getUpdateTime);
            if (StringUtils.hasText(agentType)) {
                queryWrapper.eq(AiSession::getAgentType, agentType);
            }

            Page<AiSession> page = new Page<>(pageNum, pageSize);
            Page<AiSession> resultPage = aiSessionService.page(page, queryWrapper);

            List<SessionListVO> sessionList = resultPage.getRecords().stream()
                    .map(session -> SessionListVO.fromAiSession(session, null))
                    .collect(Collectors.toList());

            PageResult<SessionListVO> pageResult = PageResult.<SessionListVO>builder()
                    .pageNum(pageNum)
                    .pageSize(pageSize)
                    .total(resultPage.getTotal())
                    .records(sessionList)
                    .build();

            return BaseResult.newSuccess(pageResult);

        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return BaseResult.newError("获取会话列表失败: " + e.getMessage());
        }
```

清理：删掉不再使用的 `import java.util.Map;`。

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=SessionControllerTest -DfailIfNoTests=false test`
Expected: PASS（3 个用例）

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/controller/SessionControllerTest.java
git commit -m "fix(session): 会话列表按会话去重后再分页，修掉重复行与 total 错误"
```

---

## Task 6: 删除会话时级联清理面试记录与 MinIO 对象

**Files:**
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewService.java`
- Modify: `dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java`
- Test: `dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewServiceTest.java`（续写）、`SessionControllerTest.java`（续写）

**Step 1: 写失败测试**

`InterviewServiceTest` 续写：

```java
    private AiInterview interviewRecord() {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");
        record.setFileId("file-1");
        record.setFileName("interView.m4a");
        record.setReportFileName("interview-report-iv-1.md");
        return record;
    }

    @Test
    @DisplayName("级联删除：删掉报告与音频对象，再删 ai_interview 记录")
    void deleteInterviews_removesObjectsAndRecords() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(interviewRecord());

        service.deleteInterviews(List.of("iv-1"));

        // 报告对象名来自 report_file_name；音频对象名由 file_id + 扩展名重新拼出来
        verify(minioService).deleteFile("interview-report-iv-1.md");
        verify(minioService).deleteFile("file-file1.m4a");
        verify(interviewMapper).delete(any());
    }

    @Test
    @DisplayName("级联删除：对象删失败必须抛出（库不能先删），让事务整体回滚")
    void deleteInterviews_minioFailure_propagates() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(interviewRecord());
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(minioService).deleteFile(anyString());

        assertThrows(IllegalStateException.class, () -> service.deleteInterviews(List.of("iv-1")));
        verify(interviewMapper, never()).delete(any());
    }

    @Test
    @DisplayName("级联删除：interviewId 查不到记录时是空操作，不碰 MinIO")
    void deleteInterviews_unknownId_isNoOp() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(null);

        service.deleteInterviews(List.of("iv-x"));

        verify(minioService, never()).deleteFile(anyString());
        verify(interviewMapper).delete(any());
    }
```

（`InterviewServiceTest` 需补 import：`com.dobao.dobaobackend.entity.AiInterview`、`java.util.List`、`org.junit.jupiter.api.Assertions.assertThrows`。）

`SessionControllerTest` 续写：

```java
    @Test
    @DisplayName("删除会话：把会话里的 interviewId 交给面试服务级联清理")
    void deleteSession_cascadesInterviews() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(interviewRow()));

        BaseResult<String> result = controller.deleteSession("conv-1");

        assertEquals(200, result.getCode());
        verify(interviewService).deleteInterviews(List.of("iv-1"));
        verify(sessionService).remove(any());
    }

    @Test
    @DisplayName("删除不存在的会话：不触发面试清理")
    void deleteSession_missingSession_doesNothing() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of());

        BaseResult<String> result = controller.deleteSession("conv-x");

        assertEquals(500, result.getCode());
        verifyNoInteractions(interviewService);
    }
```

**Step 2: 跑测试，确认失败**

Command: `mvn -q -Dtest=InterviewServiceTest+SessionControllerTest -DfailIfNoTests=false test`
Expected: FAIL —— `InterviewService.deleteInterviews` 不存在；`SessionController.deleteSession` 没调它

**Step 3: 最小实现**

3a. `InterviewService` 新增方法（放在 `retry` 之后）：

```java
    /**
     * 级联删除若干场面试：<b>先删 MinIO 对象，再删 ai_interview 记录</b>。
     *
     * <p>顺序是刻意的：对象删除失败就抛异常、由调用方（删除会话）的事务整体回滚，
     * 用户看到"删除失败"；而 MinIO 的 removeObject 对不存在的 key 是幂等的，
     * 所以"对象已删、事务回滚"之后再重试一次就能收敛。反过来先删库，则可能出现
     * "记录没了、录音还在 MinIO 里"这种既不可见又删不掉的状态。
     *
     * @param interviewIds 面试ID（会话行里 agent_type=interview 的 fileid）；可为空
     */
    public void deleteInterviews(List<String> interviewIds) {
        if (interviewIds == null || interviewIds.isEmpty()) {
            return;
        }
        List<String> objectNames = new ArrayList<>();
        for (String interviewId : interviewIds) {
            AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                    .eq(AiInterview::getInterviewId, interviewId));
            if (record == null) {
                continue;
            }
            if (StringUtils.hasText(record.getReportFileName())) {
                objectNames.add(record.getReportFileName());
            }
            if (StringUtils.hasText(record.getFileId()) && StringUtils.hasText(record.getFileName())) {
                objectNames.add(FileManageService.generateObjectName(
                        record.getFileId(), extractFileType(record.getFileName())));
            }
        }

        for (String objectName : objectNames) {
            try {
                minioService.deleteFile(objectName);
            } catch (Exception e) {
                throw new IllegalStateException("删除面试文件失败: " + objectName + ", " + e.getMessage(), e);
            }
        }

        interviewMapper.delete(new LambdaQueryWrapper<AiInterview>()
                .in(AiInterview::getInterviewId, interviewIds));
        log.info("面试记录已删除: interviewIds={}, 对象数={}", interviewIds, objectNames.size());
    }
```

补 import：`java.util.ArrayList`、`java.util.List`（若尚未导入）。

3b. `SessionController`：加字段 + 改 `deleteSession`。

字段（放在 `private AiPptInstMapper aiPptInstMapper;` 之后）：

```java
    @Autowired
    private InterviewService interviewService;
```

`deleteSession` 完整替换：

```java
    /**
     * 删除会话及其关联的文件记录、PPT实例记录、面试记录
     *
     * <p>面试记录连带 MinIO 上的录音与报告一起删：录音是敏感数据，
     * 会话没了却还能通过直链取到内容，是不一致、也是隐私问题。
     */
    @DeleteMapping("/{conversationId}")
    @Operation(summary = "删除会话", description = "删除会话及其关联数据")
    @Transactional(rollbackFor = Exception.class)
    public BaseResult<String> deleteSession(@PathVariable String conversationId) {
        log.info("删除会话: conversationId={}", conversationId);

        try {
            LambdaQueryWrapper<AiSession> sessionQuery = new LambdaQueryWrapper<AiSession>()
                    .eq(AiSession::getSessionId, conversationId);
            List<AiSession> sessions = aiSessionService.list(sessionQuery);

            if (sessions.isEmpty()) {
                return BaseResult.newError("会话不存在");
            }

            // 面试会话：fileid 即 interviewId（见 InterviewSessionRecorder）
            List<String> interviewIds = sessions.stream()
                    .filter(session -> InterviewSessionRecorder.AGENT_TYPE.equals(session.getAgentType()))
                    .map(AiSession::getFileid)
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());

            // 先删面试（对象 + ai_interview）：失败即抛，整个事务回滚
            interviewService.deleteInterviews(interviewIds);

            // 删除会话关联的文件记录
            LambdaQueryWrapper<AiFileInfo> fileQuery = new LambdaQueryWrapper<AiFileInfo>()
                    .eq(AiFileInfo::getConversationId, conversationId);
            aiFileInfoMapper.delete(fileQuery);

            // 删除会话关联的PPT实例记录
            LambdaQueryWrapper<AiPptInst> pptQuery = new LambdaQueryWrapper<AiPptInst>()
                    .eq(AiPptInst::getConversationId, conversationId);
            aiPptInstMapper.delete(pptQuery);

            // 删除会话消息记录
            aiSessionService.remove(sessionQuery);

            return BaseResult.newSuccess("会话删除成功");

        } catch (Exception e) {
            log.error("删除会话失败: conversationId={}", conversationId, e);
            return BaseResult.newError("删除会话失败: " + e.getMessage());
        }
    }
```

补 import：`com.dobao.dobaobackend.service.InterviewService`（`Collectors`/`List` 已有）。

**Step 4: 跑测试，确认通过**

Command: `mvn -q -Dtest=InterviewServiceTest+SessionControllerTest -DfailIfNoTests=false test`
Expected: PASS

**Step 5: 提交**

```bash
git add dobao-backend/src/main/java/com/dobao/dobaobackend/service/InterviewService.java \
        dobao-backend/src/main/java/com/dobao/dobaobackend/controller/SessionController.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/service/InterviewServiceTest.java \
        dobao-backend/src/test/java/com/dobao/dobaobackend/controller/SessionControllerTest.java
git commit -m "feat(interview): 删除会话时级联清理面试记录与 MinIO 对象"
```

**Step 6: 全量后端测试**

Command: `mvn test`（在 `dobao-backend` 下）
Expected: 全部通过；若 `InterviewReportRendererTest` 等既有用例失败，先确认失败是否由本任务引入（`git stash` 对照）。

---

## Task 7: 前端 —— 上传时带上 conversationId

**Files:**
- Modify: `dobao-front/src/api/index.ts`
- Modify: `dobao-front/src/composables/useInterview.ts`
- Modify: `dobao-front/src/composables/useChat.ts`

**Step 1: 改 `api/index.ts` 的 `uploadInterviewAudio`（完整替换）**

```ts
/**
 * 上传面试录音。
 *
 * 注意这里是**普通请求**而不是流式：multipart 落 MinIO 本身就要 1~3 秒，
 * 上传成功后前端立刻去开 SSE 流（见 `streamInterview`）。
 *
 * `conversationId` 是"这场面试属于哪个会话"的唯一凭据：带上它，后端才会在
 * `ai_session` 里写一行，刷新/换设备后仍能从会话列表找到并还原这场面试。
 */
export const uploadInterviewAudio = async (
  backendUrl: string,
  file: File,
  conversationId?: string | null,
  signal?: AbortSignal
): Promise<InterviewUploadVo> => {
  const formData = new FormData()
  formData.append('file', file)
  const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : ''

  const response = await fetch(`${backendUrl}/interview/upload${query}`, {
    method: 'POST',
    body: formData,
    signal
  })
  if (!response.ok) {
    throw new Error(`音频上传失败（HTTP ${response.status}）`)
  }
  const result = (await response.json()) as ApiResult<InterviewUploadVo>
  return unwrap(result, '音频上传失败')
}
```

**Step 2: 改 `useInterview.ts` 的 `start`**

```ts
  /**
   * 上传音频并开始处理，进度写进调用方给的 session。
   *
   * @param session 这条面试消息自己的 session（就地修改，不替换）
   * @param conversationId 当前会话ID：后端据此把这场面试写进 ai_session（历史可见/可还原）
   * @throws Error 前端校验不通过、或上传失败（调用方负责提示用户）
   */
  const start = async (file: File, session: InterviewSession, conversationId?: string | null) => {
```

并把方法内的上传调用改为：

```ts
      uploaded = await uploadInterviewAudio(backendUrl.value, file, conversationId, abortController.signal)
```

**Step 3: 改 `useChat.ts` 的 `startInterview`**

```ts
    await interview.start(file, session, currentChatId.value)
```

**Step 4: 验证**

Command: `npm run type-check`（在 `dobao-front` 下）
Expected: 通过、无类型错误

**Step 5: 提交**

```bash
git add dobao-front/src/api/index.ts dobao-front/src/composables/useInterview.ts dobao-front/src/composables/useChat.ts
git commit -m "feat(interview): 上传面试录音时带上 conversationId"
```

---

## Task 8: 前端 —— 打开历史会话时还原面试面板

**Files:**
- Modify: `dobao-front/src/types/index.ts`
- Modify: `dobao-front/src/composables/useInterview.ts`
- Modify: `dobao-front/src/composables/useChat.ts`

**Step 1: `types/index.ts` 的 `SessionMessage` 补字段**

```ts
  /** 面试会话的面试ID（后端在 agent_type=interview 的会话行上填 = fileid） */
  interviewId?: string
```

**Step 2: `useInterview.ts` 导出终态判断**（放在 `TERMINAL_STATUSES` 定义之后，模块作用域）

```ts
/**
 * 是不是终态（READY / FAILED）。
 *
 * <p>历史回放时用它决定"要不要再订阅进度流"：已经跑完或已经失败的场次
 * 只需要一次状态拉取，不该为每条历史消息都开一条 SSE。
 */
export const isTerminalInterviewStatus = (status: string): boolean => TERMINAL_STATUSES.includes(status)
```

**Step 3: `useChat.ts` 的 `selectChat` 消息回放段（完整替换 `if (sessionData)` 块）**

先补 import：

```ts
import { useInterview, createInterviewSession, isTerminalInterviewStatus } from './useInterview'
```

（`useChat.ts` 现在从 `./useInterview` 引入了哪些符号，请按现状合并，不要重复 import 语句。）

替换后的回放块：

```ts
    const sessionData = await apiGetChatDetail(backendUrl.value, chatId)
    if (sessionData) {
      const target = chatList.value.find(c => c.id === chatId)
      if (target) {
        target.agentType = sessionData.agentType
        target.fileid = sessionData.fileid
        target.messages = []
        const isInterviewSession = sessionData.agentType === 'interview'

        if (sessionData.messages && Array.isArray(sessionData.messages)) {
          for (const msg of sessionData.messages) {
            if (msg.question) {
              target.messages.push({
                id: 'user_' + msg.id,
                role: 'user',
                // 面试会话的 question 是录音文件名（侧边栏标题要它），
                // 但用户当时发出去的文案是固定的这一句，回放时保持与实时一致；
                // 文件名交给下面的录音 chip 呈现
                content: isInterviewSession ? '请总结这段面试录音' : msg.question,
                file: !!msg.fileid,
                fileName: msg.fileid ? (msg.fileName || '已上传文件') : null,
                thinking: [],
                reference: [],
                recommend: [],
                showThinking: false,
                showReference: false,
                hasThinking: false,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }

            // 面试会话：AI 气泡挂进度面板，按 interviewId 把状态与报告还原回来
            if (isInterviewSession && msg.interviewId) {
              const interviewMsg: Message = {
                id: 'assistant_' + msg.id,
                role: 'assistant',
                content: '',
                thinking: [],
                reference: [],
                recommend: [],
                showThinking: false,
                showReference: false,
                hasThinking: false,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now(),
                interview: reactive(createInterviewSession())
              }
              target.messages.push(interviewMsg)
              const interviewSession = interviewMsg.interview!
              interviewSession.fileName = msg.fileName || msg.question || null
              // 顺序执行：先拿一次状态（终态会顺带把报告正文拉回来）
              await interview.tryRestore(msg.interviewId, interviewSession)
              continue
            }

            // 面试会话没有 interviewId（历史脏数据）：退化成一条普通回答，别丢内容
            if (isInterviewSession) {
              if (msg.answer || msg.thinking) {
                target.messages.push({
                  id: 'assistant_' + msg.id,
                  role: 'assistant',
                  content: msg.answer || '',
                  thinking: msg.thinking ? [msg.thinking] : [],
                  reference: [],
                  recommend: [],
                  showThinking: !!msg.thinking,
                  showReference: false,
                  hasThinking: !!msg.thinking,
                  timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
                })
              }
              continue
            }

            if (msg.answer || msg.thinking) {
              const reference = processReferences(msg.reference)
              target.messages.push({
                id: 'assistant_' + msg.id,
                role: 'assistant',
                content: msg.answer || '',
                thinking: msg.thinking ? [msg.thinking] : [],
                reference,
                recommend: [],
                // 有思考过程时默认展开
                showThinking: !!msg.thinking,
                showReference: false,
                hasThinking: !!msg.thinking,
                timestamp: msg.createTime ? new Date(msg.createTime).getTime() : Date.now()
              })
            }
          }

          // 回放结束后，只给"最新一场仍在处理的面试"续订进度流：
          // 本 composable 的流与轮询都是单句柄（stop()/pollTimer 共享），
          // 同一会话同时维持多条流会互相打断 —— 这与实时交互的既有约束一致。
          if (isInterviewSession) {
            const pending = target.messages.filter(
              m => m.interview && !isTerminalInterviewStatus(m.interview.status)
            )
            const latest = pending[pending.length - 1]
            if (latest && latest.interview?.interviewId) {
              void interview.startById(latest.interview.interviewId, latest.interview, latest.interview.fileName)
            }
          }
        }

        // 面试会话的标题由列表接口给出（question=录音文件名），不要用气泡文案覆盖
        if (!isInterviewSession) {
          const firstUserMessage = target.messages.find(m => m.role === 'user')
          if (firstUserMessage && firstUserMessage.content) {
            target.title = firstUserMessage.content.substring(0, 20) + (firstUserMessage.content.length > 20 ? '...' : '')
          }
        }
      }
    }
```

注意事项：

- `selectChat` 的循环里出现 `await`，方法本身已是 `async` ✓。
- `msg.interviewId` 来自 Task 4 的后端改动；`SessionMessage` 类型已在 Step 1 补上 ✓。
- `latest.interview.fileName` 是 `string | null`，与 `startById` 的第三个参数一致 ✓。

**Step 4: 验证**

Command: `npm run type-check`（在 `dobao-front` 下）
Expected: 通过、无类型错误

**Step 5: 提交**

```bash
git add dobao-front/src/types/index.ts dobao-front/src/composables/useInterview.ts dobao-front/src/composables/useChat.ts
git commit -m "feat(interview): 会话历史回放时还原面试进度与报告"
```

---

## Task 9: 端到端手工验收

**Files:** 无（这一步不改代码）

**前置：** 仓库根目录就有可用的测试录音 `intervi2.m4a`（以及 `interView.m4a`）。

**Step 1: 起后端与前端**

```bash
# 终端 1
cd dobao-backend && mvn spring-boot:run
# 终端 2
cd dobao-front && npm run dev
```

**Step 2: 按设计文档 §9 的 8 条验收标准逐条走**

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 新建对话 → 选录音 → 勾选确认 → 开始总结 → **立刻刷新页面** | 侧边栏出现以文件名命名的会话；点开能看到进行中的进度并继续接收进度 |
| 2 | 等报告就绪后刷新 | 点开该会话直接渲染完整报告 + 下载按钮可用 |
| 3 | 换一个浏览器（或用无痕窗口，确保没有 `localStorage`）打开 | 第 1、2 条依然成立 |
| 4 | 同一会话里再跑一场面试（换一段录音） | 详情按顺序回放两个面板，各自报告不串台 |
| 5 | 同一段录音在两个不同会话里各上传一次 | 两边都能看到并可还原（幂等命中同一场分析） |
| 6 | 制造一次失败（例如传一段损坏/超长音频），再点重试 | 列表可见、详情有失败提示与重试按钮；重试成功后摘要被覆盖 |
| 7 | 删除该面试会话 | 列表里消失；库里 `ai_session`、`ai_interview` 均无记录；MinIO 里录音与报告对象已被删除 |
| 8 | 打开一个 chat 会话与一个 PPT 会话 | 列表标题、详情回放、删除行为与改动前一致 |

**Step 3: 接口侧的快速核对（可选，便于定位）**

```bash
curl "http://<backend>/session/list?pageNum=1&pageSize=10"
curl "http://<backend>/session/<conversationId>"
```

期望：面试会话在列表里每个 `sessionId` 只出现一次；详情里面试消息带 `interviewId` 与 `fileName`。

**Step 4: 汇报**

把每一条的「通过 / 不通过 + 现象」写进本文件末尾的「验收记录」，不通过的要给出可复现步骤与后端日志片段。

---

## 验收记录（Task 9 执行后填写）

| # | 结果 | 现象 / 备注 |
|---|---|---|
| 1 | | |
| 2 | | |
| 3 | | |
| 4 | | |
| 5 | | |
| 6 | | |
| 7 | | |
| 8 | | |

---

## 收尾（Phase 5：完成分支）

1. `mvn test` 全绿、`npm run type-check` 通过、Task 9 八条验收全过。
2. 确认 `git status` 里**没有**把无关改动一起提交（工作区里有大量既有未提交改动）。
3. 由用户选择：本地合并 / 推送开 PR / 保留分支 / 丢弃。
