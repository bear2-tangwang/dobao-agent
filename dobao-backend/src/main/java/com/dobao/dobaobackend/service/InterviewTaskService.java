package com.dobao.dobaobackend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.InterviewReportGenerator;
import com.dobao.dobaobackend.interview.InterviewReportRenderer;
import com.dobao.dobaobackend.interview.InterviewSessionRecorder;
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
 * 面试总结 · 异步任务编排与状态机：提交与分析走 {@code @Async("interviewExecutor")}，
 * 轮询交给 {@code @Scheduled} 扫描转写中的记录（不占线程池，重启后可续跑，顺带做超时兜底）。
 *
 * <p>状态流转一律走 {@link #updateStatus}：列级更新，SET 子句不含 {@code update_time}，
 * 由 MySQL 的 {@code ON UPDATE CURRENT_TIMESTAMP} 维护；改成 updateById 会把旧值写回并压制它。
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
    private final InterviewSessionRecorder sessionRecorder;

    /**
     * 异步：取音频 URL（dev 需上传到百炼临时存储）并提交转写任务。
     *
     * <p>必须由其他 Bean（{@code InterviewService#upload}）调用：同类内直调会绕过 Spring 代理变成同步执行。
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

    private void submitTranscription(String interviewId, String objectName) {
        updateStatus(interviewId, InterviewStatus.TRANSCRIBING, null);

        progressHub.publishProgress(interviewId, "transcribing",
                "正在准备音频（上传到识别服务）…", null);

        // 音频地址：dev 走百炼临时存储，prod 走公网地址
        String audioUrl = audioUrlProvider.provide(objectName);
        progressHub.publishProgress(interviewId, "transcribing", "音频已就绪，正在提交转写任务…", null);

        String taskId = asrClient.submit(audioUrl, null);
        progressHub.publishProgress(interviewId, "transcribing",
                "转写任务已提交，正在等待识别结果（长音频通常 1~5 分钟）…", null);

        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getAsrTaskId, taskId));
        log.info("转写任务已提交并落库: interviewId={}, taskId={}", interviewId, taskId);
    }

    /**
     * 定时轮询转写中的任务，并做超时兜底。
     *
     * <p>超时比较必须交给 SQL：MySQL 时间是 UTC、比 JDK 早 8 小时，在 Java 里拿 {@code update_time}
     * 与 {@code LocalDateTime.now()} 相减会把刚提交的任务判成"超时 8 小时"。
     */
    @Scheduled(fixedDelayString = "${interview.asr.poll-interval-ms:5000}")
    public void pollTranscribingTasks() {
        long timeoutMs = properties.getAsr().getPollTimeoutMs();

        // 先兜底超时任务
        for (AiInterview task : queryTranscribing(true, timeoutMs)) {
            updateStatus(task.getInterviewId(), InterviewStatus.FAILED, String.format(
                    "转写超时（超过 %d 分钟未完成），已强制置为失败，可重试",
                    Duration.ofMillis(timeoutMs).toMinutes()));
            log.warn("转写超时兜底生效: interviewId={}", task.getInterviewId());
        }

        // 再轮询尚未超时的任务
        List<AiInterview> tasks = queryTranscribing(false, timeoutMs);
        if (tasks.isEmpty()) {
            return;
        }
        log.info("轮询转写任务: count={}", tasks.size());
        for (AiInterview task : tasks) {
            try {
                pollOne(task);
            } catch (Exception e) {
                // 单个任务异常不能影响其他任务，也不能让调度线程挂掉
                log.error("轮询任务异常: interviewId={}", task.getInterviewId(), e);
            }
        }
    }

    /**
     * 查询"转写中且已提交任务"的记录；超时比较用 {@code update_time}（为空时回退 {@code create_time}），
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

    private void pollOne(AiInterview task) {
        String interviewId = task.getInterviewId();

        AsrTaskState state = asrClient.query(task.getAsrTaskId());
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

        // 结果链接 24 小时失效，必须立刻从百炼临时存储下载并落库
        String asrJson = asrClient.downloadResult(state.transcriptionUrl());
        persistTranscript(task, asrJson);
    }

    private void persistTranscript(AiInterview task, String asrJson) {
        String interviewId = task.getInterviewId();

        // transcript_json 是 MySQL JSON 列，非法 JSON 会让 SQL 抛异常
        try {
            objectMapper.readTree(asrJson);
        } catch (Exception e) {
            updateStatus(interviewId, InterviewStatus.FAILED, "转写结果不是合法 JSON，已跳过落库: " + e.getMessage());
            return;
        }

        NormalizedTranscript transcript = transcriptNormalizer.normalize(asrJson);

        // 时长上限只能在这里校验：上传阶段没有音频解析库，读不到时长
        Long audioMs = transcript.audioDurationMs();
        long maxDurationMs = properties.getAsr().getMaxAudioDurationMs();
        if (audioMs != null && audioMs > maxDurationMs) {
            updateStatus(interviewId, InterviewStatus.FAILED, String.format(
                    "音频时长 %.1f 分钟超过上限 %.0f 分钟", audioMs / 60000.0, maxDurationMs / 60000.0));
            return;
        }

        // 句子数 / 说话人数一并落列，状态查询就不必再反序列化大 JSON
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

        // 立刻把转写结果推给页面，不必等下一次轮询查到
        progressHub.publishProgress(interviewId, "transcribed",
                String.format("转写完成：%d 句 / %d 位说话人，文字稿已就绪",
                        transcript.size(), transcript.speakerIds().size()), null);

        // 用事件而不是同类内直调：@Async 监听方才真正异步
        eventPublisher.publishEvent(new InterviewTranscribedEvent(interviewId));
    }

    /**
     * 回填会话摘要，并吞掉自身全部异常。
     *
     * <p>摘要只影响会话列表展示，但 {@code publishReport} 的调用方会把任何异常转成 FAILED，
     * 所以这里必须一律吞掉（只记日志），否则一场报告已就绪的面试会被翻成失败。
     */
    private void recordSessionSummary(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 不能只放行已知异常类型：写会话表失败可能以任意运行时异常形态出现
            log.error("回填面试会话摘要失败（不影响面试流程本身）: {}", e.getMessage(), e);
        }
    }

    /**
     * 统一的状态流转入口（列级更新，让数据库维护 {@code update_time}），同时推送 SSE 状态与回填会话摘要。
     *
     * <p>例外：READY 的 {@code report_json} / {@code report_file_url} / {@code report_file_name} 必须
     * 与状态在同一条 UPDATE 里，由 {@link #publishReport} 直接写。失败分支多，摘要回填挂这里才一致。
     *
     * @param errorMsg 失败原因；传 null 会显式清空该列（重试时用得上）
     */
    private void updateStatus(String interviewId, InterviewStatus status, String errorMsg) {
        int rows = interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getStatus, status.name())
                .set(AiInterview::getErrorMsg, errorMsg));
        log.info("状态更新: interviewId={}, status={}, rows={}", interviewId, status, rows);

        if (status == InterviewStatus.FAILED) {
            recordSessionSummary(() -> sessionRecorder.markFailed(interviewId, errorMsg));
        } else if (status == InterviewStatus.TRANSCRIBING || status == InterviewStatus.ANALYZING) {
            recordSessionSummary(() -> sessionRecorder.markRunning(interviewId));
        }
        // 其余状态刻意不动摘要：TRANSCRIBED 仍属连续过程，写 READY 会让列表提前显示"已完成"，
        // 而报告还没生成；PENDING 等本来就不该有摘要。
        progressHub.publishStatus(interviewId, status, errorMsg);
    }

    /**
     * 转写完成事件监听：接上角色判定、问答清单与报告生成。
     * 用 {@code @EventListener} 而非在轮询里直调，{@code @Async} 才会经代理生效。
     */
    @Async("interviewExecutor")
    @EventListener
    public void onTranscribed(InterviewTranscribedEvent event) {
        runAnalysis(event.interviewId());
    }

    /**
     * 供外部入口（{@code POST /interview/{id}/retry}，有文字稿时只重跑分析）调用的异步分析入口。
     * 必须是独立方法且由其他 Bean 调用，{@code @Async} 才会经代理生效。
     */
    @Async("interviewExecutor")
    public void analyzeAsync(String interviewId) {
        runAnalysis(interviewId);
    }

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
     * 可重复调用：只依赖已落库的 {@code transcript_json}，重新生成报告不会重新转写。
     */
    private void analyze(String interviewId) {
        AiInterview record = interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId));

        if (record == null) {
            throw new IllegalStateException("面试记录不存在: " + interviewId);
        }
        if (!StringUtils.hasText(record.getTranscriptJson())) {
            throw new IllegalStateException("文字稿尚未就绪，无法分析，当前状态: " + record.getStatus());
        }

        updateStatus(interviewId, InterviewStatus.ANALYZING, null);

        NormalizedTranscript transcript = transcriptNormalizer.normalize(record.getTranscriptJson());

        // 角色判定是一次几十秒级的 LLM 调用，先推一条进度再发起
        progressHub.publishProgress(interviewId, "analyzing", "正在判定说话人角色…", null);
        RoleJudgment judgment = speakerRoleResolver.resolve(transcript);

        interviewMapper.update(null, new LambdaUpdateWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId)
                .set(AiInterview::getInterviewerSpeakerId, judgment.interviewerSpeakerId()));
        log.info("角色判定已落库: interviewId={}, interviewerSpeakerId={}",
                interviewId, judgment.interviewerSpeakerId());

        // 问答清单直接由句子列表格式化，零 LLM、零分块
        List<QaItem> qaItems = qaListBuilder.build(transcript, judgment.interviewerSpeakerId());

        progressHub.publishProgress(interviewId, "extracting",
                String.format("已整理出 %d 条问答（Q/A 均为逐字原文），准备生成报告…", qaItems.size()),
                qaItems.size());

        publishReport(record, qaItems);
    }

    /**
     * 报告落库与落盘。
     *
     * <p>包级可见（而非 private）便于 {@code InterviewTaskServiceTest} 直接验证"报告就绪 → 会话摘要回填"。
     */
    void publishReport(AiInterview record, List<QaItem> qaItems) {
        String interviewId = record.getInterviewId();

        // 这是全流程最长的单次等待，文案里写明预期耗时；"已用 N 秒"由 hub 心跳持续补
        progressHub.publishProgress(interviewId, "reporting",
                "正在生成参考回答与面试总结（通常 1~3 分钟）…", null);

        InterviewReport report = reportGenerator.generate(record, qaItems);
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

        // 会话摘要回填：报告正文仍在 report_json，这里只写一行可读摘要给会话列表/详情兜底。
        // qaList / referenceAnswers 可能为 null（report_json 会长期保存并被反序列化回来），
        // 直接 .size() 的 NPE 会把一场报告已就绪的面试翻成 FAILED，故用 recordSessionSummary 包一层。
        recordSessionSummary(() -> sessionRecorder.markReady(interviewId,
                report.qaList() == null ? 0 : report.qaList().size(),
                report.referenceAnswers() == null ? 0 : report.referenceAnswers().size()));

        // 落库之后再推终态：前端拿到 complete 就能直接渲染报告正文。
        // 此刻报告已是 READY，这里抛异常会被 runAnalysis 的 catch 翻成 FAILED，故单独包一层（只记日志）。
        try {
            progressHub.publishComplete(interviewId, reportUrl, reportJson);
        } catch (Exception e) {
            log.error("推送报告完成事件失败（不影响面试流程本身）: interviewId={}, err={}",
                    interviewId, e.getMessage(), e);
        }

        log.info("报告已就绪: interviewId={}, objectName={}, markdownChars={}, url={}",
                interviewId, objectName, markdown.length(), reportUrl);
    }
}
