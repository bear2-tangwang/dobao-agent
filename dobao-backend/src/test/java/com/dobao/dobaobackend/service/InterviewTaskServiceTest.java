package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewReportGenerator;
import com.dobao.dobaobackend.interview.InterviewReportRenderer;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.interview.QaListBuilder;
import com.dobao.dobaobackend.interview.SpeakerRoleResolver;
import com.dobao.dobaobackend.interview.TranscriptNormalizer;
import com.dobao.dobaobackend.interview.asr.DashScopeAsrClient;
import com.dobao.dobaobackend.interview.asr.dto.AsrTaskState;
import com.dobao.dobaobackend.interview.audio.AudioUrlProvider;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
import com.dobao.dobaobackend.interview.progress.InterviewProgressHub;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.dobao.dobaobackend.testsupport.MybatisPlusTableInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 面试状态机 → 会话摘要的回填，只覆盖三个汇合点：报告就绪（{@code publishReport} → {@code markReady}）、
 * 失败（{@code updateStatus} → {@code markFailed}）、开始/重试（{@code TRANSCRIBING}/{@code ANALYZING} →
 * {@code markRunning}）。被测算在 {@link #setUp()} 里显式 new，用不到的依赖也传具名 mock 而非 {@code null}。
 */
@ExtendWith(MockitoExtension.class)
class InterviewTaskServiceTest {

    /**
     * 真实配置对象（普通实例，不是 {@code @Spy}：这里不需要 Mockito 代理）。
     *
     * <p>{@code pollTimeoutMs} 在 {@link #setUp()} 里显式设成 30 分钟：超时文案里那句
     * "超过 30 分钟"就是这个值算出来的，显式设置让这层耦合摆在明面上，不依赖生产默认值。
     */
    private final InterviewProperties properties = new InterviewProperties();

    @Mock
    private AiInterviewMapper interviewMapper;
    @Mock
    private AudioUrlProvider audioUrlProvider;
    @Mock
    private DashScopeAsrClient asrClient;
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
    // 下面 4 个依赖本测试的路径都用不到，但仍声明成具名 mock 并显式传入：传字面量 null 的话，
    // 构造器一旦换序（同类型之间）或插入同类型参数，编译期不会有任何提示，错误会留到运行期。
    @Mock
    private TranscriptNormalizer transcriptNormalizer;
    @Mock
    private SpeakerRoleResolver speakerRoleResolver;
    @Mock
    private QaListBuilder qaListBuilder;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private InterviewTaskService taskService;

    @BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 把 AiInterview::getInterviewId 解析成列名依赖 MyBatis-Plus 的
        // TableInfo，而单测没有 Spring 上下文，所以这里只注册元数据（不连库）
        MybatisPlusTableInfo.ensure(AiInterview.class);
    }

    @BeforeEach
    void setUp() {
        // 超过 30 分钟即超时（1800000 ms），与 pollTranscribingTasks_timeout_marksFailed 的文案断言对应
        properties.getAsr().setPollTimeoutMs(1_800_000L);
        // 顺序 = InterviewTaskService 的字段声明顺序（Lombok @RequiredArgsConstructor）：
        // 14 个实参全部具名，换序或增删参数都会编译失败，不会静默错配
        taskService = new InterviewTaskService(properties, interviewMapper, audioUrlProvider, asrClient,
                transcriptNormalizer, speakerRoleResolver, qaListBuilder, reportGenerator, reportRenderer,
                minioService, eventPublisher, progressHub, objectMapper, sessionRecorder);
    }

    @Test
    @DisplayName("报告就绪：落库 READY + 报告三件套，并回填完成摘要（问答数 / 参考回答数）")
    void publishReport_marksReady() throws Exception {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");

        String reportJson = "{\"interviewId\":\"iv-1\"}";
        // 问答 2 条、参考回答 1 条且条数不同：这样"计数真的数了"与"两个实参没写反"才被断言住；
        // 两者都是空表的话，把实现里的 size() 换成硬编码 0 也能全绿。
        List<QaItem> qaList = List.of(
                new QaItem("Q001", "什么是索引下推？", 0L, "把过滤条件下推到存储引擎…", 1000L, 5000L),
                new QaItem("Q002", "介绍一下 MVCC", 6000L, "多版本并发控制…", 7000L, 12000L));
        List<ReferenceAnswer> referenceAnswers = List.of(
                new ReferenceAnswer("Q001", "什么是索引下推？", "在 InnoDB 里把 WHERE 条件下推到引擎层过滤。"));
        InterviewReport report = new InterviewReport("iv-1", 1000L, LocalDateTime.now(),
                qaList, referenceAnswers, new ReportSummary(List.of(), List.of(), "总结"));
        when(reportGenerator.generate(eq(record), any())).thenReturn(report);
        when(reportRenderer.render(report)).thenReturn("# 报告");
        when(objectMapper.writeValueAsString(report)).thenReturn(reportJson);
        when(minioService.uploadFile(anyString(), any(), anyString())).thenReturn("http://minio/report.md");

        taskService.publishReport(record, List.of());

        // 整段落库 update 被删掉必须让本用例变红
        Wrapper<AiInterview> wrapper = captureUpdate();
        String set = setClause(wrapper);
        assertTrue(set.contains("status"), "SET 必须把状态推到 READY，实际 SET: " + set);
        assertTrue(set.contains("report_json"), "SET 必须落报告 JSON，实际 SET: " + set);
        assertTrue(set.contains("report_file_url"), "SET 必须落报告下载地址，实际 SET: " + set);
        assertTrue(set.contains("report_file_name"), "SET 必须落报告文件名，实际 SET: " + set);
        assertEquals(InterviewStatus.READY.name(), boundValue(wrapper, "status"),
                "落库状态必须是 READY，实际 SET: " + set);
        assertEquals(reportJson, boundValue(wrapper, "report_json"),
                "落库的必须是渲染报告用的那份 JSON 载荷，实际 SET: " + set);
        assertEquals("http://minio/report.md", boundValue(wrapper, "report_file_url"));
        assertEquals("interview-report-iv-1.md", boundValue(wrapper, "report_file_name"),
                "对象名同时是下载文件名，必须与 interviewId 对应");

        verify(sessionRecorder).markReady("iv-1", 2, 1);
    }

    @Test
    @DisplayName("历史报告：qaList / referenceAnswers 为 null 时按 0 计数，不抛异常")
    void publishReport_nullCollections_marksReadyWithZeros() throws Exception {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");

        // report_json 会长期保存并被反序列化回来，历史报告里这两项确实是 null
        // （见 InterviewReport javadoc）
        InterviewReport legacy = new InterviewReport("iv-1", 1000L, LocalDateTime.now(),
                null, null, new ReportSummary(List.of(), List.of(), "总结"));
        when(reportGenerator.generate(eq(record), any())).thenReturn(legacy);
        when(reportRenderer.render(legacy)).thenReturn("# 报告");
        when(minioService.uploadFile(anyString(), any(), anyString())).thenReturn("http://minio/report.md");

        assertDoesNotThrow(() -> taskService.publishReport(record, List.of()));

        verify(sessionRecorder).markReady("iv-1", 0, 0);
    }

    @Test
    @DisplayName("提交转写（开始/重试入口）：状态转 TRANSCRIBING 时回填'进行中'摘要，不写失败摘要")
    void submitTranscriptionAsync_marksRunning() {
        taskService.submitTranscriptionAsync("iv-1", "file-x.m4a");

        verify(sessionRecorder).markRunning("iv-1");
        verify(sessionRecorder, never()).markFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("先失败后重试：markFailed 之后还会再写一次 markRunning（旧失败文案必须被覆盖）")
    void failedThenRetry_marksRunningAfterFailed() {
        AiInterview timedOut = new AiInterview();
        timedOut.setInterviewId("iv-1");
        // 第一次 selectList = 已超时的那批；第二次 = 尚未超时的那批（空）
        when(interviewMapper.selectList(any())).thenReturn(List.of(timedOut), List.of());

        taskService.pollTranscribingTasks();                        // 超时兜底 → markFailed
        taskService.submitTranscriptionAsync("iv-1", "file-x.m4a"); // 重试 → markRunning

        InOrder order = inOrder(sessionRecorder);
        order.verify(sessionRecorder).markFailed(eq("iv-1"), contains("转写超时"));
        order.verify(sessionRecorder).markRunning("iv-1");
    }

    @Test
    @DisplayName("转写超时：走 updateStatus 的失败路径，会话摘要被标记为失败")
    void pollTranscribingTasks_timeout_marksFailed() {
        AiInterview timedOut = new AiInterview();
        timedOut.setInterviewId("iv-1");
        // 第一次 selectList = 已超时的那批；第二次 = 尚未超时的那批（空）
        when(interviewMapper.selectList(any())).thenReturn(List.of(timedOut), List.of());

        taskService.pollTranscribingTasks();

        // "30 分钟"来自 setUp 里显式设置的 pollTimeoutMs=1800000
        verify(sessionRecorder).markFailed(eq("iv-1"), contains("转写超时（超过 30 分钟"));
        verify(progressHub).publishStatus(eq("iv-1"), eq(InterviewStatus.FAILED), anyString());
    }

    @Test
    @DisplayName("轮询仍在转写的任务：一条摘要都不写（'进行中'在提交时已经写过）")
    void pollTranscribingTasks_stillRunning_noSummaryWrite() {
        AiInterview inProgress = new AiInterview();
        inProgress.setInterviewId("iv-1");
        // 第一次 selectList = 已超时的那批（空）；第二次 = 仍在转写的那批（1 条）
        when(interviewMapper.selectList(any())).thenReturn(List.of(), List.of(inProgress));
        // 查询结果是 RUNNING（既非成功也非失败）→ pollOne 直接返回，不动状态
        when(asrClient.query(any())).thenReturn(new AsrTaskState("RUNNING", null, null));

        taskService.pollTranscribingTasks();

        verify(sessionRecorder, never()).markFailed(anyString(), anyString());
        verify(sessionRecorder, never()).markRunning(anyString());
        verify(sessionRecorder, never()).markReady(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("回填会话摘要失败时不能把已就绪的面试翻成失败，报告仍照常推给前端")
    void publishReport_sessionWriteFailure_isSwallowed() throws Exception {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");
        InterviewReport report = new InterviewReport("iv-1", 1000L, LocalDateTime.now(),
                List.of(), List.of(), new ReportSummary(List.of(), List.of(), "总结"));
        when(reportGenerator.generate(eq(record), any())).thenReturn(report);
        when(reportRenderer.render(report)).thenReturn("# 报告");
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(minioService.uploadFile(anyString(), any(), anyString())).thenReturn("http://minio/report.md");
        // 会话表写失败：不能影响面试本身
        doThrow(new RuntimeException("db down"))
                .when(sessionRecorder).markReady(anyString(), anyInt(), anyInt());

        assertDoesNotThrow(() -> taskService.publishReport(record, List.of()));

        // 报告仍然照常推给前端：第三个参数就是落库/渲染用的那份 JSON 载荷，不是 null
        verify(progressHub).publishComplete(eq("iv-1"), eq("http://minio/report.md"), eq("{}"));
    }

    /** 捕获那一次"落库面试记录"的 wrapper：同时钉住 {@code update(isNull(), wrapper)} 只发生一次 */
    @SuppressWarnings("unchecked")
    private Wrapper<AiInterview> captureUpdate() {
        ArgumentCaptor<Wrapper<AiInterview>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(interviewMapper).update(isNull(), captor.capture());
        return captor.getValue();
    }

    /** UPDATE 的 SET 片段（保留 {@code #{ew.paramNameValuePairs.MPGENVALn}} 占位符） */
    @SuppressWarnings("unchecked")
    private static String setClause(Wrapper<AiInterview> wrapper) {
        assertTrue(wrapper instanceof LambdaUpdateWrapper,
                "落库必须是列级 UPDATE（LambdaUpdateWrapper），实际: " + wrapper.getClass().getName());
        return ((LambdaUpdateWrapper<AiInterview>) wrapper).getSqlSet();
    }

    /** SET + WHERE 的原始片段，用来把某一列绑定的参数值取出来 */
    private static String rawSegments(Wrapper<AiInterview> wrapper) {
        String set = setClause(wrapper);
        String where = wrapper.getSqlSegment();
        return where.contains(set) ? where : set + " " + where;
    }

    /** 取出"SQL 里 {column} = ?"这个占位符实际绑定的参数值 */
    private static Object boundValue(Wrapper<AiInterview> wrapper, String column) {
        String sql = rawSegments(wrapper);
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(column)
                        + "\\s*=\\s*#\\{[A-Za-z0-9_.]*paramNameValuePairs\\.([A-Za-z0-9_]+)}")
                .matcher(sql);
        assertTrue(matcher.find(), "SQL 里应把列 " + column + " 绑定成参数，实际: " + sql);
        return paramValues(wrapper).get(matcher.group(1));
    }

    private static Map<String, Object> paramValues(Wrapper<AiInterview> wrapper) {
        return ((AbstractWrapper<AiInterview, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
