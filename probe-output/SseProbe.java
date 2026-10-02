import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.entity.record.InterviewStatus;
import com.dobao.dobaobackend.interview.progress.InterviewProgressHub;
import com.dobao.dobaobackend.mapper.AiInterviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import reactor.core.Disposable;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One-off probe: verifies InterviewProgressHub SSE frames against the wire contract
 * expected by dobao-front/src/composables/useInterview.ts, plus sink lifecycle
 * (snapshot -> live events -> terminal close -> release when no subscriber).
 *
 * No database: AiInterviewMapper is faked with a dynamic proxy.
 */
public class SseProbe {

    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // LambdaQueryWrapper needs entity metadata (normally built by MyBatis at startup).
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiInterview.class);

        String reportJson = "{\"interviewId\":\"iv-1\",\"audioDurationMs\":120000,\"qaList\":[{\"qaId\":\"Q001\"}]}";

        // ---------- A. already READY: exactly one complete frame, then close ----------
        AiInterview ready = record("iv-ready", InterviewStatus.READY, null);
        ready.setReportJson(reportJson);
        ready.setReportFileUrl("http://minio/interview-report-iv-ready.md");
        InterviewProgressHub hubA = hubFor(ready);
        List<String> framesA = hubA.stream("iv-ready").collectList().block(Duration.ofSeconds(3));
        check("A single frame (no long-lived connection)", framesA.size() == 1);
        check("A event name is complete", framesA.get(0).startsWith("event: complete\ndata: "));
        check("A carries reportUrl", framesA.get(0).contains("\"reportUrl\":\"http://minio/interview-report-iv-ready.md\""));
        check("A carries report body", framesA.get(0).contains("\"report\":{\"interviewId\":\"iv-1\""));
        check("A frame ends with blank line", framesA.get(0).endsWith("\n\n"));
        check("A no sink left behind", hubA.activeStreams() == 0);

        // ---------- B. FAILED: exactly one error frame ----------
        AiInterview bad = record("iv-bad", InterviewStatus.FAILED, "转写任务失败: 音频损坏");
        InterviewProgressHub hubB = hubFor(bad);
        List<String> framesB = hubB.stream("iv-bad").collectList().block(Duration.ofSeconds(3));
        check("B single frame", framesB.size() == 1);
        check("B event name is error", framesB.get(0).startsWith("event: error\ndata: "));
        check("B carries failure reason", framesB.get(0).contains("\"message\":\"转写任务失败: 音频损坏\""));
        check("B carries status/stage", framesB.get(0).contains("\"status\":\"FAILED\"") && framesB.get(0).contains("\"stage\":\"error\""));

        // ---------- C. record missing ----------
        InterviewProgressHub hubC = hubFor(null);
        List<String> framesC = hubC.stream("nope").collectList().block(Duration.ofSeconds(3));
        check("C single frame", framesC.size() == 1);
        check("C event name is error", framesC.get(0).startsWith("event: error\ndata: "));
        check("C carries readable message", framesC.get(0).contains("面试记录不存在"));

        // ---------- D. in progress: snapshot -> live events -> complete ----------
        AiInterview running = record("iv-live", InterviewStatus.ANALYZING, null);
        InterviewProgressHub hubD = hubFor(running);
        CompletableFuture<List<String>> future = hubD.stream("iv-live").collectList().toFuture();
        Thread.sleep(200); // let the snapshot land and the live sink be subscribed

        hubD.publishStatus("iv-live", InterviewStatus.TRANSCRIBING);
        hubD.publishProgress("iv-live", "extracting", "已整理出 3 条问答（Q/A 均为逐字原文）…", 3);
        hubD.publishComplete("iv-live", "http://minio/interview-report-iv-live.md", reportJson);

        List<String> framesD = future.get(3, TimeUnit.SECONDS);
        check("D 4 frames", framesD.size() == 4);
        check("D[0] is snapshot", framesD.get(0).startsWith("event: snapshot\ndata: "));
        check("D[0] carries current status", framesD.get(0).contains("\"status\":\"ANALYZING\""));
        check("D[0] carries reportReady=false", framesD.get(0).contains("\"reportReady\":false"));
        check("D[1] is progress/transcribing", framesD.get(1).contains("\"stage\":\"transcribing\"")
                && framesD.get(1).contains("\"status\":\"TRANSCRIBING\""));
        check("D[2] carries qaCount and stage", framesD.get(2).contains("\"stage\":\"extracting\"")
                && framesD.get(2).contains("\"qaCount\":3"));
        check("D[3] is complete", framesD.get(3).startsWith("event: complete\ndata: ")
                && framesD.get(3).contains("\"status\":\"READY\""));
        check("D sink released after terminal frame", hubD.activeStreams() == 0);

        // ---------- E. heartbeat + release on disconnect ----------
        InterviewProgressHub hubE = hubFor(record("iv-hb", InterviewStatus.TRANSCRIBING, null));
        List<String> framesE = new CopyOnWriteArrayList<>();
        AtomicReference<Disposable> sub = new AtomicReference<>();
        sub.set(hubE.stream("iv-hb").subscribe(framesE::add));
        Thread.sleep(200);
        hubE.heartbeat();
        Thread.sleep(200);
        check("E snapshot + ping", framesE.size() == 2 && ": ping".equals(framesE.get(1).trim()));
        check("E one active stream", hubE.activeStreams() == 1);
        sub.get().dispose();
        Thread.sleep(200);
        check("E sink released after disconnect", hubE.activeStreams() == 0);

        System.out.println(failed == 0 ? "\nALL CHECKS PASSED" : "\n" + failed + " CHECK(S) FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) {
            failed++;
        }
    }

    private static AiInterview record(String id, InterviewStatus status, String errorMsg) {
        AiInterview record = new AiInterview();
        record.setInterviewId(id);
        record.setStatus(status.name());
        record.setErrorMsg(errorMsg);
        return record;
    }

    /** Fake mapper: selectOne returns the given record (null means "record not found"). */
    private static InterviewProgressHub hubFor(AiInterview record) {
        AiInterviewMapper mapper = (AiInterviewMapper) Proxy.newProxyInstance(
                SseProbe.class.getClassLoader(),
                new Class<?>[]{AiInterviewMapper.class},
                (proxy, method, methodArgs) -> {
                    if ("selectOne".equals(method.getName())) {
                        return record;
                    }
                    if ("toString".equals(method.getName())) {
                        return "stub-mapper";
                    }
                    return null;
                });
        return new InterviewProgressHub(mapper, new ObjectMapper());
    }
}
