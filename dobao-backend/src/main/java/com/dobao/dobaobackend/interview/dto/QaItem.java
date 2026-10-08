package com.dobao.dobaobackend.interview.dto;

/**
 * 问答清单里的一条（由转写句子直接格式化，不经过任何 LLM）。
 *
 * <p>配对口径：面试官的一段连续发言算一个"问题"，紧随其后的候选人连续发言算"回答"。
 * 刻意不判断对方是否真的在提问，岗位介绍与流程说明也会各成一条 —— 宁可清单长一点，
 * 也不漏内容。问题与回答都是逐字原话，不做改写。
 *
 * @param qaId            编号（全局顺序编号，Q001、Q002…；报告第二节「参考回答」用同一编号引用）
 * @param question        面试官该轮的发言原文（多句已合并）
 * @param questionBeginMs 提问首句的起始时间戳（毫秒）
 * @param answer          候选人该轮的发言原文（多句已合并；无回答时为空串）
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
