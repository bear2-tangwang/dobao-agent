package com.dobao.dobaobackend.interview.asr.dto;

/**
 * 一次任务查询的结果（对 {@link AsrTaskResponse} 的收敛，供业务层判断）。
 *
 * <p>业务层只需要"成功没成功、结果在哪、失败为什么"三件事，
 * 不必也不应该知道百炼响应里的 {@code output}/{@code results[]} 结构，
 * 因此这里单独收敛一层，避免 API 结构渗进状态机。
 *
 * @param taskStatus       百炼任务状态：PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN
 * @param transcriptionUrl 成功时的结果下载地址（有效期 24 小时，必须立刻下载）
 * @param errorMessage     失败时的可读原因
 */
public record AsrTaskState(String taskStatus, String transcriptionUrl, String errorMessage) {

    /**
     * 是否到达成功终态
     */
    public boolean succeeded() {
        return "SUCCEEDED".equalsIgnoreCase(taskStatus);
    }

    /**
     * 是否到达失败终态（FAILED / UNKNOWN）；其余取值都算"还在跑"
     */
    public boolean failed() {
        if (taskStatus == null) {
            return false;
        }
        String s = taskStatus.toUpperCase();
        return "FAILED".equals(s) || "UNKNOWN".equals(s);
    }
}
