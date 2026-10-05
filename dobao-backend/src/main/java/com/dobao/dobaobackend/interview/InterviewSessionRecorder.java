package com.dobao.dobaobackend.interview;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.service.AiSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 面试总结 → {@code ai_session} 的写入器（本功能唯一的会话行写入点）。
 *
 * <p>一场面试 = 一行会话记录：{@code session_id} 取前端 conversationId，{@code agent_type} 为
 * {@value #AGENT_TYPE}，{@code question} 为录音文件名（侧边栏标题），{@code answer} 为状态摘要，
 * {@code fileid} 存 interviewId（沿用本表把 {@code fileid} 当多态业务指针的既有用法）。
 *
 * <p>回填按 {@code interviewId} 命中<b>所有</b>引用它的会话行：同一段录音可能出现在多个会话里，
 * 这些行都该拿到同一份摘要。单独成 Bean 是因为上传写入与异步回填分属两个互不依赖的服务类。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewSessionRecorder {

    /** 会话表里标识"这是一场面试"的 agent_type */
    public static final String AGENT_TYPE = "interview";

    /** 处理中的占位摘要（上传后、重试时） */
    private static final String RUNNING_SUMMARY = "面试总结进行中…";

    private final AiSessionService sessionService;

    /**
     * 记录"某会话里开始了某场面试"。幂等：同一 (会话, 面试) 只保留一行。
     *
     * <p>本类是 {@code answer} 列的唯一写入者，"有会话行就有非空 answer"由它维持；
     * 幂等命中已有行时不重写 answer —— 那一行可能已经是"已完成"。
     *
     * @param conversationId 前端会话ID；为空（或空白）表示老调用方，退化为"只写 ai_interview"
     * @param userId         归属用户。必须由调用方传入：{@code UserContext}(ThreadLocal)
     *                       在异步/SSE 链路上不可用，显式传参才不会在挪到异步路径时静默写错归属
     */
    public void recordUploaded(String conversationId, String interviewId, String fileName, String userId) {
        // 入口先 trim：带首尾空白的 id 能通过 hasText，但原样写进 session_id 后与 chat 行的
        // session_id 不一致，前端按 conversationId 取会话详情会查不到；查重与写入也都必须用
        // trim 后的值，否则同一会话重复上传会绕过幂等。
        String sessionId = conversationId == null ? null : conversationId.trim();
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(interviewId)) {
            log.info("缺少会话ID或面试ID，跳过会话记录: conversationId={}, interviewId={}",
                    conversationId, interviewId);
            return;
        }
        AiSession existing = find(sessionId, interviewId);
        if (existing != null) {
            // 同一会话重复提交同一段录音（音频哈希幂等命中）：只把这场顶到列表最前。
            // 列级更新而不是整行写回，避免把读到的旧 answer 一起覆盖回去。
            sessionService.update(new LambdaUpdateWrapper<AiSession>()
                    .eq(AiSession::getId, existing.getId())
                    .set(AiSession::getUpdateTime, LocalDateTime.now()));
            log.info("面试会话已存在，仅刷新时间: conversationId={}, interviewId={}", sessionId, interviewId);
            return;
        }
        sessionService.saveQuestion(SaveQuestionRequest.builder()
                .userId(userId)
                .sessionId(sessionId)
                .question(StringUtils.hasText(fileName) ? fileName : interviewId)
                .fileid(interviewId)
                .agentType(AGENT_TYPE)
                .build());
        // 插入后立刻写一次"进行中"占位摘要：上传到报告就绪之间是分钟级耗时，列表/详情读到 NULL
        // 只能显示空白；且幂等命中已有记录时不会重跑状态机，那一行没有别人替它回填摘要。
        updateSummary(interviewId, RUNNING_SUMMARY);
        log.info("面试会话已创建: conversationId={}, interviewId={}, fileName={}",
                sessionId, interviewId, fileName);
    }

    /** 处理中：把上一次留下的失败摘要换掉（重试时用） */
    public void markRunning(String interviewId) {
        updateSummary(interviewId, RUNNING_SUMMARY);
    }

    /** 报告就绪 */
    public void markReady(String interviewId, int qaCount, int referenceCount) {
        updateSummary(interviewId, String.format("面试总结已完成：共 %d 条问答、%d 条参考回答",
                qaCount, referenceCount));
    }

    /** 处理失败（转写失败 / 报告生成失败 / 超时都走这里） */
    public void markFailed(String interviewId, String reason) {
        updateSummary(interviewId, StringUtils.hasText(reason)
                ? "面试总结失败：" + reason
                : "面试总结失败");
    }

    /**
     * 按 interviewId 回填所有引用它的面试会话行：一条批量 UPDATE（原子、1 条 SQL），
     * 而不是"查出来再逐行写回"（1+2N 条 SQL，且并发下会丢更新）。
     */
    private void updateSummary(String interviewId, String answer) {
        if (!StringUtils.hasText(interviewId)) {
            log.warn("缺少面试ID，跳过会话摘要回填: answer={}", answer);
            return;
        }
        // IService#update(Wrapper) 返回的是"是否命中至少一行"（SqlHelper.retBool），不是行数
        boolean updated = sessionService.update(new LambdaUpdateWrapper<AiSession>()
                .eq(AiSession::getAgentType, AGENT_TYPE)
                .eq(AiSession::getFileid, interviewId)
                .set(AiSession::getAnswer, answer)
                // update_time 必须显式写：省掉这行会落到 MySQL 的 ON UPDATE CURRENT_TIMESTAMP
                //（UTC，比 JVM 时钟早 8 小时），把会话行排到 chat 行后面
                .set(AiSession::getUpdateTime, LocalDateTime.now()));
        if (updated) {
            log.info("面试会话摘要已回填: interviewId={}, answer={}", interviewId, answer);
        } else {
            log.debug("没有会话行引用这场面试，摘要未回填: interviewId={}, answer={}", interviewId, answer);
        }
    }

    private AiSession find(String conversationId, String interviewId) {
        return sessionService.getOne(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getSessionId, conversationId)
                .eq(AiSession::getFileid, interviewId)
                .eq(AiSession::getAgentType, AGENT_TYPE)
                .last("LIMIT 1"));
    }
}
