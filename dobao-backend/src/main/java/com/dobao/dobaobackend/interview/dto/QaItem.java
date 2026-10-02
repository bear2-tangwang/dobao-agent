package com.dobao.dobaobackend.interview.dto;

/**
 * 问答清单里的一条（**纯格式化产物，不经过任何 LLM**）。
 *
 * <p><b>2026-10 重构</b>：原 {@code QaPair} 的 {@code answerSummary} / {@code answerQuotes} /
 * {@code topics} 三个字段全部删除 —— 它们的存在前提是"模型读了这一段并做了摘要与归纳"。
 * 现在问答清单改为由转写句子列表直接格式化（见 {@code QaListBuilder}），
 * 因此：问题与回答都是<b>逐字原话</b>，没有任何改写，也没有模型给的知识点标签
 * （知识点统一由报告阶段那一次调用产出，见 {@code InterviewReportGenerator}）。
 *
 * <p><b>时间戳全部来自真实句子数据</b>：不再有"模型输出句子序号 + 回填"这一步，
 * 毫秒值直接取问答两侧首句的 {@code begin_ms} 与末句的 {@code end_ms}，
 * 因此误差恒为 0，不存在"模型把序号写错"的风险。
 *
 * <p><b>配对口径（用户 2026-10 指定）</b>：按对话轮次全量成对 —— 面试官的一段连续发言
 * 作为一个"问题"，紧随其后的候选人连续发言作为"回答"。这个口径<b>不判断对方是否真的在提问</b>，
 * 所以岗位介绍、流程说明、改约协调也会各成一条；这是刻意的取舍：
 * 宁可清单长一点、也不漏内容，同时彻底去掉"哪个块该不该调模型"这类判断。
 *
 * @param qaId            编号（全局顺序编号，Q001、Q002…；知识点清单用 {@code 出自：Q002} 引用它）
 * @param question        面试官该轮的发言原文（多句已合并，逐字未改写）
 * @param questionBeginMs 提问首句的起始时间戳（毫秒）
 * @param answer          候选人该轮的发言原文（多句已合并，逐字未改写；无回答时为空串）
 * @param answerBeginMs   回答首句的起始时间戳（毫秒）；无回答时等于提问起始时间
 * @param answerEndMs     回答末句的结束时间戳（毫秒）；无回答时等于提问起始时间
 */
public record QaItem(
        String qaId,
        String question,
        long questionBeginMs,
        String answer,
        long answerBeginMs,
        long answerEndMs) {
}
