package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.entity.AiFileInfo;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.MessageVO;
import com.dobao.dobaobackend.entity.vo.PageResult;
import com.dobao.dobaobackend.entity.vo.SessionDetailVO;
import com.dobao.dobaobackend.entity.vo.SessionListVO;
import com.dobao.dobaobackend.mapper.AiFileInfoMapper;
import com.dobao.dobaobackend.mapper.AiPptInstMapper;
import com.dobao.dobaobackend.service.AiSessionService;
import com.dobao.dobaobackend.service.InterviewService;
import com.dobao.dobaobackend.testsupport.MybatisPlusTableInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private InterviewService interviewService;
    private SessionController controller;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测没有 Spring 上下文：不注册 AiSession 元数据，wrapper.getTargetSql() 会抛
        // "can not find lambda cache for this entity [AiSession]"
        MybatisPlusTableInfo.ensure(AiSession.class);
    }

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

    @Test
    @DisplayName("会话列表：去重下推到 SQL（每个 session_id 取 MAX(id)），total 用分页结果的总数")
    void getSessionList_dedupesInSqlAndKeepsPageTotal() {
        // 一次面试上传就给同一会话新增一行，且按 update_time desc 排在前面：
        // 先去重再分页会让会话变少、total 也跟着变小，所以去重必须发生在分页之前（下推到 SQL）
        AiSession newest = interviewRow();
        newest.setUpdateTime(LocalDateTime.now());
        AiSession other = new AiSession();
        other.setId(9L);
        other.setSessionId("conv-2");
        other.setAgentType("chat");
        other.setQuestion("介绍一下 node.js");
        other.setUpdateTime(LocalDateTime.now().minusMinutes(1));
        Page<AiSession> page = new Page<>(1, 10);
        page.setRecords(List.of(newest, other));
        page.setTotal(2L);
        when(sessionService.page(ArgumentMatchers.<Page<AiSession>>any(), any())).thenReturn(page);

        BaseResult<PageResult<SessionListVO>> result = controller.getSessionList(1, 10, null);

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().getRecords().size());
        assertEquals(2L, result.getData().getTotal().longValue(),
                "total 必须来自分页结果，不能是内存去重后的 size");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AiSession>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).page(ArgumentMatchers.<Page<AiSession>>any(), wrapperCaptor.capture());
        String sql = wrapperCaptor.getValue().getTargetSql();
        assertTrue(sql.toUpperCase().contains("MAX(ID)"),
                "每个会话只取最新一行必须下推到 SQL（MAX(id) 子查询），实际: " + sql);
        assertTrue(sql.toLowerCase().contains("group by session_id"),
                "去重必须按 session_id 分组，实际: " + sql);
    }

    @Test
    @DisplayName("会话列表：SQL 已按会话去重时不会出现重复的 conversationId")
    void getSessionList_recordsHaveNoDuplicateConversationId() {
        AiSession row = interviewRow();
        row.setUpdateTime(LocalDateTime.now());
        Page<AiSession> page = new Page<>(1, 10);
        page.setRecords(List.of(row));
        page.setTotal(1L);
        when(sessionService.page(ArgumentMatchers.<Page<AiSession>>any(), any())).thenReturn(page);

        List<SessionListVO> records = controller.getSessionList(1, 10, null).getData().getRecords();

        assertEquals(1, records.size());
        assertEquals(1, records.stream().map(SessionListVO::getConversationId).distinct().count(),
                "同一 sessionId 只允许出现一条");
    }

    @Test
    @DisplayName("删除会话：面试行的 fileid 作为 interviewId 交出去级联删除（连带 MinIO 录音与报告）")
    void deleteSession_cascadesInterviews() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(interviewRow()));

        BaseResult<String> result = controller.deleteSession("conv-1");

        assertEquals(200, result.getCode());
        // fileid 即 interviewId（见 InterviewSessionRecorder）：不传下去录音就永远留在 MinIO 里；
        // 第二个参数是"正在被删除的会话"，供 InterviewService 判断这场面试是否还被别的会话引用
        verify(interviewService).deleteInterviews(List.of("iv-1"), "conv-1");
        verify(sessionService).remove(any());
    }

    @Test
    @DisplayName("删除会话：会话不存在时直接失败，不碰面试记录（否则会误删别人的面试）")
    void deleteSession_missingSession_doesNothing() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of());

        BaseResult<String> result = controller.deleteSession("conv-404");

        assertEquals(500, result.getCode());
        verifyNoInteractions(interviewService);
        verify(sessionService, never()).remove(any());
    }

    @Test
    @DisplayName("删除会话：中途失败必须把异常抛出去（吞掉它 @Transactional 就不会回滚）")
    void deleteSession_failure_propagatesInsteadOfBeingSwallowed() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(interviewRow()));
        // MinIO 删对象失败：deleteInterviews 会抛 IllegalStateException
        org.mockito.Mockito.doThrow(new IllegalStateException("删除面试文件失败: x.md"))
                .when(interviewService).deleteInterviews(any(), any());

        // 必须抛出：一旦被 try/catch 吞成 BaseResult.newError，事务拦截器就看不到异常，
        // 结果是"MinIO 对象已删、DB 只删了一半"的半状态
        assertThrows(IllegalStateException.class, () -> controller.deleteSession("conv-1"));
        verify(sessionService, never()).remove(any());
    }
}
