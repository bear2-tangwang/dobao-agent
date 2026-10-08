package com.dobao.dobaobackend.interview.dto;

import java.util.List;

/**
 * LLM 在报告生成阶段的原始输出，只含需要模型归纳的两部分。
 *
 * <p>问答清单不在这里：它由 {@code QaListBuilder} 从转写句子直接格式化，
 * 保证报告里的问答与时间戳跟文字稿逐字一致，不被二次改写。
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
