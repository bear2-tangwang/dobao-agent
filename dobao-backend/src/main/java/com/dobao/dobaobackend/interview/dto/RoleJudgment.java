package com.dobao.dobaobackend.interview.dto;

/**
 * 角色判定的 LLM 输出。
 *
 * @param interviewerSpeakerId 面试官对应的说话人编号
 * @param reason               判定依据（仅用于日志与排障；目前没有列存储它）
 */
public record RoleJudgment(Integer interviewerSpeakerId, String reason) {
}
