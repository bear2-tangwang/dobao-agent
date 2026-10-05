package com.dobao.dobaobackend.controller;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 会话读取路径：面试行的 {@code fileid} 就是 interviewId（本表"按 agent_type 解释业务指针"的约定），
 * 因此不能再去 {@code ai_file_info} 查一遍，文件名直接用 {@code question}。
 */
class SessionControllerTest {

    private AiSessionService sessionService;
    private AiFileInfoMapper aiFileInfoMapper;
    private AiPptInstMapper aiPptInstMapper;
    private InterviewService interviewService;
    private SessionController controller;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测没有 Spring 上下文：不注册元数据，wrapper.getTargetSql() 会抛 "can not find lambda cache"
        MybatisPlusTableInfo.ensure(AiSession.class);
    }

    @BeforeEach
    void setUp() {
        sessionService = mock(AiSessionService.class);
        aiFileInfoMapper = mock(AiFileInfoMapper.class);
        aiPptInstMapper = mock(AiPptInstMapper.class);
        interviewService = mock(InterviewService.class);
        // 数据隔离依赖它取兜底用户（未登录/单测无请求上下文时用）
        GithubOAuthProperties authProperties = new GithubOAuthProperties();
        authProperties.setDefaultUserId("u-default");
        controller = new SessionController();
        ReflectionTestUtils.setField(controller, "aiSessionService", sessionService);
        ReflectionTestUtils.setField(controller, "aiFileInfoMapper", aiFileInfoMapper);
        ReflectionTestUtils.setField(controller, "aiPptInstMapper", aiPptInstMapper);
        ReflectionTestUtils.setField(controller, "interviewService", interviewService);
        ReflectionTestUtils.setField(controller, "authProperties", authProperties);
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
        // IService#list 有 (Wrapper)/(IPage) 两个重载，裸 any() 编译不过
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(interviewRow()));

        BaseResult<SessionDetailVO> result = controller.getSession("conv-1");

        assertEquals(200, result.getCode());
        assertEquals("interview", result.getData().getAgentType());
        MessageVO message = result.getData().getMessages().get(0);
        assertEquals("iv-1", message.getInterviewId());
        assertEquals("interView.m4a", message.getFileName());
        assertEquals("iv-1", message.getFileid());
        // 面试行的 fileid 是 interviewId 而非 ai_file_info.file_id，文件元信息全部为空
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
    @DisplayName("会话列表：去重与用户过滤都在同一条相关子查询里，total 用分页结果的总数")
    void getSessionList_dedupesInSqlAndKeepsPageTotal() {
        // 去重必须先于分页（下推到 SQL）：先去重再分页会让会话变少、total 跟着变小
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

        Wrapper<AiSession> wrapper = captureListWrapper();
        String sql = wrapper.getTargetSql().toLowerCase();

        // 去重必须是真正的 SQL 子查询：`.in(id, subQueryWrapper)` 没有对应重载，会退化成
        // `id IN (?)`（Wrapper 被当成绑定参数），MySQL 拿对象字符串比 bigint 静默返回 0 行。
        // 下面两条断言是这条不变式唯一的护栏。
        assertTrue(sql.contains("select max("),
                "去重必须落成真正的相关子查询（id = (SELECT MAX(...))），实际 SQL: " + wrapper.getTargetSql());
        assertFalse(sql.contains("in (?)"),
                "不能出现 `id IN (?)`：那说明子查询被当成绑定参数了，实际 SQL: " + wrapper.getTargetSql());

        // 用户条件必须在去重子查询内部：否则所有用户的行一起取每组最大 id，
        // 别人先占用了同一个 session_id 时自己那行会被淘汰、会话凭空消失。
        assertTrue(sql.contains("session_id"),
                "去重必须按 session_id 归并，实际 SQL: " + wrapper.getTargetSql());
        assertTrue(sql.contains("user_id"),
                "用户过滤必须在去重子查询里，实际 SQL: " + wrapper.getTargetSql());
        assertTrue(sql.contains("ai_session"),
                "子查询要自己 FROM 一次会话表，实际 SQL: " + wrapper.getTargetSql());

        Map<String, Object> params = paramNameValuePairs(wrapper);
        assertTrue(params.containsValue("u-default"),
                "子查询必须绑定当前用户ID，实际参数: " + params);
        // 反向护栏：绑定参数里不允许出现 Wrapper（出现了就说明又写成 in(wrapper) 了）
        assertFalse(params.values().stream().anyMatch(Wrapper.class::isInstance),
                "子查询不能作为绑定值传进去（那正是 in(wrapper) 的错误写法），实际参数: " + params);
    }

    @Test
    @DisplayName("会话列表：agentType 过滤与去重在同一子查询里，且 agentType 也是参数绑定")
    void getSessionList_agentTypeFilterGoesIntoDedupeSubquery() {
        Page<AiSession> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0L);
        when(sessionService.page(ArgumentMatchers.<Page<AiSession>>any(), any())).thenReturn(page);

        controller.getSessionList(1, 10, "chat");

        Wrapper<AiSession> wrapper = captureListWrapper();
        String sql = wrapper.getTargetSql().toLowerCase();

        // agentType 过滤必须与去重同在一个子查询：先去重再过滤会把"先 chat 后面试"的
        // 混排会话整体滤掉（最新一行是面试行时尤其明显）。
        assertTrue(sql.contains("select max("),
                "去重子查询不能因为加了 agentType 过滤就退化成别的写法，实际 SQL: " + wrapper.getTargetSql());
        assertTrue(sql.contains("agent_type"),
                "agentType 过滤要下推到去重子查询，实际 SQL: " + wrapper.getTargetSql());

        Map<String, Object> params = paramNameValuePairs(wrapper);
        assertTrue(params.containsValue("u-default") && params.containsValue("chat"),
                "用户ID 与 agentType 都要绑定进子查询，实际参数: " + params);
        assertFalse(params.values().stream().anyMatch(Wrapper.class::isInstance),
                "子查询不能作为绑定值传进去，实际参数: " + params);
    }

    /** 取出 controller 传给 IService#page 的 wrapper */
    @SuppressWarnings("unchecked")
    private Wrapper<AiSession> captureListWrapper() {
        ArgumentCaptor<Wrapper<AiSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).page(ArgumentMatchers.<Page<AiSession>>any(), captor.capture());
        return captor.getValue();
    }

    /** 取 wrapper 上绑定的具名参数表（MPGENVAL... -> 值） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> paramNameValuePairs(Wrapper<AiSession> wrapper) {
        return ((AbstractWrapper<AiSession, ?, ?>) wrapper).getParamNameValuePairs();
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
        // fileid 即 interviewId：不传下去录音会永远留在 MinIO；第二个参数是"正在被删除的会话"，
        // 供 InterviewService 判断这场面试是否还被别的会话引用
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

        // 必须抛出：吞成 BaseResult.newError 后事务拦截器看不到异常，
        // 会留下"MinIO 对象已删、DB 只删了一半"的半状态
        assertThrows(IllegalStateException.class, () -> controller.deleteSession("conv-1"));
        verify(sessionService, never()).remove(any());
    }
}
