package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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

    /**
     * 未登录/无上下文时的兜底用户，与各表 user_id 列的 DEFAULT 'default' 保持一致。
     * 正常情况下由 {@code github.oauth.default-user-id} 配置提供（见 {@link #currentUserId()}）。
     */
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
    private final InterviewSessionRecorder sessionRecorder;
    private final AiSessionService sessionService;
    private final GithubOAuthProperties authProperties;

    /**
     * 取当前登录用户ID（未登录时回退配置的兜底用户）。
     *
     * <p>只在请求线程里可靠；异步链路（转写轮询、报告生成）里是兜底值，
     * 那些地方改用「由 interviewId 反查 ai_interview.user_id」拿归属。
     */
    private String currentUserId() {
        return UserContext.getUserIdOrDefault(authProperties.getDefaultUserId());
    }

    /**
     * 上传面试录音。
     *
     * <p>幂等规则：先算音频内容 SHA-256，命中已有记录且那条记录不是 FAILED 时直接复用，
     * 不重复上传、不重复计费。注意<b>不能只靠 {@code asr_task_id} 判重</b>——它在提交转写
     * 之前是 NULL，拦不住重复上传。
     *
     * <p>本方法同时负责"让这场面试出现在会话列表里"：拿到 interviewId 后立刻写一行
     * {@code ai_session}（见 {@link InterviewSessionRecorder}）。<b>幂等命中的分支也要写</b>——
     * 否则把已上传过的录音传到另一个会话里，那个会话永远不会出现这场面试。
     *
     * <p><b>已知代价（刻意取舍）</b>：会话行与 {@code ai_interview} 同事务，会话行写入失败会
     * 整体回滚，但 MinIO 上已上传的音频对象不参与回滚（落盘发生在
     * {@link FileManageService#uploadFile} 内部、事务之外），因此这种回滚会留下一个
     * 没有任何 DB 引用的孤儿对象。相比"上传成功却查不到记录"，这里选择让 DB 保持一致，
     * 孤儿对象由 MinIO 生命周期策略与后续清理任务兜底。
     *
     * @param conversationId 前端会话ID（可选；为空时退化为"只写 ai_interview"）
     * @param file           上传的音频（mp3/wav/m4a/aac/flac/amr，≤80MB）
     * @return 上传结果（含 interviewId）
     */
    @Transactional(rollbackFor = Exception.class)
    public InterviewUploadVO upload(String conversationId, MultipartFile file) {
        // 上传接口始终在请求线程里执行，可以从 UserContext 取归属用户
        String userId = currentUserId();
        // 上传前置校验：格式 + 大小
        validate(file);

        String audioHash = sha256(file);
        AiInterview existing = findByAudioHash(audioHash);
        // 去重：已有记录且不是 FAILED 状态时直接复用，不重复转写、不重复计费
        if (existing != null && !InterviewStatus.FAILED.name().equals(existing.getStatus())) {
            log.info("音频内容命中已有记录，直接复用: audioHash={}, interviewId={}, status={}",
                    audioHash, existing.getInterviewId(), existing.getStatus());
            // 会话行记到"当前上传者"名下：同一段录音被另一个人上传时，
            // 会话列表该出现在他自己的列表里，而不是原上传者的
            sessionRecorder.recordUploaded(conversationId, existing.getInterviewId(), existing.getFileName(), userId);
            backfillReusedSummary(existing);
            return new InterviewUploadVO(existing.getInterviewId(), existing.getStatus(),
                    existing.getFileName(), existing.getFileSize(), true);
        }

        // 落 MinIO + ai_file_info 表 + ai_interview 记录表（文件归属当前用户）
        FileInfo fileInfo = fileManageService.uploadFile(file, userId);
        String objectName = FileManageService.generateObjectName(fileInfo.getFileId(), fileInfo.getFileType());

        AiInterview record = new AiInterview();
        record.setInterviewId(UUID.randomUUID().toString());
        record.setUserId(userId);
        record.setFileId(fileInfo.getFileId());
        record.setAudioHash(audioHash);
        record.setFileName(fileInfo.getFileName());
        record.setFileSize(fileInfo.getFileSize());
        record.setAudioUrl(fileInfo.getMinioPath());
        record.setAsrModel(properties.getAsr().getModel());
        record.setStatus(InterviewStatus.UPLOADED.name());
        interviewMapper.insert(record);

        // 让这场面试出现在会话列表里（与 ai_interview 同一个事务）
        sessionRecorder.recordUploaded(conversationId, record.getInterviewId(), record.getFileName(), userId);

        log.info("面试记录已创建: interviewId={}, fileId={}, objectName={}, size={}B",
                record.getInterviewId(), fileInfo.getFileId(), objectName, fileInfo.getFileSize());

        // 提交异步任务：取音频 URL（dev 要上传到百炼临时存储）+ 提交转写任务
        taskService.submitTranscriptionAsync(record.getInterviewId(), objectName);

        return new InterviewUploadVO(record.getInterviewId(), record.getStatus(),
                record.getFileName(), record.getFileSize(), false);
    }

    /**
     * 复用时补齐会话摘要。
     *
     * <p>命中已有记录时不会再走状态机（既不重跑转写也不重跑分析），所以这场面试的会话行
     * 不会有人替它回填摘要 —— 只能在这里按它当前的状态补一次，否则 {@code answer} 会永远停在
     * "进行中"占位（并作为一条没有配对回答的 UserMessage 进 chat memory）。
     */
    private void backfillReusedSummary(AiInterview existing) {
        String interviewId = existing.getInterviewId();
        if (InterviewStatus.READY.name().equals(existing.getStatus())) {
            try {
                InterviewReport report = report(interviewId);
                sessionRecorder.markReady(interviewId,
                        report.qaList() == null ? 0 : report.qaList().size(),
                        report.referenceAnswers() == null ? 0 : report.referenceAnswers().size());
            } catch (Exception e) {
                // 报告 JSON 解析不了也要给个完成的说法，不能让这行停在"进行中"
                log.warn("复用记录的报告不可解析，按无计数回填完成摘要: interviewId={}, err={}",
                        interviewId, e.getMessage());
                sessionRecorder.markReady(interviewId, 0, 0);
            }
            return;
        }
        // FAILED 不会走到这里（命中条件排除了它：失败会重新提交转写）；其余状态都还在处理中
        sessionRecorder.markRunning(interviewId);
    }

    /**
     * 按业务标识取记录（不做归属校验）。
     *
     * <p>只允许在"归属已由上游确认"或"系统内部链路"里使用；
     * 任何对外接口都应该用 {@link #requireOwnedByInterviewId(String, String)}。
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
     * 按「业务标识 + 归属用户」取记录。
     *
     * <p>对外的查询接口（状态/报告/下载/重试）一律走这里：{@code interviewId} 是 UUID，
     * 但"拿到别人的 ID 就能读别人的面试报告"仍是越权，所以归属必须进 WHERE。
     *
     * <p>归属不符时报"不存在"而不是 403 —— 不回 403 是为了不泄露"这个 ID 真实存在"。
     */
    private AiInterview requireOwnedByInterviewId(String interviewId, String userId) {
        AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .eq(AiInterview::getUserId, userId));
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
        AiInterview record = requireOwnedByInterviewId(interviewId, currentUserId());
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
        AiInterview record = requireOwnedByInterviewId(interviewId, currentUserId());
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
        AiInterview record = requireOwnedByInterviewId(interviewId, currentUserId());
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
        AiInterview record = requireOwnedByInterviewId(interviewId, currentUserId());
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
     * 级联删除若干场面试：<b>先删 MinIO 对象，再删 ai_interview 记录</b>。
     *
     * <p>顺序是刻意的：对象删除失败就抛异常、由调用方（删除会话）的事务整体回滚，
     * 用户看到"删除失败"；而 MinIO 的 removeObject 对不存在的 key 是幂等的，
     * 所以"对象已删、事务回滚"之后再重试一次就能收敛。反过来先删库，则可能出现
     * "记录没了、录音还在 MinIO 里"这种既不可见又删不掉的状态。
     *
     * <p><b>本方法要求调用方已开启事务</b>：它自己不带 {@code @Transactional}，
     * "先删对象后删库"的原子性靠 {@code SessionController.deleteSession} 的事务提供。
     * 直接调用它（没有外层事务）会退化成两步各自提交，中途失败就留下半状态。
     *
     * <p><b>仍被别的会话引用时只解引用</b>：同一段录音可以在多个会话里各上传一次
     * （音频哈希幂等命中同一个 interviewId，见 {@link #upload}），删其中一个会话不该
     * 带走另一个会话仍在用的录音与报告 —— 那种情况下只跳过删除（不删 MinIO 对象、
     * 不删 {@code ai_interview} 行），由删掉会话行本身来完成"解引用"。
     *
     * @param interviewIds   面试ID（会话行里 agent_type=interview 的 fileid）；可为空
     * @param conversationId 正在被删除的那个会话（用于判断这场面试是否还被别的会话引用）
     */
    public void deleteInterviews(List<String> interviewIds, String conversationId) {
        if (interviewIds == null || interviewIds.isEmpty()) {
            return;
        }
        List<String> objectNames = new ArrayList<>();
        // 真正要删的那批：被别的会话引用的不能进来，否则 delete 会把别人在用的记录删掉
        List<String> deletedIds = new ArrayList<>();
        for (String interviewId : interviewIds) {
            if (referencedByOtherConversation(interviewId, conversationId)) {
                log.info("面试仍被其它会话引用，仅解引用不删对象与记录: interviewId={}, conversationId={}",
                        interviewId, conversationId);
                continue;
            }
            AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                    .eq(AiInterview::getInterviewId, interviewId)
                    // 只能删自己的：别人的 interviewId 传进来时，这里查不到记录，
                    // 于是不会删它的 MinIO 对象，delete 也命中 0 行
                    .eq(AiInterview::getUserId, currentUserId()));
            if (record == null) {
                // 记录本就不存在：没有对象可删，但归入本批（delete 命中 0 行），语义仍是"这场归我们删"
                deletedIds.add(interviewId);
                continue;
            }
            if (StringUtils.hasText(record.getReportFileName())) {
                objectNames.add(record.getReportFileName());
            }
            if (StringUtils.hasText(record.getFileId()) && StringUtils.hasText(record.getFileName())) {
                objectNames.add(FileManageService.generateObjectName(
                        record.getFileId(), extractFileType(record.getFileName())));
            }
            deletedIds.add(interviewId);
        }

        for (String objectName : objectNames) {
            try {
                minioService.deleteFile(objectName);
            } catch (Exception e) {
                throw new IllegalStateException("删除面试文件失败: " + objectName + ", " + e.getMessage(), e);
            }
        }

        if (!deletedIds.isEmpty()) {
            interviewMapper.delete(new LambdaQueryWrapper<AiInterview>()
                    .in(AiInterview::getInterviewId, deletedIds));
        }
        log.info("面试记录已删除: interviewIds={}, 对象数={}", deletedIds, objectNames.size());
    }

    /** 这个 interviewId 是否仍被"别的会话"引用（同一录音可被多个会话各上传一次） */
    private boolean referencedByOtherConversation(String interviewId, String conversationId) {
        return sessionService.count(new LambdaQueryWrapper<AiSession>()
                .eq(AiSession::getAgentType, InterviewSessionRecorder.AGENT_TYPE)
                .eq(AiSession::getFileid, interviewId)
                .ne(AiSession::getSessionId, conversationId)) > 0;
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
