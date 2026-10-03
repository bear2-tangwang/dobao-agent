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
 * 面试总结 → {@code ai_session} 的写入器（本需求唯一的会话行写入点）。
 *
 * <p><b>一场面试 = 一行会话记录</b>：
 * <ul>
 *   <li>{@code session_id} = 前端 conversationId（一个会话可含多场面试 → 多行）</li>
 *   <li>{@code agent_type} = {@value #AGENT_TYPE}</li>
 *   <li>{@code question} = 录音文件名（侧边栏标题取 question，正好显示成文件名）</li>
 *   <li>{@code answer} = 状态摘要（报告正文的唯一来源仍是 {@code ai_interview.report_json}）</li>
 *   <li>{@code fileid} = interviewId —— 沿用本表把 {@code fileid} 当多态业务指针的既有用法
 *       （见设计文档 §2 决策 4）</li>
 * </ul>
 *
 * <p><b>为什么单独成 Bean</b>：上传写入在 {@link com.dobao.dobaobackend.service.InterviewService}
 * （快路径），回填在 {@link com.dobao.dobaobackend.service.InterviewTaskService}（异步线程），
 * 而这两个类被明确要求互不依赖（见 InterviewService 类注释）。本类作为第三方被两者依赖。
 *
 * <p>回填按 {@code interviewId} 命中<b>所有</b>引用它的会话行：同一段录音可能出现在多个会话里
 * （幂等命中同一场分析），这些行都该拿到同一份摘要。
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
     * @param conversationId 前端会话ID；为空表示老调用方，退化为"只写 ai_interview"
     */
    public void recordUploaded(String conversationId, String interviewId, String fileName) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(interviewId)) {
            log.info("缺少会话ID或面试ID，跳过会话记录: conversationId={}, interviewId={}",
                    conversationId, interviewId);
            return;
        }
        AiSession existing = find(conversationId, interviewId);
        if (existing != null) {
            // 同一会话重复提交同一段录音（音频哈希幂等命中）：只把这场顶到列表最前。
            // 列级更新（不是整行写回），避免把读到的旧 answer 一起覆盖回去。
            sessionService.update(new LambdaUpdateWrapper<AiSession>()
                    .eq(AiSession::getId, existing.getId())
                    .set(AiSession::getUpdateTime, LocalDateTime.now()));
            log.info("面试会话已存在，仅刷新时间: conversationId={}, interviewId={}", conversationId, interviewId);
            return;
        }
        sessionService.saveQuestion(SaveQuestionRequest.builder()
                .sessionId(conversationId)
                .question(StringUtils.hasText(fileName) ? fileName : interviewId)
                .fileid(interviewId)
                .agentType(AGENT_TYPE)
                .build());
        log.info("面试会话已创建: conversationId={}, interviewId={}, fileName={}",
                conversationId, interviewId, fileName);
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
                // 必须显式写 update_time：ai_session 这一列在现有链路里全部来自 JVM 时钟
                //（saveQuestion/updateAnswer 都用 LocalDateTime.now()）。省掉这行会让 MySQL 的
                // ON UPDATE CURRENT_TIMESTAMP 写入 UTC，比 JVM 时钟早 8 小时，于是
                // SessionController 的 update_time desc 排序把这行排到 chat 行后面。
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
