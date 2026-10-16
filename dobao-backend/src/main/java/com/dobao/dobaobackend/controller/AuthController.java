package com.dobao.dobaobackend.controller;

import com.dobao.dobaobackend.auth.GithubAuthException;
import com.dobao.dobaobackend.auth.GithubOAuthClient;
import com.dobao.dobaobackend.auth.GithubUser;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.common.BaseResult;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.entity.DbUser;
import com.dobao.dobaobackend.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录鉴权接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AuthController {

    /**
     * Session 里暂存 state 的键（一次性，回调时立刻删除）
     */
    private static final String STATE_KEY = "DOBAO_OAUTH_STATE";

    /**
     * Session 里暂存"登录后回哪"的键
     */
    private static final String REDIRECT_KEY = "DOBAO_OAUTH_REDIRECT";

    /**
     * 登录成功后浏览器落在哪个**前端 SPA 路由**（本类 302 的目标，必须与前端 router 一致）。
     */
    private static final String FRONTEND_CALLBACK_PATH = "/login/callback";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 会话 Cookie 名。
     */
    @Value("${server.servlet.session.cookie.name:SESSION}")
    private String sessionCookieName;

    private final GithubOAuthProperties properties;
    private final GithubOAuthClient githubOAuthClient;
    private final UserService userService;

    /**
     * 发起 GitHub 授权：生成 state 存 Session，然后 302(重定向) 到 GitHub。
     * @param redirect 登录成功后要回到的前端站内路径（可选，只接受站内相对路径）
     */
    @GetMapping("/oauth/github/authorize")
    public void authorize(@RequestParam(value = "redirect", required = false) String redirect,
                          HttpSession session,
                          HttpServletResponse response) throws IOException {

        String state = generateState();
        session.setAttribute(STATE_KEY, state);
        // 开放重定向防护：只接受站内相对路径，且不允许 //host 这种协议相对写法。
        // 不合法就不写这个属性 —— Spring Session 的 MapSession 把 null 当"删除"，
        // 写成三元表达式（…? redirect : null）能跑通，但依赖了这个隐含语义。
        if (isSafeRedirect(redirect)) {
            session.setAttribute(REDIRECT_KEY, redirect);
        }

        // 构建github授权 URL
        String authorizeUrl = githubOAuthClient.buildAuthorizeUrl(state);
        log.info("发起 GitHub 授权: redirect={}", redirect);
        response.sendRedirect(authorizeUrl);
    }

    /**
     * GitHub 回调接口：校验 state → 换 token → 拉资料 → upsert 本地用户 → 写 Session → 302 回前端。
     * 无论成功失败都跳回前端的回调页（带上 error 参数）：这一步是浏览器顶层跳转，
     * 返回 JSON 用户会看到一屏裸数据。
     */
    @GetMapping("/oauth/github/callback")
    public void callback(@RequestParam(value = "code", required = false) String code,
                         @RequestParam(value = "state", required = false) String state,
                         @RequestParam(value = "error", required = false) String error,
                         @RequestParam(value = "error_description", required = false) String errorDescription,
                         HttpServletRequest request,
                         HttpSession session,
                         HttpServletResponse response) throws IOException {

        Object savedState = session.getAttribute(STATE_KEY);
        // state 一次性：无论校验结果如何都不允许复用
        session.removeAttribute(STATE_KEY);
        String target = (String) session.getAttribute(REDIRECT_KEY);
        session.removeAttribute(REDIRECT_KEY);

        // 1. 参数校验
        if (error != null) {
            log.warn("用户在 GitHub 侧取消或拒绝授权: error={}, desc={}", error, errorDescription);
            redirectToFrontend(response, "/login", "error=denied", target);
            return;
        }
        if (code == null || code.isEmpty()) {
            redirectToFrontend(response, "/login", "error=missing_code", target);
            return;
        }
        if (savedState == null || !savedState.equals(state)) {
            // Session 过期、state 被重放、或跨站伪造的授权请求。
            boolean sessionSeen = request.getSession(false) != null;
            log.warn("OAuth state 校验失败: saved={}, received={}, sessionCookiePresent={}, cookieHeader={}",
                    savedState, state, sessionSeen,
                    request.getHeader(HttpHeaders.COOKIE) == null ? "<无>" : "<有>");
            redirectToFrontend(response, "/login", "error=state", target);
            return;
        }


        try {
            // 2. 换 token 拿到用户信息
            GithubUser githubUser = githubOAuthClient.fetchUser(code);

            // 3. upsert 本地用户
            DbUser user = userService.upsertByGithub(githubUser);
            if (!userService.isEnabled(user)) {
                log.warn("被停用的账号尝试登录: userId={}", user.getUserId());
                redirectToFrontend(response, "/login", "error=disabled", target);
                return;
            }

            // 会话固定攻击防护：登录成功后换一个新的 session id 承载登录态。
            session.invalidate(); // 先失效旧的 session，再创建新的
            HttpSession fresh = request.getSession(true);
            fresh.setAttribute(properties.getSessionUserKey(), user.getUserId());

            log.info("登录成功: userId={}, github={}", user.getUserId(), user.getLogin());
            redirectToFrontend(response, null, null, target);
        } catch (GithubAuthException e) {
            log.warn("GitHub 授权流程失败: {}", e.getMessage());
            redirectToFrontend(response, "/login", "error=exchange", target);
        } catch (Exception e) {
            log.error("GitHub 登录出现未预期异常", e);
            redirectToFrontend(response, "/login", "error=server", target);
        }
    }

    /**
     * 当前登录用户。未登录返回 {@code code=401}（HTTP 仍是 200），前端据此判断登录态。
     */
    @GetMapping("/auth/me")
    public BaseResult<Map<String, Object>> me() {
        String userId = UserContext.getUserId();
        if (userId == null || userId.isEmpty()) {
            return BaseResult.newAuthError();
        }
        DbUser user = userService.findByUserId(userId);
        if (user == null) {
            // 会话里的 uid 在库里不存在（用户被清理等），按未登录处理
            return BaseResult.newAuthError();
        }
        return BaseResult.newSuccess(toView(user));
    }

    /**
     * 退出登录：失效会话并清 Cookie。
     */
    @PostMapping("/auth/logout")
    public BaseResult<String> logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        // 删除型 Cookie 只按 (name, domain, path) 匹配，所以不必复制原 Cookie 的其他属性。
        jakarta.servlet.http.Cookie cookie = new jakarta.servlet.http.Cookie(sessionCookieName, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
        return BaseResult.newSuccess("已退出登录");
    }

    /**
     * 登录方式探测：前端据此决定登录按钮跳哪、是否显示"登录未配置"。不返回任何密钥。
     */
    @GetMapping("/auth/config")
    public BaseResult<Map<String, Object>> config() {
        boolean configured = properties.getClientId() != null && !properties.getClientId().isBlank()
                && properties.getClientSecret() != null && !properties.getClientSecret().isBlank();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", "github");
        data.put("enabled", configured);
        data.put("authorizeUrl", "/oauth/github/authorize");
        data.put("frontendBaseUrl", properties.getFrontendBaseUrl());
        data.put("redirectUriHost", hostOf(properties.getRedirectUri()));
        return BaseResult.newSuccess(data);
    }

    /**
     * 从 URL 里取主机名，解析失败返回 null（前端据此跳过校验）
     */
    private String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            log.warn("配置的 URL 无法解析主机名: {}", url);
            return null;
        }
    }

    /**
     * 组装给前端的用户视图（不含任何敏感字段）
     */
    private Map<String, Object> toView(DbUser user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", user.getUserId());
        view.put("login", user.getLogin());
        view.put("nickname", user.getNickname());
        view.put("avatarUrl", user.getAvatarUrl());
        view.put("email", user.getEmail());
        return view;
    }

    /**
     * 随机 state：32 字节 URL-safe Base64，足够抗猜测
     */
    private String generateState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 只接受站内相对路径：必须以单个 / 开头，且不能是 //（协议相对地址会被浏览器当外站）
     */
    private boolean isSafeRedirect(String redirect) {
        return redirect != null
                && redirect.startsWith("/")
                && !redirect.startsWith("//")
                && !redirect.contains("\\");
    }

    /**
     * 302 回前端。{@code path} 为 null 时落到前端回调页。
     *
     * @param errorQuery 形如 {@code error=state}，可为 null
     * @param target     登录前想去的前端路径，会被带到 {@code /login/callback?redirect=}
     */
    private void redirectToFrontend(HttpServletResponse response, String path,
                                    String errorQuery, String target) throws IOException {
        String base = properties.getFrontendBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        StringBuilder url = new StringBuilder(base);
        if (path != null) {
            url.append(path);
        } else {
            // 成功：统一落到回调页，由它去问 /auth/me 再决定进首页还是提示错误
            url.append(FRONTEND_CALLBACK_PATH);
            if (target != null) {
                url.append("?redirect=").append(URLEncoder.encode(target, StandardCharsets.UTF_8));
            }
        }
        if (errorQuery != null) {
            url.append(url.indexOf("?") >= 0 ? '&' : '?').append(errorQuery);
        }
        response.sendRedirect(url.toString());
    }
}
