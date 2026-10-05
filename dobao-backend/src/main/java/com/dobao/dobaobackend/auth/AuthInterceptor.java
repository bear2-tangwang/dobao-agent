package com.dobao.dobaobackend.auth;

import com.dobao.dobaobackend.config.GithubOAuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录拦截器：把 Session 里的 uid 放进 {@link UserContext}，并校验 {@link LoginRequired}。
 *
 * <p>校验失败一律返回 401 JSON 而绝不 302：前端全部请求走 fetch / EventSource，
 * 302 会被自动跟随并把 HTML 当成响应体；SSE 请求单独吐一个 {@code event: unauthorized} 帧。
 * afterCompletion 必须清 ThreadLocal，否则 Tomcat 线程复用会把上一个请求的用户身份带过来。
 */
@Slf4j
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final GithubOAuthProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        HttpSession session = request.getSession(false);
        // 获取用户id 从 Session 里
        Object uid = (session == null) ? null : session.getAttribute(properties.getSessionUserKey());
        if (uid != null && !uid.toString().isEmpty()) {
            // 把用户id 放进 UserContext
            UserContext.setUserId(uid.toString());
        }

        // 未标注 @LoginRequired 的接口（/auth/**、/oauth/**）放行，由 Controller 自行处理
        LoginRequired required = resolveAnnotation(handler);
        if (required == null) {
            return true;
        }
        if (UserContext.getUserId() == null) {
            writeUnauthorized(request, response, required.message());
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        UserContext.clear();
    }

    private LoginRequired resolveAnnotation(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return null;
        }
        // 方法上的注解优先于类上的
        LoginRequired onMethod = handlerMethod.getMethodAnnotation(LoginRequired.class);
        return onMethod != null ? onMethod : handlerMethod.getBeanType().getAnnotation(LoginRequired.class);
    }

    /**
     * 未登录的统一应答：按 {@code Accept} 分流，SSE 吐具名错误帧，其余吐与
     * {@code BaseResult} 同构的 JSON（{@code code=401}）。
     */
    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response, String message)
            throws Exception {
        // 判断是不是 SSE 请求，是就吐具名错误帧，否则就吐 JSON 错误帧
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        boolean sse = accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (sse) {
            response.setContentType("text/event-stream;charset=UTF-8");
            // 具名事件 + data：前端解析到 event=unauthorized 就跳登录页
            response.getWriter().write("event: unauthorized\n"
                    + "data: {\"code\":401,\"message\":\"" + escape(message) + "\"}\n\n");
        } else {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":401,\"message\":\"" + escape(message) + "\",\"data\":null}");
        }
        response.getWriter().flush();
        log.debug("未登录访问被拦截: {} {}", request.getMethod(), request.getRequestURI());
    }

    /**
     * 极简 JSON 字符串转义：message 来自注解常量，这里只做防呆
     */
    private String escape(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "请先登录";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
