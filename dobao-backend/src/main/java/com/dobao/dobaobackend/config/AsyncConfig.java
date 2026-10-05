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
 * <p>提交动作必须离开 Tomcat 线程；最长 30 分钟的轮询不占用本池，由 {@code @Scheduled}
 * 扫描 {@code ai_interview} 中 {@code status=TRANSCRIBING} 的记录驱动，以支持重启续跑。
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
        // 日志中的线程名形如 interview-asr-1，便于定位转写任务
        executor.setThreadNamePrefix("interview-asr-");
        executor.setAllowCoreThreadTimeOut(true);
        // 不等在途任务：单次转写可能挂 30 分钟，等待会让重启卡住
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        // 队列满即失败：调用方负责捕获 TaskRejectedException 置 FAILED；
        // 不能用 CallerRunsPolicy——那会让 Tomcat 线程替跑去执行任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        log.info("面试转写线程池初始化完成: threadNamePrefix=interview-asr-, core={}, max={}, queue={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * 返回 null 表示不覆盖 Spring 的默认异步执行器解析逻辑：
     * 本类只借用 {@link AsyncConfigurer} 注册全局的异步异常处理器。
     */
    @Override
    public java.util.concurrent.Executor getAsyncExecutor() {
        return null;
    }

    /**
     * {@code @Async} 修饰 void 方法时异常不会传播给调用方（默认只打一条 WARN），这里统一记录。
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("面试异步任务抛出未捕获异常: method={}, params={}", method.getName(), params, ex);
    }
}
