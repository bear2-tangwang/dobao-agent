package com.dobao.dobaobackend.interview;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.service.AiSessionService;
import com.dobao.dobaobackend.testsupport.MybatisPlusTableInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
 *
 * <p>摘要回填必须是<b>一条批量 UPDATE</b>（列级 set），而不是"查出来再逐行写回"：
 * 前者 1 条 SQL、原子；后者 1+2N 条且并发下丢更新。下面用 wrapper 的 SET/WHERE 片段
 * 与绑定参数把这一点钉住。
 */
class InterviewSessionRecorderTest {

    private AiSessionService sessionService;
    private InterviewSessionRecorder recorder;

    @BeforeAll
    static void initTableInfo() {
        MybatisPlusTableInfo.ensure(AiSession.class);
    }

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
    @DisplayName("同一 (会话, 面试) 已存在：只按主键做列级刷新 update_time，不重复插行")
    void recordUploaded_existingRow_onlyTouchesTime() {
        AiSession existing = new AiSession();
        existing.setId(11L);
        when(sessionService.getOne(any())).thenReturn(existing);

        recorder.recordUploaded("conv-1", "iv-1", "interView.m4a");

        verify(sessionService, never()).saveQuestion(any());
        Wrapper<AiSession> wrapper = captureUpdate();
        String set = setClause(wrapper);
        assertFalse(set.contains("answer"),
                "只该刷新 update_time，不能把读到的旧 answer 整行写回，实际 SET: " + set);
        assertEquals(11L, boundValue(wrapper, "id"), "必须按主键定位这一行，实际 SET: " + set);
        Object updateTime = boundValue(wrapper, "update_time");
        assertInstanceOf(LocalDateTime.class, updateTime, "update_time 应显式绑定 JVM 时钟，实际: " + updateTime);
        assertTrue(((LocalDateTime) updateTime).isAfter(LocalDateTime.now().minusMinutes(1)),
                "应把 update_time 刷新到现在，列表排序才会浮上来");
        verify(sessionService, never()).updateById(any());
    }

    @Test
    @DisplayName("没传 conversationId（老调用方）：完全不碰会话表")
    void recordUploaded_withoutConversationId_doesNothing() {
        recorder.recordUploaded(null, "iv-1", "interView.m4a");
        recorder.recordUploaded("", "iv-1", "interView.m4a");

        verifyNoInteractions(sessionService);
    }

    @Test
    @DisplayName("回填完成摘要：一条批量 UPDATE 按 agent_type + fileid 定位，写完整文案")
    void markReady_writesSummary() {
        when(sessionService.update(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(true);

        recorder.markReady("iv-1", 12, 8);

        Wrapper<AiSession> wrapper = captureUpdate();
        assertEquals("面试总结已完成：共 12 条问答、8 条参考回答", boundValue(wrapper, "answer"));
        assertNotNull(boundValue(wrapper, "update_time"),
                "必须显式写 update_time，否则 MySQL ON UPDATE CURRENT_TIMESTAMP 会用 UTC 排序到 chat 行后面");
        verify(sessionService, never()).updateAnswer(any());
        verify(sessionService, never()).updateById(any());
        verify(sessionService, never()).list(ArgumentMatchers.<Wrapper<AiSession>>any());
    }

    @Test
    @DisplayName("回填失败摘要：带上失败原因")
    void markFailed_writesReason() {
        when(sessionService.update(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(true);

        recorder.markFailed("iv-1", "分析失败: 模型超时");

        assertEquals("面试总结失败：分析失败: 模型超时", boundValue(captureUpdate(), "answer"));
        verify(sessionService, never()).updateAnswer(any());
    }

    @Test
    @DisplayName("重试开始：把上一次留下的失败摘要换成进行中占位")
    void markRunning_writesRunningSummary() {
        when(sessionService.update(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(true);

        recorder.markRunning("iv-1");

        assertEquals("面试总结进行中…", boundValue(captureUpdate(), "answer"));
        verify(sessionService, never()).updateAnswer(any());
    }

    @Test
    @DisplayName("回填只认面试类型的会话行；命中 0 行时也不退化成逐行读-改-写")
    void updateSummary_filtersByAgentTypeAndFileid() {
        when(sessionService.update(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(false);

        recorder.markReady("iv-1", 1, 1);

        Wrapper<AiSession> wrapper = captureUpdate();
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("agent_type"), "过滤条件必须含 agent_type，实际: " + sql);
        assertTrue(sql.contains("fileid"), "过滤条件必须含 fileid，实际: " + sql);
        Map<String, Object> params = paramValues(wrapper);
        assertTrue(params.containsValue(InterviewSessionRecorder.AGENT_TYPE),
                "参数里必须绑定 agent_type（不能写成别的字面量），实际: " + params);
        assertTrue(params.containsValue("iv-1"), "参数里必须绑定 interviewId，实际: " + params);
        assertEquals("interview", boundValue(wrapper, "agent_type"));
        assertEquals("iv-1", boundValue(wrapper, "fileid"));

        // stub 返回 false = 命中 0 行：这条路径同样不该退回"查出来逐行写回"
        verify(sessionService, never()).list(ArgumentMatchers.<Wrapper<AiSession>>any());
        verify(sessionService, never()).updateAnswer(any());
        verify(sessionService, never()).updateById(any());
    }

    /** 捕获那一次批量 UPDATE 的 wrapper，并顺带断言它只被调用一次 */
    @SuppressWarnings("unchecked")
    private Wrapper<AiSession> captureUpdate() {
        ArgumentCaptor<Wrapper<AiSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).update(captor.capture());
        return captor.getValue();
    }

    /** UPDATE 的 SET 片段（保留 {@code #{ew.paramNameValuePairs.MPGENVALn}} 占位符） */
    @SuppressWarnings("unchecked")
    private static String setClause(Wrapper<AiSession> wrapper) {
        assertTrue(wrapper instanceof LambdaUpdateWrapper,
                "回填必须是批量 UPDATE（LambdaUpdateWrapper），实际: " + wrapper.getClass().getName());
        return ((LambdaUpdateWrapper<AiSession>) wrapper).getSqlSet();
    }

    /** SET + WHERE 的原始片段，用来把某一列绑定的参数值取出来 */
    private static String rawSegments(Wrapper<AiSession> wrapper) {
        String set = setClause(wrapper);
        String where = wrapper.getSqlSegment();
        return where.contains(set) ? where : set + " " + where;
    }

    /** 取出"SQL 里 {column} = ?"这个占位符实际绑定的参数值 */
    private static Object boundValue(Wrapper<AiSession> wrapper, String column) {
        String sql = rawSegments(wrapper);
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(column)
                        + "\\s*=\\s*#\\{[A-Za-z0-9_.]*paramNameValuePairs\\.([A-Za-z0-9_]+)}")
                .matcher(sql);
        assertTrue(matcher.find(), "SQL 里应把列 " + column + " 绑定成参数，实际: " + sql);
        return paramValues(wrapper).get(matcher.group(1));
    }

    private static Map<String, Object> paramValues(Wrapper<AiSession> wrapper) {
        return ((AbstractWrapper<AiSession, ?, ?>) wrapper).getParamNameValuePairs();
    }
}
