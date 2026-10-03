package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.InterviewUploadVO;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.dobao.dobaobackend.testsupport.MybatisPlusTableInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private AiSessionService sessionService;
    private GithubOAuthProperties authProperties;
    private InterviewService service;

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfo.ensure(AiInterview.class);
    }

    @BeforeEach
    void setUp() {
        // 用真实的 InterviewProperties：里面的上限值就是配置默认值，不需要打桩
        properties = new InterviewProperties();
        interviewMapper = mock(AiInterviewMapper.class);
        fileManageService = mock(FileManageService.class);
        minioService = mock(MinioService.class);
        taskService = mock(InterviewTaskService.class);
        sessionRecorder = mock(InterviewSessionRecorder.class);
        sessionService = mock(AiSessionService.class);
        // 归属用户来自配置：未登录（测试里没有请求上下文）时用它兜底，
        // 断言里也用这个值，避免测试写死在 "default" 字面量上
        authProperties = new GithubOAuthProperties();
        authProperties.setDefaultUserId("u-default");
        service = new InterviewService(properties, interviewMapper, fileManageService,
                minioService, taskService, new ObjectMapper(), sessionRecorder, sessionService, authProperties);
    }

    private MultipartFile audio() {
        return new MockMultipartFile("file", "interView.m4a", "audio/m4a",
                "fake-audio".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("新上传：写会话行（question=文件名、fileid=interviewId），并提交转写")
    void upload_writesSessionRow() {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(fileManageService.uploadFile(any(), anyString())).thenReturn(FileInfo.builder()
                .fileId("file-1").fileName("interView.m4a").fileType("m4a").fileSize(10L).build());

        InterviewUploadVO uploaded = service.upload("conv-1", audio());

        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> interviewId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fileName = ArgumentCaptor.forClass(String.class);
        verify(sessionRecorder).recordUploaded(conversationId.capture(), interviewId.capture(), fileName.capture(), anyString());
        assertEquals("conv-1", conversationId.getValue());
        assertEquals(uploaded.interviewId(), interviewId.getValue());
        assertEquals("interView.m4a", fileName.getValue());

        // 快路径的核心副作用是"落 ai_interview"这一行：光断言返回值和会话行，
        // 删掉 insert 也照样绿（存活变异体），所以这里必须把入库的记录本身钉住。
        ArgumentCaptor<AiInterview> recordCaptor = ArgumentCaptor.forClass(AiInterview.class);
        verify(interviewMapper).insert(recordCaptor.capture());
        AiInterview saved = recordCaptor.getValue();
        assertEquals("interView.m4a", saved.getFileName());
        assertNotNull(saved.getInterviewId(), "interviewId 由应用侧生成，缺了后续所有接口都查不到记录");
        assertEquals("file-1", saved.getFileId());
        assertEquals(InterviewStatus.UPLOADED.name(), saved.getStatus());
        assertNotNull(saved.getAudioHash(), "audioHash 是音频内容 SHA-256，重复上传去重全靠它");

        // 期望的 objectName 用同一个静态方法算，避免文件命名规则一变测试就假红
        String expectedObjectName = FileManageService.generateObjectName("file-1", "m4a");
        verify(taskService).submitTranscriptionAsync(eq(uploaded.interviewId()), eq(expectedObjectName));
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
        verify(fileManageService, never()).uploadFile(any(), anyString());
        verify(sessionRecorder).recordUploaded(eq("conv-2"), eq("iv-9"), eq("interView.m4a"), anyString());
        verify(taskService, never()).submitTranscriptionAsync(anyString(), anyString());
    }

    @Test
    @DisplayName("幂等命中 READY 记录：按报告回填完成摘要（复用不会再走状态机，不补就永远停在占位文案）")
    void upload_duplicateHit_readyRecord_backfillsReadySummary() throws Exception {
        AiInterview existing = new AiInterview();
        existing.setInterviewId("iv-9");
        existing.setStatus(InterviewStatus.READY.name());
        existing.setFileName("interView.m4a");
        existing.setFileId("file-9");
        // 用真实 ObjectMapper 序列化一份报告：report_json 在库里就是这个形态，
        // 手写 JSON 字符串会与 record 结构脱节（字段改名后测试仍然"绿"）
        InterviewReport report = new InterviewReport("iv-9", 1000L, null,
                List.of(new QaItem("Q001", "什么是索引下推？", 0L, "把过滤条件下推到引擎层…", 1000L, 5000L),
                        new QaItem("Q002", "介绍一下 MVCC", 6000L, "多版本并发控制…", 7000L, 12000L)),
                List.of(new ReferenceAnswer("Q001", "什么是索引下推？", "在 InnoDB 里下推到引擎层过滤。")),
                null);
        existing.setReportJson(new ObjectMapper().writeValueAsString(report));
        when(interviewMapper.selectOne(any())).thenReturn(existing);

        service.upload("conv-2", audio());

        verify(sessionRecorder).markReady("iv-9", 2, 1);
        verify(sessionRecorder, never()).markRunning(anyString());
    }

    @Test
    @DisplayName("幂等命中处理中记录（TRANSCRIBING）：按当前状态回填'进行中'摘要")
    void upload_duplicateHit_transcribingRecord_marksRunning() {
        AiInterview existing = new AiInterview();
        existing.setInterviewId("iv-9");
        existing.setStatus(InterviewStatus.TRANSCRIBING.name());
        existing.setFileName("interView.m4a");
        existing.setFileId("file-9");
        when(interviewMapper.selectOne(any())).thenReturn(existing);

        service.upload("conv-2", audio());

        verify(sessionRecorder).markRunning("iv-9");
        verify(sessionRecorder, never()).markReady(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("幂等命中 READY 但报告 JSON 解析不了：按无计数回填完成摘要，不能停在'进行中'")
    void upload_duplicateHit_readyRecordWithBrokenReport_stillMarksReady() {
        AiInterview existing = new AiInterview();
        existing.setInterviewId("iv-9");
        existing.setStatus(InterviewStatus.READY.name());
        existing.setFileName("interView.m4a");
        existing.setFileId("file-9");
        existing.setReportJson("{ 这不是合法 JSON");
        when(interviewMapper.selectOne(any())).thenReturn(existing);

        service.upload("conv-2", audio());

        verify(sessionRecorder).markReady("iv-9", 0, 0);
    }

    @Test
    @DisplayName("查别人的面试：按 interviewId + user_id 查询命中 0 行时按'不存在'拒绝")
    void status_otherUsersInterview_isRejected() {
        // 归属被写进 WHERE，别人的记录查不到 → selectOne 返回 null
        when(interviewMapper.selectOne(any())).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.status("other-iv"));

        assertTrue(error.getMessage().contains("不存在"),
                "越权要按'不存在'回应（不回 403 以免泄露该 ID 真实存在），实际: " + error.getMessage());
        assertTrue(currentUserFilterApplied(),
                "查询语句里必须带 user_id 条件，否则归属校验形同虚设");
    }

    /**
     * 断言最近一次 selectOne 的 wrapper 里确实带了 user_id 条件与当前用户值。
     *
     * <p>用 wrapper 的 SQL 片段 + 绑定参数来钉，不依赖数据库。
     */
    @SuppressWarnings("unchecked")
    private boolean currentUserFilterApplied() {
        ArgumentCaptor<Wrapper<AiInterview>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(interviewMapper).selectOne(captor.capture());
        Wrapper<AiInterview> wrapper = captor.getValue();
        if (!wrapper.getTargetSql().contains("user_id")) {
            return false;
        }
        Map<String, Object> params =
                ((AbstractWrapper<AiInterview, ?, ?>) wrapper).getParamNameValuePairs();
        return params.containsValue(authProperties.getDefaultUserId());
    }

    @ParameterizedTest(name = "[{index}] conversationId=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("conversationId 为 null / 空串 / 纯空白：上传照常成功，值原样透传（由 Recorder 决定不写会话行）")
    void upload_withoutConversationId_stillUploads(String conversationId) {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(fileManageService.uploadFile(any(), anyString())).thenReturn(FileInfo.builder()
                .fileId("file-2").fileName("interView.m4a").fileType("m4a").fileSize(10L).build());

        InterviewUploadVO uploaded = service.upload(conversationId, audio());

        // 退化边界不能连累主路径：记录照落、任务照提交
        assertNotNull(uploaded.interviewId());
        assertEquals(InterviewStatus.UPLOADED.name(), uploaded.status());
        verify(interviewMapper).insert(any(AiInterview.class));
        verify(taskService).submitTranscriptionAsync(eq(uploaded.interviewId()), anyString());
        // 透传原值（controller 在 ?conversationId= 时给的是 ""，"   " 也照样原样传），
        // trim 与"退化为只写 ai_interview"都在 Recorder 内部收口
        verify(sessionRecorder).recordUploaded(eq(conversationId), eq(uploaded.interviewId()),
                eq("interView.m4a"), eq("u-default"));
    }

    /** 一条已完成的面试记录：报告对象 + 录音对象都在 MinIO 里 */
    private AiInterview finishedInterview() {        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");
        record.setReportFileName("interview-report-iv-1.md");
        record.setFileId("file-1");
        record.setFileName("interView.m4a");
        return record;
    }

    @Test
    @DisplayName("级联删除面试：报告对象与录音对象都从 MinIO 删掉，再删 ai_interview 记录")
    void deleteInterviews_removesObjectsThenRows() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(finishedInterview());
        // 显式打桩"没有被别的会话引用"：Mockito 默认就是 0，但写出来意图才清楚
        when(sessionService.count(any())).thenReturn(0L);

        service.deleteInterviews(List.of("iv-1"), "conv-1");

        verify(minioService).deleteFile("interview-report-iv-1.md");
        // 录音的 objectName 由 fileId + 文件类型算出来，规则与上传/重试两侧同源
        verify(minioService).deleteFile("file-file1.m4a");
        verify(interviewMapper).delete(any());
    }

    @Test
    @DisplayName("级联删除面试：MinIO 删除失败必须抛异常且不删库（由会话删除事务整体回滚）")
    void deleteInterviews_objectDeleteFails_throwsAndKeepsRows() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(finishedInterview());
        when(sessionService.count(any())).thenReturn(0L);
        doThrow(new RuntimeException("minio down")).when(minioService).deleteFile(anyString());

        assertThrows(IllegalStateException.class, () -> service.deleteInterviews(List.of("iv-1"), "conv-1"));

        // 先删对象、后删库：删库失败可见并可重试；反过来会留下"记录没了、录音还在"的不可见残留
        verify(interviewMapper, never()).delete(any());
    }

    @Test
    @DisplayName("级联删除面试：查不到记录时不碰 MinIO，但照样按 interviewId 删 0 行")
    void deleteInterviews_recordMissing_onlyDeletesRows() throws Exception {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(sessionService.count(any())).thenReturn(0L);

        service.deleteInterviews(List.of("iv-404"), "conv-1");

        verify(minioService, never()).deleteFile(anyString());
        verify(interviewMapper).delete(any());
    }

    @Test
    @DisplayName("级联删除面试：仍被别的会话引用时只解引用，不删 MinIO 对象、不删 ai_interview 记录")
    void deleteInterviews_referencedByOtherConversation_keepsObjectsAndRecord() throws Exception {
        // 同一段录音在 conv-2 里也上传过（幂等命中同一 interviewId）→ 删 conv-1 不能动它
        when(sessionService.count(any())).thenReturn(1L);

        service.deleteInterviews(List.of("iv-1"), "conv-1");

        verify(minioService, never()).deleteFile(anyString());
        verify(interviewMapper, never()).delete(any());
    }

    @Test
    @DisplayName("级联删除面试：一批里被引用的跳过、未引用的照删，delete 只带真正删掉的那批")
    void deleteInterviews_mixedReferences_deletesOnlyUnreferenced() throws Exception {
        // iv-1 仍被别的会话引用，iv-2 没有
        when(sessionService.count(any())).thenReturn(1L, 0L);
        AiInterview second = new AiInterview();
        second.setInterviewId("iv-2");
        second.setFileId("file-2");
        second.setFileName("interView.m4a");
        when(interviewMapper.selectOne(any())).thenReturn(second);

        service.deleteInterviews(List.of("iv-1", "iv-2"), "conv-1");

        // 被引用的那场连查都不查，自然也不会删它的录音/报告
        verify(interviewMapper, times(1)).selectOne(any());
        verify(minioService, never()).deleteFile("interview-report-iv-1.md");
        verify(minioService, never()).deleteFile("file-file1.m4a");
        verify(minioService).deleteFile("file-file2.m4a");

        // 关键不变式：delete 的入参只能是"真正删掉的那批"，带上 iv-1 就把别的会话在用的记录删了
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiInterview>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(interviewMapper).delete(captor.capture());
        captor.getValue().getTargetSql(); // MyBatis-Plus 3.5.x 参数惰性绑定，先取 SQL 才能读到参数表
        Map<String, Object> params =
                ((AbstractWrapper<AiInterview, ?, ?>) captor.getValue()).getParamNameValuePairs();
        assertTrue(params.containsValue("iv-2"), "应删掉未引用的 iv-2，实际绑定: " + params);
        assertFalse(params.containsValue("iv-1"), "不能把仍被别的会话引用的 iv-1 一起删掉，实际绑定: " + params);
    }

    @Test
    @DisplayName("级联删除面试：interviewIds 为空/为 null 时是空操作")
    void deleteInterviews_emptyIds_doesNothing() {
        service.deleteInterviews(null, "conv-1");
        service.deleteInterviews(List.of(), "conv-1");

        verifyNoInteractions(interviewMapper, minioService);
    }
}
