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
