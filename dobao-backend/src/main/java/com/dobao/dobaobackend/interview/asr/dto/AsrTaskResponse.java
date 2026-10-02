package com.dobao.dobaobackend.interview.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 百炼异步任务响应，提交与查询共用同一结构：
 * <ul>
 *   <li>提交：{@code POST {base}/services/audio/asr/transcription} → {@code output.task_id}</li>
 *   <li>查询：{@code GET {base}/tasks/{task_id}} → {@code output.task_status} / {@code output.results[]}</li>
 * </ul>
 *
 * <p>只建模真正用到的字段（{@code @JsonIgnoreProperties} 已放开未知字段），
 * 状态判断与取值收敛在 {@link AsrTaskState}，本记录只负责反序列化。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AsrTaskResponse(Output output) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("task_status") String taskStatus,
            List<Result> results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("subtask_status") String subtaskStatus,
            @JsonProperty("transcription_url") String transcriptionUrl,
            String code,
            String message) {
    }

    /**
     * 取第一个子任务的结果下载地址
     */
    public String firstTranscriptionUrl() {
        if (output == null || output.results() == null || output.results().isEmpty()) {
            return null;
        }
        return output.results().get(0).transcriptionUrl();
    }

    /**
     * 取子任务的失败原因（成功时返回 null）
     */
    public String firstErrorMessage() {
        if (output == null || output.results() == null || output.results().isEmpty()) {
            return null;
        }
        Result r = output.results().get(0);
        if (r.code() == null && r.message() == null) {
            return null;
        }
        return "subtask_status=" + r.subtaskStatus() + ", code=" + r.code() + ", message=" + r.message();
    }
}
