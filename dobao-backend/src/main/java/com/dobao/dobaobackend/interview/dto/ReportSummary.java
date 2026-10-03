package com.dobao.dobaobackend.interview.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * 报告第三部分：面试总结（**总结性质，不展开成清单式长文**）。
 *
 * <p><b>2026-10 重构</b>：原报告的「知识点清单」与「待补充知识点」两节被本记录取代 ——
 * 它们把同一件事写了两遍（每个知识点都要 points / performance / why / directions），
 * 篇幅远超"总结"该有的体量。现在只留三样东西：
 *
 * <ol>
 *   <li>{@code coveredTopics}：本轮实际涉及的知识点（速览用，短词，如"Redis 缓存穿透"）；</li>
 *   <li>{@code gapTopics}：候选人答得不好或没答上、后续需要补充的知识点；</li>
 *   <li>{@code summary}：约 100 字的中文总结，串起"覆盖了什么、表现如何、接下来优先补什么"。</li>
 * </ol>
 *
 * <p>不做评分、不做总评、不判断是否通过 —— 这是需求明确禁止的内容，总结里也不允许出现。
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
     * <p>{@code @JsonIgnore} 是必须的：Jackson 会把 {@code isEmpty()} 当成布尔 getter
     * 序列化成 {@code summary.empty} 字段（实测真的出现在 report_json 里），
     * 而它不是协议的一部分，留着只会让前端类型与库里的 JSON 对不上。
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
