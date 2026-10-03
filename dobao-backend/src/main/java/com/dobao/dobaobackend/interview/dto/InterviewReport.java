package com.dobao.dobaobackend.interview.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试总结报告（落库到 {@code ai_interview.report_json}，并渲染成 Markdown 存 MinIO）。
 *
 *
 * <p><b>不含任何评分或总评</b> —— 这是需求明确禁止的内容。
 *
 * @param interviewId      业务标识
 * @param audioDurationMs  音频总时长（毫秒）
 * @param generatedAt      生成时间
 * @param qaList           一、问答清单（Q 与 A 均为逐字原文，时间戳来自真实句子数据）
 * @param referenceAnswers 二、参考回答（只含技术问题；旧报告里该字段为 null）
 * @param summary          三、面试总结（旧报告里该字段为 null）
 */
public record InterviewReport(
        String interviewId,
        long audioDurationMs,
        LocalDateTime generatedAt,
        List<QaItem> qaList,
        List<ReferenceAnswer> referenceAnswers,
        ReportSummary summary) {
}
