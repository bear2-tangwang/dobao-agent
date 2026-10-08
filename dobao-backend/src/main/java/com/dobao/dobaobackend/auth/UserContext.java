package com.dobao.dobaobackend.auth;

/**
 * 当前请求的登录用户（由 {@link AuthInterceptor} 写入，请求结束时清理）。
 *
 * <p>只在 Tomcat 请求线程内同步执行时可靠：SSE 流跑在 Reactor 线程上，{@code @Async} /
 * {@code @Scheduled} 任务没有请求上下文，这些场景读到的一定是 null，需显式传参或改用
 * {@link #REQUEST_USER_KEY} 走 Reactor Context。
 */
public final class UserContext {

    /**
     * Session 里存 uid 的键的默认值；实际读取以
     * {@link com.dobao.dobaobackend.config.GithubOAuthProperties} 的配置为准。
     */
    public static final String DEFAULT_SESSION_KEY = "DOBAO_UID";

    /**
     * Reactor Context 里存 uid 的键。
     *
     * <p>{@code @Tool} 方法由 Spring AI 在 Reactor 链内部调用，那时没有请求线程的
     * ThreadLocal，只能在 Controller 里对返回的 Flux 调
     * {@code contextWrite(ctx -> ctx.put(REQUEST_USER_KEY, userId))}，工具里再用
     * {@code Mono.deferContextual} 读出来；不在 Reactor 链里时退化为空 Context，不抛异常。
     */
    public static final String REQUEST_USER_KEY = "DOBAO_REQUEST_USER_ID";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    /**
     * 当前登录用户 ID；未登录或非请求线程时为 {@code null}。
     */
    public static String getUserId() {
        return CURRENT.get();
    }

    /**
     * 当前登录用户 ID，未登录时回退到兜底用户
     * （{@code github.oauth.default-user-id}）。
     */
    public static String getUserIdOrDefault(String defaultUserId) {
        String uid = CURRENT.get();
        return (uid == null || uid.isEmpty()) ? defaultUserId : uid;
    }

    public static void setUserId(String userId) {
        CURRENT.set(userId);
    }

    /**
     * 必须清理：Tomcat 线程是复用的，不清理会把上一个请求的用户带到下一个请求上。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
