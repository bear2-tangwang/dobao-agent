package com.dobao.dobaobackend.interview;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.service.AiSessionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

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

    /**
     * 让 {@code LambdaQueryWrapper.getTargetSql()} 能解析出列名。
     *
     * <p>{@code AiSession} 的 TableInfo 平时由 MyBatis 启动过程注册；纯单测没有 Spring 上下文，
     * 不手动注册的话，lambda 条件解析会抛 "can not find lambda cache for this entity"。
     * 只注册元数据，不建连接池、不读库。
     */
    @BeforeAll
    static void initTableInfo() {
        if (TableInfoHelper.getTableInfo(AiSession.class) == null) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
            assistant.setCurrentNamespace(AiSession.class.getName());
            TableInfoHelper.initTableInfo(assistant, AiSession.class);
        }
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
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(row));

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
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of(row));

        recorder.markFailed("iv-1", "分析失败: 模型超时");

        ArgumentCaptor<UpdateAnswerRequest> captor = ArgumentCaptor.forClass(UpdateAnswerRequest.class);
        verify(sessionService).updateAnswer(captor.capture());
        assertTrue(captor.getValue().getAnswer().contains("分析失败: 模型超时"));
    }

    @Test
    @DisplayName("回填只认面试类型的会话行（不能误伤 fileid 恰好相同的 chat 行）")
    @SuppressWarnings("unchecked")
    void updateSummary_filtersByAgentTypeAndFileid() {
        when(sessionService.list(ArgumentMatchers.<Wrapper<AiSession>>any())).thenReturn(List.of());

        recorder.markReady("iv-1", 1, 1);

        ArgumentCaptor<Wrapper<AiSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionService).list(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertTrue(sql.contains("agent_type"), "过滤条件必须含 agent_type，实际: " + sql);
        assertTrue(sql.contains("fileid"), "过滤条件必须含 fileid，实际: " + sql);
    }
}
