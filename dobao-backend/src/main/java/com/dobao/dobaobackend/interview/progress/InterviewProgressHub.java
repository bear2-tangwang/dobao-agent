package com.dobao.dobaobackend.interview.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面试进度实时推送（SSE）。
 *
 * <p><b>为什么要有它</b>：上传接口返回后，转写要几分钟、报告归纳要 1~3 分钟，
 * 期间前端只能靠 {@code GET /{id}/status} 轮询（4~12 秒一次，任务越长越显得"卡住"）。
 * 这个类把状态机里的每一次跳变**在同一瞬间**推给页面，轮询退化为"断线兜底"而不是主通道。
 *
 * <h3>协议（与前端 {@code dobao-front/src/composables/useInterview.ts} 一一对应）</h3>
 * <pre>
 * event: snapshot   data: { status, stage, text, sentenceCount, speakerCount, reportReady, reportUrl }  连接建立时的当前态
 * event: progress   data: { status, stage, text, qaCount }              阶段推进
 * event: complete   data: { status:"READY", reportUrl, report: {...} }  报告就绪（带完整报告）
 * event: error      data: { status, message }                           失败原因
 * </pre>
 * 事件名、{@code stage} 的取值（uploaded/transcribing/transcribed/analyzing/extracting/reporting/ready/error）
 * 都是**前后端约定**，前端按 {@code stage} 做步骤去重，所以这里不能随手改名。
 *
 * <h3>几条刻意的取舍</h3>
 * <ul>
 *   <li><b>推的是"已经落库的事实"，不是另一份状态</b>：{@link #publishProgress} 只在
 *       {@code InterviewTaskService} 改完数据库之后调用；流断了、页面刷新了，
 *       重新连接时的 snapshot 一律从库里读，不会出现"流说有、库里没有"。</li>
 *   <li><b>没人看就不建 sink</b>：推送时若该 interviewId 还没有连接，直接丢弃 ——
 *       连接时的那一帧 snapshot 已经能补齐全部已发生的状态。</li>
 *   <li><b>终态主动收尾</b>：{@code complete}/{@code error} 之后 complete 掉 sink 并回收，
 *       否则每条完成的面试都会在内存里留一个永远不结束的 Flux。</li>
 *   <li><b>心跳</b>：转写阶段可能几分钟没有任何跳变，靠 {@code : ping} 注释帧保住长连接
 *       （前端解析时会忽略注释行）。</li>
 *   <li><b>单实例内存广播</b>：多实例部署时前端可能连到"不是跑任务的那台"，
 *       那时只能靠 snapshot + 轮询兜底；要真正跨实例需要换成 Redis pub/sub 之类的总线。</li>
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

    /** 心跳用的注释帧：SSE 规范里以 ':' 开头的行是注释，前端会直接跳过 */
    private static final String HEARTBEAT_FRAME = ": ping\n\n";

    private final AiInterviewMapper interviewMapper;
    private final ObjectMapper objectMapper;

    /**
     * interviewId → 广播 sink（只在有订阅者时才存在）。
     *
     * <p>用 {@code multicast().onBackpressureBuffer()}：没有订阅者时先把事件缓存在 sink 里，
     * 第一个订阅者接入时会把它排空 —— 这样"读 snapshot 的这几毫秒里发生的跳变"不会丢。
     */
    private final Map<String, Sinks.Many<String>> sinks = new ConcurrentHashMap<>();

    // ==================== 订阅端 ====================

    /**
     * 一条面试进度流：先补一帧 snapshot，再持续推后续事件。
     *
     * <p>已经是终态的记录不会挂长连接：READY 直接回一帧 {@code complete}（带完整报告，
     * 前端因此不用再请求一次报告接口），FAILED 直接回一帧 {@code error}，
     * 两种情况连接都会立刻收尾。
     */
    public Flux<String> stream(String interviewId) {
        return Flux.defer(() -> {
            log.info("面试进度流已连接: interviewId={}, activeStreams={}", interviewId, sinks.size());
            AiInterview record = find(interviewId);
            if (record == null) {
                return Flux.just(errorFrame(null, "面试记录不存在: " + interviewId));
            }
            if (InterviewStatus.READY.name().equals(record.getStatus())) {
                return Flux.just(completeFrame(record.getReportFileUrl(), record.getReportJson()));
            }
            if (InterviewStatus.FAILED.name().equals(record.getStatus())) {
                return Flux.just(errorFrame(InterviewStatus.FAILED.name(),
                        StringUtils.hasText(record.getErrorMsg())
                                ? record.getErrorMsg() : InterviewStatus.FAILED.description()));
            }

            Sinks.Many<String> sink = sinks.computeIfAbsent(interviewId,
                    id -> Sinks.many().multicast().onBackpressureBuffer());
            // 最后一个订阅者也走了就回收，避免 map 长期持有已结束的 sink
            Flux<String> live = sink.asFlux().doFinally(signal -> {
                if (sink.currentSubscriberCount() == 0) {
                    sinks.remove(interviewId, sink);
                }
            });
            return Flux.concat(Flux.just(snapshotFrame(record)), live);
        });
    }

    // ==================== 推送端 ====================

    /**
     * 状态跳变，状态机里每一次"写库 + 通知"都走这里。
     *
     * <p>FAILED 推的是 {@code error} 事件（带可读原因），其余状态推 {@code progress}；
     * 分支收在这里，调用方（{@code InterviewTaskService#updateStatus}）不必自己判终态。
     *
     * @param errorMsg 失败原因，仅 {@code FAILED} 使用；为空时退化为兜底文案
     */
    public void publishStatus(String interviewId, InterviewStatus status, String errorMsg) {
        if (status == InterviewStatus.FAILED) {
            pushTerminal(interviewId, errorFrame(status.name(),
                    StringUtils.hasText(errorMsg) ? errorMsg : status.description()));
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status.name());
        data.put("stage", stageKey(status));
        data.put("text", status.description());
        push(interviewId, frame(EVENT_PROGRESS, data));
    }

    /**
     * 阶段内的细粒度进度（转写完成 / 问答整理完 / 报告生成中）。
     *
     * @param qaCount 已整理出的问答条数，没有就传 null
     */
    public void publishProgress(String interviewId, String stage, String text, Integer qaCount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stage", stage);
        data.put("text", text);
        if (qaCount != null) {
            data.put("qaCount", qaCount);
        }
        push(interviewId, frame(EVENT_PROGRESS, data));
    }

    /**
     * 报告就绪（终态）。带上完整报告，前端一次就能渲染出正文。
     *
     * <p>不下发 {@code downloadUrl}：下载地址是前端按自己的 {@code backendUrl} 拼的，
     * 后端并不知道对外可达的域名（前端在缺字段时会自行拼接）。
     *
     * @param reportUrl  报告在 MinIO 里的地址（前端只做展示/兜底）
     * @param reportJson {@code ai_interview.report_json} 原文，原样透传成 {@code report} 字段
     */
    public void publishComplete(String interviewId, String reportUrl, String reportJson) {
        pushTerminal(interviewId, completeFrame(reportUrl, reportJson));
    }

    /**
     * 心跳：转写阶段可能几分钟没有任何状态变化，中间层（代理 / 浏览器）会把静默的连接判死。
     * 只在确实有订阅者时发，避免给没人看的 sink 堆缓冲区。
     */
    @Scheduled(fixedDelayString = "${interview.stream.heartbeat-ms:15000}")
    public void heartbeat() {
        if (sinks.isEmpty()) {
            return;
        }
        sinks.forEach((interviewId, sink) -> {
            if (sink.currentSubscriberCount() > 0) {
                sink.tryEmitNext(HEARTBEAT_FRAME);
            }
        });
    }

    // ==================== 内部实现 ====================

    private AiInterview find(String interviewId) {
        return interviewMapper.selectOne(new LambdaQueryWrapper<AiInterview>()
                .eq(AiInterview::getInterviewId, interviewId));
    }

    private void push(String interviewId, String frame) {
        Sinks.Many<String> sink = sinks.get(interviewId);
        if (sink == null) {
            // 没人在看这场面试：不建 sink（连接时的 snapshot 会补齐当前状态）
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(frame);
        if (result.isFailure()) {
            log.info("面试进度推送未成功: interviewId={}, result={}", interviewId, result);
        }
    }

    private void pushTerminal(String interviewId, String frame) {
        Sinks.Many<String> sink = sinks.get(interviewId);
        if (sink == null) {
            return;
        }
        sink.tryEmitNext(frame);
        sink.tryEmitComplete();
        sinks.remove(interviewId, sink);
        log.info("面试进度流已收尾: interviewId={}", interviewId);
    }

    // ---- SSE 帧 ----

    private String frame(String event, Map<String, Object> data) {
        try {
            return "event: " + event + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            log.warn("面试进度事件序列化失败，已丢弃: event={}, err={}", event, e.getMessage());
            return "";
        }
    }

    /**
     * snapshot 帧：连接建立时的当前态。
     *
     * <p>字段与 {@code GET /interview/{id}/status} 对齐（阶段 key、文案、句数/说话人数都在），
     * 目的是让断线重连与页面刷新<b>不必再补一次状态查询</b> —— 轮询因此只在
     * "流完全不可用"时才启动。
     *
     * <p>两个终态不走 snapshot：READY 走 {@code complete}（带完整报告）、
     * FAILED 走 {@code error}（带失败原因），都在 {@link #stream(String)} 里提前返回。
     */
    private String snapshotFrame(AiInterview record) {
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
        return frame(EVENT_SNAPSHOT, data);
    }

    /**
     * complete 帧（报告就绪）。连接时已是 READY 的记录与"报告刚生成"两个场景推的是同一帧，
     * 因此两处共用本方法，保证字段完全一致。
     */
    private String completeFrame(String reportUrl, String reportJson) {
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
        return frame(EVENT_COMPLETE, data);
    }

    private String errorFrame(String status, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (status != null) {
            data.put("status", status);
        }
        data.put("stage", "error");
        data.put("message", message);
        return frame(EVENT_ERROR, data);
    }

    // ---- 状态 → 协议字段 ----

    /** 状态 → 前端的步骤 key（前端按 key 去重，所以必须与 useInterview.ts 的 InterviewStepKey 同名） */
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
