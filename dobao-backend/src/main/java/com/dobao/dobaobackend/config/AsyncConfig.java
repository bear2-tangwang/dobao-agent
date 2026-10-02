package com.dobao.dobaobackend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 面试转写任务线程池配置。
 *
 * <p>设计要点（对应 docs/interview-step1-analysis.md §4.6 / §4.7）：
 *
 * <ol>
 *   <li><b>为什么必须用独立线程池</b>：音频转写是"取临时 URL → 提交任务 → 落库"
 *       这类秒级到分钟级的耗时动作，放在 Tomcat 线程里会把上传接口拖到 3 秒以上，
 *       违反 docs/interview-summary-coding-plan.md 步骤 2 的验收要求。</li>
 *
 *   <li><b>为什么轮询不放在这个池里</b>：百炼转写是异步任务，最长要等 30 分钟。
 *       如果把 {@code pollUntilDone} 这种阻塞轮询丢进本池，8 个线程会被占满 30 分钟，
 *       第 9 个任务只会在队列里静默排队（用户看到状态一直停在 UPLOADED，像是卡死），
 *       而且实例重启后排队任务全丢。因此采用"轮询与提交解耦"方案：
 *       本池只负责提交/落库等短任务，轮询由 {@code @Scheduled} 扫描
 *       {@code ai_interview} 中 {@code status=TRANSCRIBING} 的记录驱动，
 *       天然支持重启续跑，也顺带实现步骤 6 的"30 分钟超时兜底"。</li>
 *
 *   <li><b>拒绝策略</b>：队列满时用 {@link ThreadPoolExecutor.AbortPolicy} 快速失败。
 *       调用方（上传入口）必须捕获 {@code TaskRejectedException}，把记录置为 FAILED 并返回
 *       可读提示；否则异常会冒泡进上传事务导致回滚，用户只看到 500 却不知道原因。
 *       这里刻意不用 CallerRunsPolicy——那会让 Tomcat 线程去跑任务，等于白拆线程池。</li>
 * </ol>
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 面试转写专用线程池。异步方法需显式指定：{@code @Async("interviewExecutor")}
     */
    @Bean("interviewExecutor")
    public ThreadPoolTaskExecutor interviewExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        // 线程名前缀是步骤 1 验收"异步线程池生效"的观察点：日志里应为 interview-asr-1 这类名字
        executor.setThreadNamePrefix("interview-asr-");
        // 低峰期回收核心线程，避免空占内存
        executor.setAllowCoreThreadTimeOut(true);
        // 关停时不等待在途任务——否则一次重启要挂 30 分钟
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("面试转写线程池初始化完成: threadNamePrefix=interview-asr-, core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * 返回 null 表示不覆盖 Spring 的默认异步执行器解析逻辑。
     * 本类只借用 {@link AsyncConfigurer} 来注册全局的异步异常处理器。
     */
    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
        return null;
    }

    /**
     * {@code @Async} 修饰 void 方法时异常不会传播给调用方，默认只打一条 WARN。
     * 这里统一记录，避免转写失败被静默吞掉（步骤 2 需要可读的失败原因）。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("面试异步任务抛出未捕获异常: method={}, params={}", method.getName(), params, ex);
    }
}
