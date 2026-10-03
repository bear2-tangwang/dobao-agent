package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.entity.AiFileInfo;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.MessageVO;
import com.dobao.dobaobackend.entity.vo.SessionDetailVO;
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话读取路径：面试会话要把它那行的 fileid 解读成 interviewId 交给前端。
 *
 * <p>面试行的 {@code fileid} 是 interviewId（沿用本表"按 agent_type 解释的业务指针"约定），
 * 所以它不能再去 {@code ai_file_info} 里查一遍；文件名直接用 {@code question}。
 */
class SessionControllerTest {

    private AiSessionService sessionService;
    private AiFileInfoMapper aiFileInfoMapper;
    private AiPptInstMapper aiPptInstMapper;
    private SessionController controller;

    @BeforeEach
    void setUp() {
        sessionService = mock(AiSessionService.class);
        aiFileInfoMapper = mock(AiFileInfoMapper.class);
        aiPptInstMapper = mock(AiPptInstMapper.class);
        controller = new SessionController();
        ReflectionTestUtils.setField(controller, "aiSessionService", sessionService);
        ReflectionTestUtils.setField(controller, "aiFileInfoMapper", aiFileInfoMapper);
        ReflectionTestUtils.setField(controller, "aiPptInstMapper", aiPptInstMapper);
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
        // 面试行的文件元信息全部为空：这条 fileid 是 interviewId，不是 ai_file_info.file_id
        assertNull(message.getFileType(), "面试行不该有文件类型（它不是 ai_file_info 的行）");
        assertNull(message.getFileSize(), "面试行不该有文件大小（它不是 ai_file_info 的行）");
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

    @Test
    @DisplayName("普通会话 + 有 fileid：文件元信息仍取自 ai_file_info，interviewId 恒为空")
    void getSession_chatWithFileid_fillsFileMetaFromFileTable() {
        AiSession row = new AiSession();
        row.setId(7L);
        row.setSessionId("conv-3");
        row.setAgentType("chat");
        row.setQuestion("帮我看看这份年报");
        row.setFileid("file-9");
        AiFileInfo info = new AiFileInfo();
        info.setFileId("file-9");
        info.setFileName("年报.pdf");
        info.setFileType("pdf");
        info.setFileSize(1024L);
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(row));
        when(aiFileInfoMapper.selectOne(ArgumentMatchers.<Wrapper<AiFileInfo>>any())).thenReturn(info);

        MessageVO message = controller.getSession("conv-3").getData().getMessages().get(0);

        // 反向分支：非面试侧必须照旧走文件表，且 interviewId 只能是 null
        assertEquals("年报.pdf", message.getFileName());
        assertEquals("pdf", message.getFileType());
        assertEquals(Long.valueOf(1024L), message.getFileSize());
        assertNull(message.getInterviewId());
        assertEquals("file-9", message.getFileid());
    }

    @Test
    @DisplayName("同一 sessionId 多行（面试行 + chat 行混排）：逐行判断，interviewId 归属不串行")
    void getSession_mixedRows_interviewIdIsPerRow() {
        AiSession interview = interviewRow();
        AiSession chat = new AiSession();
        chat.setId(8L);
        chat.setSessionId("conv-1");
        chat.setAgentType("chat");
        chat.setQuestion("帮我看看这份年报");
        chat.setFileid("file-9");
        chat.setCreateTime(LocalDateTime.now());
        AiFileInfo info = new AiFileInfo();
        info.setFileId("file-9");
        info.setFileName("年报.pdf");
        info.setFileType("pdf");
        info.setFileSize(1024L);
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any()))
                .thenReturn(List.of(interview, chat));
        when(aiFileInfoMapper.selectOne(ArgumentMatchers.<Wrapper<AiFileInfo>>any())).thenReturn(info);

        SessionDetailVO detail = controller.getSession("conv-1").getData();

        // agentType 取首行：首行是面试行 → 整个会话按面试入口展示
        assertEquals("interview", detail.getAgentType());
        assertEquals(2, detail.getMessages().size(), "一个会话可含多行（多场面试 + chat），不能只回第一行");

        MessageVO first = detail.getMessages().get(0);
        assertEquals("iv-1", first.getInterviewId(), "面试行的 interviewId 来自自己的 fileid");
        assertEquals("interView.m4a", first.getFileName());
        assertNull(first.getFileType(), "面试行不去查文件表，元信息为空");

        MessageVO second = detail.getMessages().get(1);
        assertNull(second.getInterviewId(), "chat 行的 fileid 是文件ID，不是 interviewId，必须判空");
        assertEquals("年报.pdf", second.getFileName(), "chat 行照旧去文件表补文件名");
        assertEquals("file-9", second.getFileid());
    }
}
