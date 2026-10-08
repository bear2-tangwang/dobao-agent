package com.dobao.dobaobackend.entity.record;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文件元数据模型
 * 存储文件的基本信息和解析后的内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInfo {

    /**
     * 归属用户ID（db_user.user_id）。
     *
     * <p>名字与实体 {@code AiFileInfo.userId} 一致，这样
     * {@code FileInfoServiceImpl} 里的 {@code BeanUtils.copyProperties} 能自动带过去。
     */
    private String userId;

    /**
     * 文件唯一标识
     */
    private String fileId;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 文件类型（pdf/doc/docx/txt/png/jpg等）
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * MinIO中的存储路径
     */
    private String minioPath;

    /**
     * 解析后的纯文本内容
     */
    private String extractedText;

    /**
     * 文件上传时间
     */
    private LocalDateTime createdAt;

    /**
     * 会话ID（可选，用于关联特定会话）
     */
    private String conversationId;

    /**
     * 文件状态
     */
    @Builder.Default
    private FileStatus status = FileStatus.PENDING;

    /**
     * 是否已向量化（大文件标识）
     * 0-未向量化，1-已向量化
     */
    @Builder.Default
    private Integer embed = 0;

    /**
     * 文件状态枚举
     */
    public enum FileStatus {
        /**
         * 待处理
         */
        PENDING,
        /**
         * 处理中
         */
        PROCESSING,
        /**
         * 处理成功
         */
        SUCCESS,
        /**
         * 处理失败
         */
        FAILED
    }

    /**
     * 判断文件是否已处理完成
     */
    public boolean isProcessed() {
        return status == FileStatus.SUCCESS && extractedText != null;
    }

    /**
     * 判断文件是否为图片
     */
    public boolean isImage() {
        return ("png".equalsIgnoreCase(fileType)
                || "jpg".equalsIgnoreCase(fileType)
                || "jpeg".equalsIgnoreCase(fileType)
                || "gif".equalsIgnoreCase(fileType)
                || "bmp".equalsIgnoreCase(fileType));
    }

    /**
     * 判断文件是否为音频（面试录音）
     */
    public boolean isAudio() {
        return isAudioType(fileType);
    }

    /**
     * 判断给定扩展名是否为受支持的音频类型。
     *
     * <p>抽成静态方法是为了让"支持哪些音频格式"只有一处定义：实体侧 {@link #isAudio()} 与
     * 面试上传校验（{@code InterviewService}）共用它，避免两处清单漂移。
     *
     * @param fileType 扩展名（不含点），可为 null
     */
    public static boolean isAudioType(String fileType) {
        return ("mp3".equalsIgnoreCase(fileType)
                || "wav".equalsIgnoreCase(fileType)
                || "m4a".equalsIgnoreCase(fileType)
                || "aac".equalsIgnoreCase(fileType)
                || "flac".equalsIgnoreCase(fileType)
                || "amr".equalsIgnoreCase(fileType));
    }

    /**
     * 判断文件是否为PDF
     */
    public boolean isPdf() {
        return "pdf".equalsIgnoreCase(fileType);
    }

    /**
     * 判断文件是否为Word文档
     */
    public boolean isWord() {
        return ("doc".equalsIgnoreCase(fileType)
                || "docx".equalsIgnoreCase(fileType));
    }
}
