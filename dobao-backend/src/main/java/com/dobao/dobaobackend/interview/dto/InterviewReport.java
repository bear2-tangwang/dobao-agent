package com.dobao.dobaobackend.interview.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试总结报告（落库到 {@code ai_interview.report_json}，并渲染成 Markdown 存 MinIO）。
 *
 * <p>四部分：问答清单 / 知识点 / 待补充知识点 / 完整对话。
 * 前三部分是需求文档 §2 约定的内容；<b>第四部分（完整对话）是 2026-10 追加的</b>：
 * 只放问答清单时，没有与候选人发言配成对的语句在报告里会缺少独立可核对的位置，
 * 整场对话逐句附在后面，用户才能自己核对是否完整 —— 见 {@code docs/interview-step4-report.md} §9。
 *
 * <p><b>2026-10 重构</b>：删除 {@code failedChunks} / {@code skippedChunks} ——
 * 问答清单已改为由转写句子列表直接格式化（{@code QaListBuilder}），不再有分块与分块失败。
 * 同时 {@code qaList} 的元素类型由 {@code QaPair} 改为 {@code QaItem}：问答两侧都是逐字原文，
 * 没有模型摘要、没有原话摘录、没有逐条知识点标签。
 *
 * <p><b>不含任何评分或总评</b> —— 这是需求明确禁止的内容。
 *
 * @param interviewId     业务标识
 * @param audioDurationMs 音频总时长（毫秒）
 * @param generatedAt     生成时间
 * @param qaList          第一部分：问答清单（Q 与 A 均为逐字原文，时间戳来自真实句子数据）
 * @param knowledgeTopics 第二部分：知识点清单（由问答清单做一次 LLM 归纳）
 * @param knowledgeGaps   第三部分：待补充知识点（同上）
 * @param transcript      第四部分：完整对话（逐句，原话不改写）
 */
public record InterviewReport(
        String interviewId,
        long audioDurationMs,
        LocalDateTime generatedAt,
        List<QaItem> qaList,
        List<KnowledgeTopic> knowledgeTopics,
        List<KnowledgeGap> knowledgeGaps,
        List<TranscriptLine> transcript) {
}
