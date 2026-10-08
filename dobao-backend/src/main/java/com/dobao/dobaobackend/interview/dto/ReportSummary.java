package com.dobao.dobaobackend.interview.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 报告第三部分：面试总结（总结性质，不展开成清单式长文）。
 *
 * <p>不做评分、不做总评、不判断是否通过 —— 总结里也不允许出现这类内容。
 *
 * @param coveredTopics 本轮涉及的知识点（短词列表）
 * @param gapTopics     后续需要补充的知识点（短词列表，可为空）
 * @param summary       约 100 字的总结性文字（可为空串）
 */
public record ReportSummary(
        List<String> coveredTopics,
        List<String> gapTopics,
        String summary) {

    /**
     * 三部分都为空时视为"没有总结"。
     *
     * <p>{@code @JsonIgnore} 不能删：Jackson 会把 {@code isEmpty()} 当成布尔 getter
     * 序列化出 {@code summary.empty} 字段，而它不是协议的一部分，
     * 留着只会让前端类型与库里的 JSON 对不上。
     */
    @JsonIgnore
    public boolean isEmpty() {
        return safeCoveredTopics().isEmpty() && safeGapTopics().isEmpty()
                && (summary == null || summary.isBlank());
    }

    /** 不会返回 null 的知识点列表 */
    public List<String> safeCoveredTopics() {
        return coveredTopics == null ? List.of() : coveredTopics;
    }

    /** 不会返回 null 的待补充列表 */
    public List<String> safeGapTopics() {
        return gapTopics == null ? List.of() : gapTopics;
    }
}
