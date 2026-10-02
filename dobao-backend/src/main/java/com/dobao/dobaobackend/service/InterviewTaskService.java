package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewReportGenerator;
import com.dobao.dobaobackend.interview.InterviewReportRenderer;
import com.dobao.dobaobackend.interview.QaListBuilder;
import com.dobao.dobaobackend.interview.SpeakerRoleResolver;
import com.dobao.dobaobackend.interview.TranscriptNormalizer;
import com.dobao.dobaobackend.interview.asr.DashScopeAsrClient;
import com.dobao.dobaobackend.interview.asr.dto.AsrTaskState;
import com.dobao.dobaobackend.interview.audio.AudioUrlProvider;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.NormalizedTranscript;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.RoleJudgment;
import com.dobao.dobaobackend.interview.event.InterviewTranscribedEvent;
import com.dobao.dobaobackend.interview.progress.InterviewProgressHub;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 面试总结 · 异步任务编排与状态机。
 *
 * <p><b>两个关键设计（都来自步骤 1 的分析结论）</b>：
 * <ol>
 *   <li><b>提交与分析走 {@code @Async("interviewExecutor")}</b>，不占用 Tomcat 线程，
 *       保证上传接口 3 秒内返回。</li>
 *   <li><b>轮询不由线程池承担</b>：转写最长 30 分钟，若用阻塞轮询会占满 8 个线程、
 *       让第 9 个任务静默排队、实例重启即丢。因此轮询交给 {@code @Scheduled} 扫描
 *       {@code status=TRANSCRIBING} 的记录，每次只"查一次"，天然支持重启续跑，
 *       并顺带实现超时兜底。</li>
 * </ol>
 *
 * <p><b>状态流转一律走 {@link #updateStatus}</b>：用 {@code LambdaUpdateWrapper} 做列级更新，
 * SET 子句里不出现 {@code update_time}，于是 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 会
 * 自动维护它。若这里改成"查出来 → 改字段 → updateById"，MyBatis-Plus 会把旧值显式写回，
 * 反而压制 ON UPDATE（实测行为，见分析报告 §10）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewTaskService {

    private final InterviewProperties properties;
    private final AiInterviewMapper interviewMapper;
    private final AudioUrlProvider audioUrlProvider;
    private final DashScopeAsrClient asrClient;
    private final TranscriptNormalizer transcriptNormalizer;
    private final SpeakerRoleResolver speakerRoleResolver;
    private final QaListBuilder qaListBuilder;
    private final InterviewReportGenerator reportGenerator;
    private final InterviewReportRenderer reportRenderer;
    private final MinioService minioService;
    private final ApplicationEventPublisher eventPublisher;
    private final InterviewProgressHub progressHub;
    private final ObjectMapper objectMapper;

    /**
     * 异步：取音频 URL（dev 需上传到百炼临时存储）并提交转写任务。
     *
     * <p>注意本方法必须由<b>其他 Bean</b> 调用（{@code InterviewService#upload}），
     * 同类内部调用会绕过 Spring 代理、静默变成同步执行。
     */
    @Async("interviewExecutor")
    public void submitTranscriptionAsync(String interviewId, String objectName) {
        try {
            submitTranscription(interviewId, objectName);
        } catch (Exception e) {
            log.error("提交转写任务失败: interviewId={}", interviewId, e);
            updateStatus(interviewId, InterviewStatus.FAILED, "提交转写任务失败: " + e.getMessage());
        }
    }

    /**
     * 重新提交转写（失败重试用；与分析重试区分开：有文字稿时只重跑分析）。
     */
    @Async("interviewExecutor")
    public void retryTranscriptionAsync(String interviewId, String objectName) {
        try {
            submitTranscription(interviewId, objectName);
        } catch (Exception e) {
            log.error("重试提交转写任务失败: interviewId={}", interviewId, e);
            updateStatus(interviewId, InterviewStatus.FAILED, "重试提交转写任务失败: " + e.getMessage());
        }
    }

    /**
     * 提交转写的实际动作（异步入口调用）。
     */
    private void submitTranscription(String interviewId, String objectName) {
        // 更新状态为转写中
        updateStatus(interviewId, InterviewStatus.TRANSCRIBING, null);

        // 从 MinIO 取音频地址（dev 走百炼临时存储，prod 走公网地址），再提交转写任务
        String audioUrl = audioUrlProvider.provide(objectName);
        String taskId = asrClient.submit(audioUrl, null);

        // 更新面试表，记录转写任务 ID
        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getAsrTaskId, taskId));
        log.info("转写任务已提交并落库: interviewId={}, taskId={}", interviewId, taskId);
    }

    /**
     * 定时轮询转写中的任务（间隔取 {@code interview.asr.poll-interval-ms}，默认 5 秒）。
     *
     * <p>超时判断必须放在 SQL 里做：MySQL 时间是 UTC，比 JDK 早 8 小时。
     * 若在 Java 里拿 {@code update_time} 与 {@code LocalDateTime.now()} 相减，
     * 刚提交的任务会被立刻判定为"超时 8 小时"。把比较交给 MySQL 后两侧同一个时钟，天然正确。
     */
    @Scheduled(fixedDelayString = "${interview.asr.poll-interval-ms:5000}")
    public void pollTranscribingTasks() {
        long timeoutMs = properties.getAsr().getPollTimeoutMs();

        // 1. 先做超时兜底（纯 SQL 判断）
        for (AiInterview task : queryTranscribing(true, timeoutMs)) {
            updateStatus(task.getInterviewId(), InterviewStatus.FAILED, String.format(
                    "转写超时（超过 %d 分钟未完成），已强制置为失败，可重试",
                    Duration.ofMillis(timeoutMs).toMinutes()));
            log.warn("转写超时兜底生效: interviewId={}", task.getInterviewId());
        }

        // 2. 再轮询尚未超时的任务
        List<AiInterview> tasks = queryTranscribing(false, timeoutMs);
        if (tasks.isEmpty()) {
            return;
        }
        log.info("轮询转写任务: count={}", tasks.size());
        for (AiInterview task : tasks) {
            try {
                pollOne(task); // 推进每个转写任务
            } catch (Exception e) {
                // 单个任务异常不能影响其他任务，也不能让调度线程挂掉
                log.error("轮询任务异常: interviewId={}", task.getInterviewId(), e);
            }
        }
    }

    /**
     * 查询"转写中且已提交任务"的记录。
     *
     * <p>超时比较用 {@code update_time}（为空时回退 {@code create_time}）；
     * 秒数以 {@code {0}} 占位符传参，不把时间值拼进 SQL。
     *
     * @param timedOut  true=取已超时的（兜底置 FAILED）；false=取尚未超时的（继续轮询）
     * @param timeoutMs 超时上限（毫秒）
     */
    private List<AiInterview> queryTranscribing(boolean timedOut, long timeoutMs) {
        long timeoutSeconds = Duration.ofMillis(timeoutMs).toSeconds();
        String compare = timedOut ? "<" : ">=";
        return interviewMapper.selectList(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getStatus, InterviewStatus.TRANSCRIBING.name())
                .isNotNull(AiInterview::getAsrTaskId)
                .apply("COALESCE(update_time, create_time) " + compare
                        + " DATE_SUB(NOW(), INTERVAL {0} SECOND)", timeoutSeconds));
    }

    /**
     * 轮询单个转写任务，更新状态并落库结果
     */
    private void pollOne(AiInterview task) {
        String interviewId = task.getInterviewId();

        AsrTaskState state = asrClient.query(task.getAsrTaskId()); // 请求百炼查询转写任务状态
        if (state.failed()) {
            log.error("转写任务失败: interviewId={}, errorMessage={}", interviewId, state.errorMessage());
            updateStatus(interviewId, InterviewStatus.FAILED,
                    "转写任务失败: " + state.errorMessage());
            return;
        }
        if (!state.succeeded()) {
            return;
        }
        if (!StringUtils.hasText(state.transcriptionUrl())) {
            log.error("转写成功但未返回结果下载地址: interviewId={}", interviewId);
            updateStatus(interviewId, InterviewStatus.FAILED, "转写成功但未返回结果下载地址");
            return;
        }

        // 结果链接 24 小时失效，必须立刻下载并落库
        String asrJson = asrClient.downloadResult(state.transcriptionUrl());
        persistTranscript(task, asrJson);
    }

    /**
     * 落库转写结果并推进状态。
     */
    private void persistTranscript(AiInterview task, String asrJson) {
        String interviewId = task.getInterviewId();

        // JSON 合法性校验：transcript_json 是 MySQL JSON 列，非法 JSON 会让 SQL 抛异常
        try {
            objectMapper.readTree(asrJson);
        } catch (Exception e) {
            updateStatus(interviewId, InterviewStatus.FAILED, "转写结果不是合法 JSON，已跳过落库: " + e.getMessage());
            return;
        }

        // 归一化：原始 JSON → 内存句子列表（纯转换，不落库）
        NormalizedTranscript transcript = transcriptNormalizer.normalize(asrJson);

        // 时长上限校验（1.5 小时）：上传阶段没有音频解析库读不到时长，只能在这里补
        Long audioMs = transcript.audioDurationMs();
        long maxDurationMs = properties.getAsr().getMaxAudioDurationMs();
        if (audioMs != null && audioMs > maxDurationMs) {
            updateStatus(interviewId, InterviewStatus.FAILED, String.format(
                    "音频时长 %.1f 分钟超过上限 %.0f 分钟", audioMs / 60000.0, maxDurationMs / 60000.0));
            return;
        }

        // 落库转写结果：句子数 / 说话人数一并落列，状态查询就不必再反序列化大 JSON
        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getTranscriptJson, asrJson)
                .set(AiInterview::getTranscriptText, transcript.plainText())
                .set(AiInterview::getAudioDurationMs, transcript.audioDurationMs())
                .set(AiInterview::getSpeechDurationMs, transcript.speechDurationMs())
                .set(AiInterview::getSentenceCount, transcript.size())
                .set(AiInterview::getSpeakerCount, transcript.speakerIds().size())
                .set(AiInterview::getStatus, InterviewStatus.TRANSCRIBED.name())
                .set(AiInterview::getErrorMsg, null));

        log.info("转写完成并落库: interviewId={}, 句子数={}, 说话人数={}",
                interviewId, transcript.size(), transcript.speakerIds().size());

        // 实时通道：把"转写完成 + 句数/人数"立刻推给页面（轮询时这个信息要等下一次查询才看到）
        progressHub.publishProgress(interviewId, "transcribed",
                String.format("转写完成：%d 句 / %d 位说话人，文字稿已就绪",
                        transcript.size(), transcript.speakerIds().size()), null);

        // 用事件而不是同类内直调：@Async 监听方才会真正异步（见事件类注释）
        eventPublisher.publishEvent(new InterviewTranscribedEvent(interviewId));
    }

    /**
     * 统一的状态流转入口（列级更新，让数据库维护 {@code update_time}）。
     *
     * <p>这里同时是 SSE 的推送点：状态是"已经落库的事实"，推给前端只是通知，
     * 所以推送放在 update 之后，推失败也不会影响状态机（前端还有轮询兜底）。
     * 改状态只有本类会做，故不对外开放。
     *
     * @param errorMsg 失败原因；传 null 会显式清空该列（重试时用得上）
     */
    private void updateStatus(String interviewId, InterviewStatus status, String errorMsg) {
        int rows = interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getStatus, status.name())
                .set(AiInterview::getErrorMsg, errorMsg));
        log.info("状态更新: interviewId={}, status={}, rows={}", interviewId, status, rows);

        progressHub.publishStatus(interviewId, status, errorMsg);
    }


    /**
     * 转写完成事件监听：接上"角色判定 + 问答清单 + 报告生成"。
     *
     * <p>用 {@code @EventListener} 而不是在轮询里直调，是为了让 {@code @Async} 真正生效
     * （同类内直调会绕过 Spring 代理、静默变成同步执行，把调度线程占住几分钟）。
     */
    @Async("interviewExecutor")
    @EventListener
    public void onTranscribed(InterviewTranscribedEvent event) {
        runAnalysis(event.interviewId());
    }

    /**
     * 供外部入口（{@code POST /interview/{id}/retry}，有文字稿时只重跑分析）调用的异步分析入口。
     *
     * <p>必须是独立方法且由<b>其他 Bean</b> 调用，{@code @Async} 才会经代理生效。
     */
    @Async("interviewExecutor")
    public void analyzeAsync(String interviewId) {
        runAnalysis(interviewId);
    }

    /**
     * 分析阶段的统一异常兜底。
     */
    private void runAnalysis(String interviewId) {
        try {
            analyze(interviewId);
        } catch (Exception e) {
            log.error("分析阶段失败: interviewId={}", interviewId, e);
            updateStatus(interviewId, InterviewStatus.FAILED, "分析失败: " + e.getMessage());
        }
    }

    /**
     * 执行分析：角色判定 → 问答清单 → 报告生成。
     *
     * <p>可重复调用：只依赖已落库的 {@code transcript_json}，
     * 因此"重新生成报告"不会重新转写（编码方案步骤 4 的验收项）。
     */
    private void analyze(String interviewId) {
        AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId));

        // 1. 条件校验
        if (record == null) {
            throw new IllegalStateException("面试记录不存在: " + interviewId);
        }
        if (!StringUtils.hasText(record.getTranscriptJson())) {
            throw new IllegalStateException("文字稿尚未就绪，无法分析，当前状态: " + record.getStatus());
        }

        // 2. 状态更新：分析中
        updateStatus(interviewId, InterviewStatus.ANALYZING, null);

        // 3. 拿到转写结果 JSON
        NormalizedTranscript transcript = transcriptNormalizer.normalize(record.getTranscriptJson());

        // 4. 角色判定
        RoleJudgment judgment = speakerRoleResolver.resolve(transcript);

        // 5. 角色判定结果落库
        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getInterviewerSpeakerId, judgment.interviewerSpeakerId()));
        log.info("角色判定已落库: interviewId={}, interviewerSpeakerId={}",
                interviewId, judgment.interviewerSpeakerId());

        // 6. 问答清单：直接由句子列表格式化（零 LLM、零分块）
        List<QaItem> qaItems = qaListBuilder.build(transcript, judgment.interviewerSpeakerId());

        // 发送进度更新：问答清单已整理出
        progressHub.publishProgress(interviewId, "extracting",
                String.format("已整理出 %d 条问答（Q/A 均为逐字原文），准备生成报告…", qaItems.size()),
                qaItems.size());

        // 生成报告 → 渲染 Markdown → 存 MinIO → 落库 → READY ----
        publishReport(record, transcript, qaItems, judgment.interviewerSpeakerId());
    }

    /**
     * 报告落库与落盘。
     */
    private void publishReport(AiInterview record,
                               NormalizedTranscript transcript,
                               List<QaItem> qaItems,
                               Integer interviewerSpeakerId) {
        String interviewId = record.getInterviewId();

        // 发送进度更新：报告生成中
        progressHub.publishProgress(interviewId, "reporting",
                "正在生成总结报告中…", null);

        InterviewReport report = reportGenerator.generate(record, transcript, qaItems, interviewerSpeakerId);
        String markdown = reportRenderer.render(report);
        String reportJson;
        try {
            reportJson = objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            throw new IllegalStateException("序列化报告失败: " + e.getMessage(), e);
        }

        // 对象名同时也是下载文件名，取自同一个 interviewId
        String objectName = "interview-report-" + interviewId + ".md";
        String reportUrl;
        try {
            reportUrl = minioService.uploadFile(objectName,
                    markdown.getBytes(StandardCharsets.UTF_8), "text/markdown;charset=UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("报告上传 MinIO 失败: " + e.getMessage(), e);
        }

        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getReportJson, reportJson)
                .set(AiInterview::getReportFileUrl, reportUrl)
                .set(AiInterview::getReportFileName, objectName)
                .set(AiInterview::getStatus, InterviewStatus.READY.name())
                .set(AiInterview::getErrorMsg, null));

        // 落库之后再推终态：前端拿到 complete 就能直接渲染报告正文（不必再请求一次报告接口）
        progressHub.publishComplete(interviewId, reportUrl, reportJson);

        log.info("报告已就绪: interviewId={}, objectName={}, markdownChars={}, url={}",
                interviewId, objectName, markdown.length(), reportUrl);
    }
}
