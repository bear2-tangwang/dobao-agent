package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.InterviewStatusVO;
import com.dobao.dobaobackend.interview.dto.InterviewUploadVO;
import com.dobao.dobaobackend.interview.dto.ReportFile;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 面试总结 · 上传与查询服务。
 *
 * <p>职责边界：本类只做"快路径"——上传落库、查询、把耗时动作交给
 * {@link InterviewTaskService}。状态机的所有"写"都在 {@code InterviewTaskService} 里，
 * 避免两个类互相依赖。
 *
 * <p>上传接口必须在 3 秒内返回（编码方案步骤 2 的验收项），因此这里
 * <b>只落 MinIO + 建记录</b>，取 URL / 提交转写任务一律异步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewService {

    /** 本期没有登录体系，用户标识写占位值（规格 §7.1） */
    private static final String DEFAULT_USER_ID = "default";

    /** 文件名没有可识别扩展名时的占位类型 */
    private static final String UNKNOWN_FILE_TYPE = "unknown";

    /** SHA-256 的流式读取缓冲区大小 */
    private static final int SHA256_BUFFER_SIZE = 8 * 1024;

    private final InterviewProperties properties;
    private final AiInterviewMapper interviewMapper;
    private final FileManageService fileManageService;
    private final MinioService minioService;
    private final InterviewTaskService taskService;
    private final ObjectMapper objectMapper;

    /**
     * 上传面试录音。
     *
     * <p>幂等规则：先算音频内容 SHA-256，命中已有记录且那条记录不是 FAILED 时直接复用，
     * 不重复上传、不重复计费。注意<b>不能只靠 {@code asr_task_id} 判重</b>——它在提交转写
     * 之前是 NULL，拦不住重复上传。
     *
     * @param file 上传的音频（mp3/wav/m4a/aac/flac/amr，≤80MB）
     * @return 上传结果（含 interviewId）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewUploadVO upload(MultipartFile file) {
        // 上传前置校验：格式 + 大小
        validate(file);

        String audioHash = sha256(file);
        AiInterview existing = findByAudioHash(audioHash);
        // 去重：已有记录且不是 FAILED 状态时直接复用，不重复转写、不重复计费
        if (existing != null && !InterviewStatus.FAILED.name().equals(existing.getStatus())) {
            log.info("音频内容命中已有记录，直接复用: audioHash={}, interviewId={}, status={}",
                    audioHash, existing.getInterviewId(), existing.getStatus());
            return new InterviewUploadVO(existing.getInterviewId(), existing.getStatus(),
                    existing.getFileName(), existing.getFileSize(), true);
        }

        // 落 MinIO + ai_file_info 表 + ai_interview 记录表
        FileInfo fileInfo = fileManageService.uploadFile(file);
        String objectName = FileManageService.generateObjectName(fileInfo.getFileId(), fileInfo.getFileType());

        AiInterview record = new AiInterview();
        record.setInterviewId(UUID.randomUUID().toString());
        record.setUserId(DEFAULT_USER_ID);
        record.setFileId(fileInfo.getFileId());
        record.setAudioHash(audioHash);
        record.setFileName(fileInfo.getFileName());
        record.setFileSize(fileInfo.getFileSize());
        record.setAudioUrl(fileInfo.getMinioPath());
        record.setAsrModel(properties.getAsr().getModel());
        record.setStatus(InterviewStatus.UPLOADED.name());
        interviewMapper.insert(record);

        log.info("面试记录已创建: interviewId={}, fileId={}, objectName={}, size={}B",
                record.getInterviewId(), fileInfo.getFileId(), objectName, fileInfo.getFileSize());

        // 提交异步任务：取音频 URL（dev 要上传到百炼临时存储）+ 提交转写任务
        taskService.submitTranscriptionAsync(record.getInterviewId(), objectName);

        return new InterviewUploadVO(record.getInterviewId(), record.getStatus(),
                record.getFileName(), record.getFileSize(), false);
    }

    /**
     * 按业务标识取记录，不存在则抛异常。
     */
    private AiInterview requireByInterviewId(String interviewId) {
        AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId));
        if (record == null) {
            throw new IllegalArgumentException("面试记录不存在: " + interviewId);
        }
        return record;
    }

    /**
     * 按音频内容哈希查记录。
     */
    private AiInterview findByAudioHash(String audioHash) {
        if (!StringUtils.hasText(audioHash)) {
            return null;
        }
        return interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getAudioHash, audioHash)
                .orderByDesc(AiInterview::getId)
                .last("limit 1"));
    }

    /**
     * 查询状态（供前端做阶段化进度展示）。
     *
     * <p><b>这里绝不能解析 {@code transcript_json}</b>：前端从上传那一刻起就在轮询这个接口，
     * 而原始 JSON 里约 80% 是用不到的逐字 {@code words[]}（实测 5.5 分钟音频原始 JSON 80KB、
     * 句子级投影只有 15KB）。早期版本为了拿"句子数 / 说话人数"每次请求都全量反序列化，
     * 结果是每 3~5 秒一次 JSON 解析 + 一行 INFO 日志，把日志刷满、看着像卡死。
     * 现在这两个数字来自转写落库时写入的 {@code sentence_count} / {@code speaker_count} 列。
     */
    public InterviewStatusVO status(String interviewId) {
        AiInterview record = requireByInterviewId(interviewId);
        boolean reportReady = StringUtils.hasText(record.getReportFileUrl());
        return new InterviewStatusVO(
                record.getInterviewId(),
                record.getStatus(),
                InterviewStatus.describe(record.getStatus()),
                record.getErrorMsg(),
                record.getAudioDurationMs(),
                record.getSpeechDurationMs(),
                record.getSpeakerCount(),
                record.getSentenceCount(),
                reportReady);
    }

    /**
     * 取结构化报告。
     */
    public InterviewReport report(String interviewId) {
        AiInterview record = requireByInterviewId(interviewId);
        if (!StringUtils.hasText(record.getReportJson())) {
            throw new IllegalStateException("报告尚未生成，当前状态: " + record.getStatus());
        }
        try {
            return objectMapper.readValue(record.getReportJson(), InterviewReport.class);
        } catch (Exception e) {
            throw new IllegalStateException("报告解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 取报告文件（Markdown）用于下载。
     */
    public ReportFile reportFile(String interviewId) {
        AiInterview record = requireByInterviewId(interviewId);
        if (!StringUtils.hasText(record.getReportFileName())) {
            throw new IllegalStateException("报告文件尚未生成，当前状态: " + record.getStatus());
        }
        try {
            return new ReportFile(record.getReportFileName(),
                    minioService.downloadFile(record.getReportFileName()));
        } catch (Exception e) {
            throw new IllegalStateException("报告文件下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重试：有文字稿就只重跑分析（不重新转写），否则重新提交转写。
     *
     * <p>它同时是唯一的"重新生成报告"入口：有文字稿时只依赖已落库的
     * {@code transcript_json}，<b>不会重新转写</b>、不重复计费
     * —— 这是编码方案步骤 4"重新生成报告不重复转写"的验收项。
     *
     * <p>这样"转写失败"和"分析失败"两种失败都能原地重试，且都不需要用户重新上传音频
     * （音频在 MinIO 里保留 30 天）。
     */
    public void retry(String interviewId) {
        AiInterview record = requireByInterviewId(interviewId);
        if (StringUtils.hasText(record.getTranscriptJson())) {
            log.info("重试：复用已有文字稿，仅重跑分析: interviewId={}", interviewId);
            taskService.analyzeAsync(interviewId);
            return;
        }
        if (!StringUtils.hasText(record.getFileId())) {
            throw new IllegalStateException("记录缺少 fileId，无法重新提交转写，请重新上传");
        }
        String objectName = FileManageService.generateObjectName(
                record.getFileId(), extractFileType(record.getFileName()));
        log.info("重试：重新提交转写: interviewId={}, objectName={}", interviewId, objectName);
        taskService.retryTranscriptionAsync(interviewId, objectName);
    }

    /**
     * 上传前置校验：格式 + 大小。
     *
     * <p>时长校验（≤1.5 小时）放在转写返回之后做——pom 里没有音频解析库，
     * 上传阶段无法读时长；这是刻意的取舍，见分析报告 §4.10。
     */
    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileType = extractFileType(file.getOriginalFilename());
        if (!FileInfo.isAudioType(fileType)) {
            throw new IllegalArgumentException(
                    "仅支持音频文件（mp3/wav/m4a/aac/flac/amr），当前文件类型: " + fileType);
        }
        long maxBytes = properties.getAsr().getMaxAudioBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(String.format(
                    "音频文件过大：%.1fMB，上限 %.0fMB", file.getSize() / 1048576.0, maxBytes / 1048576.0));
        }
    }

    /**
     * 从文件名提取扩展名（小写）
     */
    private static String extractFileType(String fileName) {
        if (fileName == null) {
            return UNKNOWN_FILE_TYPE;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return fileName.substring(dot + 1).toLowerCase();
        }
        return UNKNOWN_FILE_TYPE;
    }

    /**
     * 流式计算音频内容的 SHA-256（按 {@value #SHA256_BUFFER_SIZE} 字节缓冲区读，不把整个文件读进内存）
     */
    private static String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[SHA256_BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算音频哈希失败: " + e.getMessage(), e);
        }
    }
}
