package com.dobao.dobaobackend.interview.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试进度实时推送（SSE）：把状态机里的每一次跳变在同一瞬间推给页面，
 * 前端的 {@code GET /{id}/status} 轮询退化为"断线兜底"而不是主通道。
 *
 * <h3>协议（与前端 {@code dobao-front/src/composables/useInterview.ts} 一一对应）</h3>
 * <pre>
 * event: snapshot   data: { status, stage, text, sentenceCount, speakerCount, reportReady, reportUrl }
 * event: progress   data: { stage, text, qaCount?, elapsedMs? }
 * event: complete   data: { status:"READY", reportUrl, report: {...} }
 * event: error      data: { status?, message }
 * :ping                                                                心跳注释帧
 * </pre>
 * 事件名与 {@code stage} 取值都是前后端约定（前端按 {@code stage} 去重），不能随手改名。
 *
 * <h3>帧格式必须交给框架拼</h3>
 * 本类只产出 {@link ServerSentEvent}，不自己拼 {@code "event: x\ndata: ..."} 字符串：
 * 控制器返回 {@code Flux<String>} + {@code produces=text/event-stream} 时，
 * Spring MVC 只对 {@link ServerSentEvent} 走结构化的 {@code event:}/{@code data:} 输出，
 * 其他对象一律当成"一条 data"，前端解析必然失败、所有事件被静默丢弃。
 *
 * <h3>几条刻意的取舍</h3>
 * <ul>
 *   <li><b>推的是"已经落库的事实"</b>：推送只在状态机改完数据库之后发生；
 *       断线或刷新后的 snapshot 一律从库里读，不会出现"流说有、库里没有"。</li>
 *   <li><b>没人看就不推</b>：推送时若该 interviewId 还没有连接，直接丢弃 ——
 *       连接时的那一帧 snapshot 已能补齐全部已发生的状态。</li>
 *   <li><b>终态主动收尾</b>：{@code complete}/{@code error} 之后 complete 掉 sink 并回收，
 *       否则每条完成的面试都会在内存里留一个永远不结束的 Flux。</li>
 *   <li><b>单实例内存广播</b>：多实例部署时前端可能连到"不是跑任务的那台"，
 *       那时只能靠 snapshot + 轮询兜底。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewProgressHub {

    private static final String EVENT_SNAPSHOT = "snapshot";
    private static final String EVENT_PROGRESS = "progress";
    private static final String EVENT_COMPLETE = "complete";
    private static final String EVENT_ERROR = "error";

    /** 心跳注释帧：SSE 规范里以 ':' 开头的行是注释，前端与浏览器都会直接跳过 */
    private static final ServerSentEvent<String> HEARTBEAT =
            ServerSentEvent.<String>builder().comment("ping").build();

    /**
     * "长步骤"：这些阶段可能静默几分钟，心跳要为它们报时。
     *
     * <p>uploaded / transcribed / ready / error 都是瞬时阶段，只发 {@code :ping} 保活。
     */
    private static final Set<String> LONG_STAGES =
            Set.of("transcribing", "analyzing", "extracting", "reporting");

    private final AiInterviewMapper interviewMapper;
    private final ObjectMapper objectMapper;
    private final GithubOAuthProperties authProperties;

    /**
     * interviewId → 广播 sink（只在有订阅者时才存在）。
     *
     * <p>用 {@code multicast().onBackpressureBuffer()}：第一个订阅者接入前的事件会被缓存，
     * 接入时排空 —— 这样"读 snapshot 的这几毫秒里发生的跳变"不会丢。
     */
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    /** interviewId → 当前正在进行的阶段（用于心跳报时）。终态时清除。 */
    private final Map<String, StepState> steps = new ConcurrentHashMap<>();

    /** 一个正在进行中的阶段：起始时间只在 stage 变化时重置，因此"已用 N 秒"是真实的阶段耗时 */
    private record StepState(String stage, String baseText, long startedAtMs) {
    }

    /**
     * 一条面试进度流：先补一帧 snapshot，再持续推后续事件。
     *
     * <p>已是终态的记录不挂长连接：READY 直接回一帧 {@code complete}（带完整报告，
     * 前端因此不用再请求一次报告接口），FAILED 直接回一帧 {@code error}，两种情况都立刻收尾。
     */
    public Flux<ServerSentEvent<String>> stream(String interviewId) {
        return Flux.defer(() -> {
            AiInterview record = find(interviewId);
            if (record == null) {
                return Flux.just(errorEvent(null, "面试记录不存在: " + interviewId));
            }
            // 归属校验：这条流会把转写进度、问答条数甚至完整报告推给订阅者，
            // 拿到别人的 interviewId 就能看别人的面试。报"不存在"而不是"无权访问"，
            // 避免泄露该 ID 真实存在。
            String currentUserId = UserContext.getUserIdOrDefault(authProperties.getDefaultUserId());
            if (StringUtils.hasText(record.getUserId()) && !record.getUserId().equals(currentUserId)) {
                log.warn("面试进度流归属校验失败: interviewId={}, owner={}, requester={}",
                        interviewId, record.getUserId(), currentUserId);
                return Flux.just(errorEvent(null, "面试记录不存在: " + interviewId));
            }
            if (InterviewStatus.READY.name().equals(record.getStatus())) {
                return Flux.just(completeEvent(record.getReportFileUrl(), record.getReportJson()));
            }
            if (InterviewStatus.FAILED.name().equals(record.getStatus())) {
                return Flux.just(errorEvent(InterviewStatus.FAILED.name(),
                        StringUtils.hasText(record.getErrorMsg())
                                ? record.getErrorMsg() : InterviewStatus.FAILED.description()));
            }

            // 连接时若还没有阶段登记（例如后端刚重启、或任务在另一台实例上跑），
            // 用当前状态补一条，这样"已用 N 秒"至少有个起点
            InterviewStatus status = InterviewStatus.parse(record.getStatus());
            if (status != null) {
                steps.putIfAbsent(interviewId,
                        new StepState(stageKey(status), status.description(), System.currentTimeMillis()));
            }

            Sinks.Many<ServerSentEvent<String>> sink = sinks.computeIfAbsent(interviewId,
                    id -> Sinks.many().multicast().onBackpressureBuffer());
            log.info("面试进度流已连接: interviewId={}, activeStreams={}", interviewId, sinks.size());
            // 最后一个订阅者也走了就回收，避免 map 长期持有已结束的 sink
            Flux<ServerSentEvent<String>> live = sink.asFlux().doFinally(signal -> {
                if (sink.currentSubscriberCount() == 0) {
                    sinks.remove(interviewId, sink);
                }
            });
            return Flux.concat(Flux.just(snapshotEvent(record)), live);
        });
    }

    /**
     * 状态跳变，状态机里每一次"写库 + 通知"都走这里。
     *
     * <p>FAILED 推 {@code error} 事件（带可读原因），其余状态推 {@code progress}；
     * 分支收在这里，调用方不必自己判终态。
     *
     * @param errorMsg 失败原因，仅 {@code FAILED} 使用；为空时退化为兜底文案
     */
    public void publishStatus(String interviewId, InterviewStatus status, String errorMsg) {
        if (status == InterviewStatus.FAILED) {
            pushTerminal(interviewId, errorEvent(status.name(),
                    StringUtils.hasText(errorMsg) ? errorMsg : status.description()));
            return;
        }
        String stage = stageKey(status);
        registerStep(interviewId, stage, status.description());
        push(interviewId, progressEvent(stage, status.description(), null, null));
    }

    /**
     * 阶段内的细粒度进度（准备音频 / 等待识别 / 判定角色 / 整理问答 / 报告生成中）。
     *
     * <p>同时把该阶段登记为"当前阶段"，心跳任务据此为长步骤补齐"已用 N 秒"。
     *
     * @param qaCount 已整理出的问答条数，没有就传 null
     */
    public void publishProgress(String interviewId, String stage, String text, Integer qaCount) {
        registerStep(interviewId, stage, text);
        push(interviewId, progressEvent(stage, text, qaCount, null));
    }

    /**
     * 报告就绪（终态）。带上完整报告，前端一次就能渲染出正文。
     *
     * <p>不下发 {@code downloadUrl}：下载地址由前端按自己的 {@code backendUrl} 拼，
     * 后端并不知道对外可达的域名。
     *
     * @param reportUrl  报告在 MinIO 里的地址（前端只做展示/兜底）
     * @param reportJson {@code ai_interview.report_json} 原文，原样透传成 {@code report} 字段
     */
    public void publishComplete(String interviewId, String reportUrl, String reportJson) {
        pushTerminal(interviewId, completeEvent(reportUrl, reportJson));
    }

    /**
     * 心跳：长步骤报时 + 静默连接保活，一个定时任务做完（默认 15 秒一次）。
     *
     * <p>长步骤（转写等待 / 角色判定 / 报告生成）推一条带 {@code elapsedMs} 的 progress，
     * 其余推注释帧 {@code :ping} 保活。只对确实有订阅者的面试发。
     */
    @Scheduled(fixedDelayString = "${interview.stream.heartbeat-ms:15000}")
    public void heartbeat() {
        if (sinks.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        sinks.forEach((interviewId, sink) -> {
            if (sink.currentSubscriberCount() == 0) {
                return;
            }
            StepState step = steps.get(interviewId);
            if (step != null && LONG_STAGES.contains(step.stage())) {
                long elapsedMs = now - step.startedAtMs();
                sink.tryEmitNext(progressEvent(step.stage(),
                        tickText(step.baseText(), elapsedMs), null, elapsedMs));
            } else {
                sink.tryEmitNext(HEARTBEAT);
            }
        });
    }

    private AiInterview find(String interviewId) {
        return interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId));
    }

    /**
     * 登记/更新当前阶段。同 stage 重复调用不重置起始时间，
     * 这样"准备音频 → 提交任务 → 等待结果"三条同阶段文案能共用同一个计时。
     */
    private void registerStep(String interviewId, String stage, String text) {
        if (!StringUtils.hasText(stage)) {
            return;
        }
        steps.compute(interviewId, (id, prev) -> {
            long startedAt = prev != null && stage.equals(prev.stage())
                    ? prev.startedAtMs() : System.currentTimeMillis();
            return new StepState(stage, text == null ? "" : text, startedAt);
        });
    }

    private void push(String interviewId, ServerSentEvent<String> event) {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(interviewId);
        if (sink == null) {
            // 没人在看这场面试：不建 sink（连接时的 snapshot 会补齐当前状态）
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.info("面试进度推送未成功: interviewId={}, result={}", interviewId, result);
        }
    }

    private void pushTerminal(String interviewId, ServerSentEvent<String> event) {
        steps.remove(interviewId);
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(interviewId);
        if (sink == null) {
            return;
        }
        sink.tryEmitNext(event);
        sink.tryEmitComplete();
        sinks.remove(interviewId, sink);
        log.info("面试进度流已收尾: interviewId={}", interviewId);
    }

    /** 基础文案 + 已用时长：去掉结尾的省略号，避免"…（已用 12 秒）"这种叠标点 */
    static String tickText(String baseText, long elapsedMs) {
        String base = baseText == null ? "" : baseText.trim();
        while (base.endsWith("…") || base.endsWith("。") || base.endsWith(".")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        return (base.isEmpty() ? "处理中" : base) + "（已用 " + formatElapsed(elapsedMs) + "）";
    }

    /** 毫秒 → 人读时长（不足 1 秒按 1 秒算，避免出现"已用 0 秒"） */
    static String formatElapsed(long elapsedMs) {
        long seconds = Math.max(1L, elapsedMs / 1000);
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    // ---- SSE 事件（帧格式交给 Spring MVC 的 SseEmitter，本类不再自己拼文本） ----

    private ServerSentEvent<String> progressEvent(String stage, String text, Integer qaCount, Long elapsedMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("text", text);
        if (qaCount != null) {
            data.put("qaCount", qaCount);
        }
        if (elapsedMs != null) {
            data.put("elapsedMs", elapsedMs);
        }
        return event(EVENT_PROGRESS, data);
    }

    /**
     * snapshot 事件：连接建立时的当前态。
     *
     * <p>字段与 {@code GET /interview/{id}/status} 对齐，目的是让断线重连与页面刷新
     * <b>不必再补一次状态查询</b>。
     *
     * <p>两个终态不走 snapshot：READY 走 {@code complete}、FAILED 走 {@code error}，
     * 都在 {@link #stream(String)} 里提前返回。
     */
    private ServerSentEvent<String> snapshotEvent(AiInterview record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", record.getStatus());
        InterviewStatus status = InterviewStatus.parse(record.getStatus());
        if (status != null) {
            data.put("stage", stageKey(status));
            data.put("text", status.description());
        }
        data.put("sentenceCount", record.getSentenceCount());
        data.put("speakerCount", record.getSpeakerCount());
        data.put("reportReady", StringUtils.hasText(record.getReportFileUrl()));
        data.put("reportUrl", record.getReportFileUrl());
        return event(EVENT_SNAPSHOT, data);
    }

    /**
     * complete 事件（报告就绪）：连接时已是 READY 与"报告刚生成"两个场景共用本方法，
     * 保证两处字段完全一致。
     */
    private ServerSentEvent<String> completeEvent(String reportUrl, String reportJson) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", InterviewStatus.READY.name());
        data.put("reportUrl", reportUrl);
        // 报告 JSON 坏掉时宁可不带正文，也不能让 complete 帧整个失败
        if (StringUtils.hasText(reportJson)) {
            try {
                data.put("report", objectMapper.readTree(reportJson));
            } catch (Exception e) {
                log.warn("报告 JSON 解析失败，complete 事件里将不带报告正文: {}", e.getMessage());
            }
        }
        return event(EVENT_COMPLETE, data);
    }

    private ServerSentEvent<String> errorEvent(String status, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (status != null) {
            data.put("status", status);
        }
        data.put("stage", "error");
        data.put("message", message);
        return event(EVENT_ERROR, data);
    }

    private ServerSentEvent<String> event(String name, Map<String, Object> data) {
        try {
            return ServerSentEvent.builder(objectMapper.writeValueAsString(data)).event(name).build();
        } catch (Exception e) {
            // 序列化失败说明载荷里有无法映射的对象，属于编码问题：退化成一条说明性 error 帧，
            // 而不是把一帧没有 data 的 event 推给前端
            log.warn("面试进度事件序列化失败: event={}, err={}", name, e.getMessage());
            return ServerSentEvent.builder("{\"stage\":\"error\",\"message\":\"进度事件序列化失败\"}")
                    .event(EVENT_ERROR).build();
        }
    }

    /** 状态 → 前端步骤 key（前端按 key 去重，必须与 useInterview.ts 的步骤 key 同名） */
    private static String stageKey(InterviewStatus status) {
        return switch (status) {
            case UPLOADED -> "uploaded";
            case TRANSCRIBING -> "transcribing";
            case TRANSCRIBED -> "transcribed";
            case ANALYZING -> "analyzing";
            case READY -> "ready";
            case FAILED -> "error";
        };
    }
}
