package com.dobao.dobaobackend.interview.dto;

import java.util.List;

/**
 * LLM 在报告生成阶段的原始输出（只含需要模型归纳的两部分）。
 *
 * <p>问答清单不经过模型汇总 —— 它直接来自步骤 3 的抽取结果，
 * 这样能保证报告里的问答与时间戳与抽取阶段完全一致，不会被"二次改写"。
 */
public record ReportDraft(
        List<KnowledgeTopic> knowledgeTopics,
        List<KnowledgeGap> knowledgeGaps) {

    /**
     * 不会返回 null 的知识点列表
     */
    public List<KnowledgeTopic> safeTopics() {
        return knowledgeTopics == null ? List.of() : knowledgeTopics;
    }

    /**
     * 不会返回 null 的待补充列表
     */
    public List<KnowledgeGap> safeGaps() {
        return knowledgeGaps == null ? List.of() : knowledgeGaps;
    }
}
