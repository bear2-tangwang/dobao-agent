package com.dobao.dobaobackend.interview.dto;

/**
 * 状态查询返回体，供前端做阶段化进度展示。
 *
 * @param interviewId      业务标识
 * @param status           当前状态，取值见 {@code InterviewStatus}
 * @param stageDescription 面向用户的中文阶段说明
 * @param errorMsg         失败原因（成功时为 null）
 * @param audioDurationMs  音频总时长（毫秒），未转写完成时为 null
 * @param speechDurationMs 语音内容时长（毫秒，计费口径），未转写完成时为 null
 * @param speakerCount     识别出的说话人数量，未转写完成时为 null
 * @param sentenceCount    句子数量，未转写完成时为 null
 * @param reportReady      报告是否已可下载
 */
public record InterviewStatusVO(
        String interviewId,
        String status,
        String stageDescription,
        String errorMsg,
        Long audioDurationMs,
        Long speechDurationMs,
        Integer speakerCount,
        Integer sentenceCount,
        boolean reportReady) {
}
