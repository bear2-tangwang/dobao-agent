package com.dobao.dobaobackend.interview.dto;

/**
 * 上传接口返回体。
 *
 * @param interviewId 业务标识，后续所有接口都用它
 * @param status      初始状态，正常为 {@code UPLOADED}
 * @param fileName    原始文件名
 * @param fileSize    文件大小（字节）
 * @param reused      是否命中"同一音频重复上传"而复用了已有记录（幂等）
 */
public record InterviewUploadVO(
        String interviewId,
        String status,
        String fileName,
        Long fileSize,
        boolean reused) {
}
