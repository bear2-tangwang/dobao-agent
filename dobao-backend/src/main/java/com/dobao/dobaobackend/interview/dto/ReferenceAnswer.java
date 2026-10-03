package com.dobao.dobaobackend.interview.dto;

/**
 * 报告第二部分：一条技术问题的参考回答。
 *
 * <p><b>2026-10 重构新增</b>：原报告只有"候选人原话"，用户知道被问了什么、却不知道答得对不对，
 * 复盘价值低。本节由报告阶段那一次 LLM 调用从问答清单里**挑出真正的技术问题**并给出参考答案。
 *
 * <p><b>为什么只覆盖技术问题</b>：清单里大量条目是岗位介绍、公司介绍、面试流程说明、
 * 约定时间等非技术对话（{@link QaItem} 的配对口径刻意不判断"是否真提问"），
 * 给这些条目写参考答案没有意义，由模型跳过。
 *
 * <p><b>为什么不做词面校验</b>：{@code answer} 描述的是"应该怎么答"，它本就<b>不该</b>
 * 出现在候选人原话里；用"是否出现在原文"去校验只会把正确结果全部误删。
 * 防编造在这里退化为<b>编号落地校验</b>：{@code qaId} 必须指向真实存在的问答条目，
 * 由 {@code InterviewReportGenerator} 负责剔除与记日志。
 *
 * @param qaId     该问题出自哪条问答（必须是问答清单里真实存在的编号，如 {@code Q003}）
 * @param question 从面试官原话里收敛出的技术问题（去掉口语与寒暄，但不得改变提问意图）
 * @param answer   参考回答（模型生成，供复盘参考，不是"标准答案"，也不代表候选人原话）
 */
public record ReferenceAnswer(String qaId, String question, String answer) {
}
