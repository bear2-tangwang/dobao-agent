package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dobao.dobaobackend.common.BaseResult;
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
