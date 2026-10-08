package com.dobao.dobaobackend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试记录主表实体，对应 {@code ai_interview}。
 */
@Data
@TableName("ai_interview")
public class AiInterview {

    /**
     * 主键（雪花 ID，应用侧生成）
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务唯一标识（UUID），对外暴露的 interviewId
     */
    @TableField("interview_id")
    private String interviewId;

    /**
     * 归属用户ID（db_user.user_id）
     */
    @TableField("user_id")
    private String userId;

    /**
     * 关联 ai_file_info.file_id（面试录音同时以文件记录存在）
     */
    @TableField("file_id")
    private String fileId;

    /**
     * 音频内容 SHA-256，用于"同一音频重复上传"的幂等去重：
     * 重复上传会在提交转写之前就新建一条记录，光靠 asr_task_id 拦不住。
     */
    @TableField("audio_hash")
    private String audioHash;

    /**
     * 原始文件名
     */
    @TableField("file_name")
    private String fileName;

    /**
     * 文件大小（字节）
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 音频总时长（毫秒），取自 ASR 结果的 properties.original_duration_in_milliseconds
     */
    @TableField("audio_duration_ms")
    private Long audioDurationMs;

    /**
     * 语音内容时长（毫秒，计费口径），取自 transcripts[].content_duration_in_milliseconds
     */
    @TableField("speech_duration_ms")
    private Long speechDurationMs;

    /**
     * MinIO 音频地址
     */
    @TableField("audio_url")
    private String audioUrl;

    /**
     * 百炼任务 ID，幂等/续跑用
     */
    @TableField("asr_task_id")
    private String asrTaskId;

    /**
     * 实际使用的转写模型名
     */
    @TableField("asr_model")
    private String asrModel;

    /**
     * 状态，取值见 {@link InterviewStatus}
     */
    @TableField("status")
    private String status;

    /**
     * 失败原因（可读）
     */
    @TableField("error_msg")
    private String errorMsg;

    /**
     * 判定出的面试官说话人编号
     */
    @TableField("interviewer_speaker_id")
    private Integer interviewerSpeakerId;

    /**
     * ASR 原始结果 JSON
     */
    @TableField("transcript_json")
    private String transcriptJson;

    /**
     * 归一化纯文本
     */
    @TableField("transcript_text")
    private String transcriptText;

    /**
     * 转写句子数
     */
    @TableField("sentence_count")
    private Integer sentenceCount;

    /**
     * 识别出的说话人数（与 {@link #sentenceCount} 同时写入）
     */
    @TableField("speaker_count")
    private Integer speakerCount;

    /**
     * 报告结构化数据 JSON
     */
    @TableField("report_json")
    private String reportJson;

    /**
     * 报告 Markdown 在 MinIO 的地址
     */
    @TableField("report_file_url")
    private String reportFileUrl;

    /**
     * 报告下载文件名
     */
    @TableField("report_file_name")
    private String reportFileName;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
