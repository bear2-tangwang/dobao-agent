package com.dobao.dobaobackend.interview.dto;

import java.util.List;

/**
 * LLM 在报告生成阶段的原始输出（只含需要模型归纳的两部分）。
 *
 * <p><b>问答清单不经过模型汇总</b> —— 它由 {@code QaListBuilder} 从转写句子直接格式化，
 * 保证报告里的问答与时间戳与文字稿逐字一致，不会被"二次改写"。
 *
 * <p><b>2026-10 重构</b>：原 {@code knowledgeTopics} / {@code knowledgeGaps} 两个字段
 * 合并为 {@link ReportSummary}（三个字段：涉及知识点、待补充知识点、约 100 字总结），
 * 同时新增 {@link ReferenceAnswer}（技术问题的参考回答）。
 *
 * @param referenceAnswers 技术问题的参考回答（可为 null / 空数组）
 * @param summary          面试总结（可为 null）
 */
public record ReportDraft(
        List<ReferenceAnswer> referenceAnswers,
        ReportSummary summary) {

    /**
     * 不会返回 null 的参考回答列表
     */
    public List<ReferenceAnswer> safeReferenceAnswers() {
        return referenceAnswers == null ? List.of() : referenceAnswers;
    }

    /**
     * 不会返回 null 的面试总结
     */
    public ReportSummary safeSummary() {
        return summary == null ? new ReportSummary(List.of(), List.of(), "") : summary;
    }
}
