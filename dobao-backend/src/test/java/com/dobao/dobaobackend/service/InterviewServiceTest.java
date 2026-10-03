package com.dobao.dobaobackend.service;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
import com.dobao.dobaobackend.interview.dto.InterviewUploadVO;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        verify(fileManageService, never()).uploadFile(any());
        verify(sessionRecorder).recordUploaded("conv-2", "iv-9", "interView.m4a");
        verify(taskService, never()).submitTranscriptionAsync(anyString(), anyString());
    }

    @ParameterizedTest(name = "[{index}] conversationId=[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("conversationId 为 null / 空串 / 纯空白：上传照常成功，值原样透传（由 Recorder 决定不写会话行）")
    void upload_withoutConversationId_stillUploads(String conversationId) {
        when(interviewMapper.selectOne(any())).thenReturn(null);
        when(fileManageService.uploadFile(any())).thenReturn(FileInfo.builder()
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
                eq("interView.m4a"));
    }
}
