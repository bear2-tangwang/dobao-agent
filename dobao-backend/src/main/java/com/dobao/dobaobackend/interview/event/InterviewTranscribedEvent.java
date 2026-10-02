package com.dobao.dobaobackend.interview.event;

/**
 * 转写完成事件。
 *
 * <p>为什么用事件而不是直接调用：轮询方法（{@code @Scheduled}）与后续的分析任务在同一个 Bean 里，
 * 同类内部直接调用 {@code @Async} 方法会**绕过 Spring 代理、静默变成同步执行**
 * （表现为"轮询线程被分析任务占住几分钟"）。通过 {@code ApplicationEventMulticaster} 派发，
 * 监听方是从容器里取出的代理对象，{@code @Async} 才会真正生效。
 *
 * @param interviewId 业务标识
 */
public record InterviewTranscribedEvent(String interviewId) {
}
