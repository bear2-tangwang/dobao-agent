package com.dobao.dobaobackend.entity.record;

/**
 * 面试记录状态机：UPLOADED → TRANSCRIBING → TRANSCRIBED → ANALYZING → READY，
 * 任一环节失败 → FAILED（可重试，无需重新上传）。
 *
 * <p>实体字段沿用项目现有风格（{@code AiFileInfo.status} / {@code AiPptInst.status} 都是
 * {@code String}），本枚举用于业务判断，避免魔法字符串散落各处。
 *
 * <p>面向用户的文案（{@link #description()}）也放在这里：SSE 进度流与
 * {@code GET /interview/{id}/status} 是同一件事的两条通道，文案只能有一份，
 * 否则"走流"和"走轮询"看到的措辞会不一样。
 */
public enum InterviewStatus {

    /**
     * 已上传，等待提交转写
     */
    UPLOADED,

    /**
     * 转写中（百炼异步任务已提交，等待轮询）
     */
    TRANSCRIBING,

    /**
     * 转写完成，文字稿已落库
     */
    TRANSCRIBED,

    /**
     * 说话人角色判定 / 问答清单整理 / 报告生成中
     */
    ANALYZING,

    /**
     * 报告已生成并落 MinIO
     */
    READY,

    /**
     * 失败，可重试
     */
    FAILED;

    /** 状态列是 String，取值非法时的兜底文案 */
    private static final String UNKNOWN_DESCRIPTION = "未知状态";

    /**
     * 状态 → 面向用户的一句话（SSE 的 progress 帧与 {@code /status} 的 stageDescription 共用）
     */
    public String description() {
        return switch (this) {
            case UPLOADED -> "已接收录音，正在提交转写任务…";
            case TRANSCRIBING -> "正在转写（识别说话人与时间戳）…";
            case TRANSCRIBED -> "转写完成，文字稿已就绪，即将进入分析…";
            case ANALYZING -> "正在分析（判定说话人角色、整理问答清单、生成参考回答与总结）…";
            case READY -> "报告已生成，可查看与下载";
            case FAILED -> "处理失败，可重试";
        };
    }

    /**
     * 状态字符串 → 枚举；为空或取值非法时返回 null（不抛异常，由调用方兜底）
     */
    public static InterviewStatus parse(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 状态字符串 → 面向用户的一句话；为空或取值非法时返回 {@value #UNKNOWN_DESCRIPTION}
     */
    public static String describe(String status) {
        InterviewStatus parsed = parse(status);
        return parsed == null ? UNKNOWN_DESCRIPTION : parsed.description();
    }
}
