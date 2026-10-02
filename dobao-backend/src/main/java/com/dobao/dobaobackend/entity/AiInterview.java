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
 *
 * <p>与项目其他表的差异（务必注意）：
 * <ul>
 *   <li>主键 {@code id BIGINT NOT NULL} <b>没有 AUTO_INCREMENT</b>，
 *       必须用 {@link IdType#ASSIGN_ID} 由应用侧生成雪花 ID，
 *       否则插入会报 {@code Field 'id' doesn't have a default value}。</li>
 *   <li>时间列由数据库维护：插入靠 {@code DEFAULT CURRENT_TIMESTAMP}，更新靠
 *       {@code ON UPDATE CURRENT_TIMESTAMP} —— 但更新走 {@code updateById} 时<b>仍需显式赋值</b>，
 *       详见 {@link #updateTime} 字段的说明。</li>
 *   <li>{@code transcript_json} / {@code report_json} 是 MySQL {@code JSON} 列，这里用
 *       {@link String} 承载。写入前请先做一次 JSON 合法性校验（步骤 2），
 *       否则非法 JSON 会让 SQL 抛异常并打挂整个事务。</li>
 * </ul>
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
     * 用户标识，本期写占位值 'default'，登录后替换
     */
    @TableField("user_id")
    private String userId;

    /**
     * 关联 ai_file_info.file_id
     */
    @TableField("file_id")
    private String fileId;

    /**
     * 音频内容 SHA-256，用于"同一音频重复上传"的幂等去重（步骤 6）。
     * 光靠 asr_task_id 拦不住重复上传——重复上传会在提交转写之前就新建一条记录。
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
     * 转写句子数。
     *
     * <p><b>为什么单独存一列</b>：状态查询（{@code GET /interview/{id}/status}）需要这个数字，
     * 而它原本是靠"每次请求都重新解析一遍 {@code transcript_json}"算出来的 ——
     * 实测 20 分钟音频的原始 JSON 里有 259 句、约 80% 是用不到的逐字 {@code words[]}，
     * 前端轮询期间会反复做全量反序列化。这两个数字在转写落库那一刻就已经知道了，
     * 顺手写进列里，状态查询就**完全不需要碰 JSON**。
     */
    @TableField("sentence_count")
    private Integer sentenceCount;

    /**
     * 识别出的说话人数（与 {@link #sentenceCount} 同时写入，用途相同）
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
     * 创建时间。
     *
     * <p>插入时无需赋值：本列是 {@code DEFAULT CURRENT_TIMESTAMP}，而 MyBatis-Plus 默认按
     * {@code FieldStrategy.NOT_NULL} 生成 SQL，字段为 null 时不会拼进 INSERT，由 MySQL 填默认值。
     * 代价是插入后 Java 对象里该字段仍是 null，若要回给前端需重新查一次。
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>⚠️ <b>走 {@code updateById} 时必须显式赋值</b>，否则该列会一直停在旧值。原因有两层，均已实测：
     * <ol>
     *   <li>MyBatis-Plus 的 {@code updateById} 对每个字段都是"非空才拼进 SET"（默认
     *       {@code FieldStrategy.NOT_NULL}）。从库里查出来的实体带着非空 {@code updateTime}，
     *       于是旧值被显式写回；</li>
     *   <li>MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} <b>在该列被显式赋值时不生效</b>，
     *       只有语句里完全不出现该列时才会自动更新。</li>
     * </ol>
     *
     * <p>两种正确写法，任选其一：
     * <pre>{@code
     * // 写法一：读改写时显式赋值
     * record.setStatus(...);
     * record.setUpdateTime(LocalDateTime.now());
     * interviewMapper.updateById(record);
     *
     * // 写法二（推荐）：列级更新，该列交给 DB 维护
     * interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
     *         .eq(AiInterview::getInterviewId, id)
     *         .set(AiInterview::getStatus, ...));   // SET 里没有 update_time，ON UPDATE 才会生效
     * }</pre>
     *
     * <p>另有两点实测行为需要注意：① 若某次更新的值和当前值完全相同（行未发生实际变化），
     * MySQL 不会刷新该列，别误判为 bug；② 插入时无需赋值，靠列默认值。
     */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
