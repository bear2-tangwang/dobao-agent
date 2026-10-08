package com.dobao.dobaobackend.auth;

import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub OAuth 客户端：只做两件事 —— 用 code 换 access_token、用 token 换用户资料。
 *
 * <p>刻意不引 {@code spring-boot-starter-oauth2-client}：那套要接管整条安全过滤器链，
 * 而本项目已有自定义登录页与统一返回体，手写这两次 HTTP 调用的适配成本更低。
 * client_secret 只在这里出现，永远不会经由任何接口回到浏览器。
 */
@Slf4j
@Component
public class GithubOAuthClient {

    private static final String AUTHORIZE_ENDPOINT = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String USER_ENDPOINT = "https://api.github.com/user";
    private static final String EMAILS_ENDPOINT = "https://api.github.com/user/emails";

    /**
     * read:user 读资料；user:email 才能读到私密邮箱（/user/emails 要求这个 scope）。
     * 邮箱读不到不阻塞登录，只是 email 落空。
     */
    private static final String SCOPE = "read:user user:email";

    /**
     * 可重试的状态码：GitHub token 端点在 5xx / 429 上可重试（认证类错误不能重试）。
     */
    private static final List<String> RETRYABLE_STATUS = List.of("429", "500", "502", "503", "504");

    /**
     * 超时必须显式设置：RestClient 默认"无限等"，网络不通时回调接口会一直挂住，
     * 用户只看到"登录失败"，日志里没有任何线索。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOKEN_READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration API_READ_TIMEOUT = Duration.ofSeconds(20);

    private final GithubOAuthProperties properties;

    /** 换 token 用：超时短一些，避免把回调接口拖死 */
    private final RestClient tokenClient;

    /** 读用户资料（api.github.com）用 */
    private final RestClient apiClient;

    public GithubOAuthClient(GithubOAuthProperties properties) {
        this.properties = properties;
        this.tokenClient = buildClient(TOKEN_READ_TIMEOUT);
        this.apiClient = buildClient(API_READ_TIMEOUT);
    }

    /**
     * 构造 RestClient：显式设置超时，需要时把代理只挂给这两个 GitHub 客户端。
     */
    private RestClient buildClient(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        applyProxy(factory);

        return RestClient.builder()
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(factory)
                .build();
    }

    /**
     * 需要经代理才能访问 github.com 时，把代理只挂在这两个 GitHub 客户端上
     * （见 {@link GithubOAuthProperties#getProxyHost()}）。
     *
     * <p>JVM 不读 Windows 的系统代理设置：直连 token 端点可能只读到半个响应体，被 Jackson
     * 包装成反序列化错误，看起来像 bug 其实是网络。不用 {@code -Dhttps.proxyHost}，
     * 那会把 DashScope / MinIO 这些直连更快的调用也塞进代理。
     */
    private void applyProxy(SimpleClientHttpRequestFactory factory) {
        String host = properties.getProxyHost();
        Integer port = properties.getProxyPort();
        if (host == null || host.isBlank() || port == null) {
            return;
        }
        factory.setProxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
        log.info("GitHub OAuth 经代理访问: {}:{}", host, port);
    }

    /**
     * 拼接 GitHub 授权页地址（后端 302 用）。
     *
     * @param state 防 CSRF 的一次性随机串
     */
    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromUriString(AUTHORIZE_ENDPOINT)
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .build()
                .toUriString();
    }

    /**
     * 用授权码换 access_token，并拉取用户资料。
     *
     * @param code GitHub 回调带回的授权码
     * @throws GithubAuthException 任一环节失败（不会把 client_secret 写进异常消息）
     */
    public GithubUser fetchUser(String code) {
        String accessToken = exchangeToken(code); // 换 access_token
        return fetchProfile(accessToken); // 用 access_token 拉取用户资料
    }

    /**
     * 用授权码换 access_token。
     * @param code GitHub 回调带回的授权码
     * @return access_token
     */
    private String exchangeToken(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());

        JsonNode token = postForm(URI.create(TOKEN_ENDPOINT), form);
        if (token.hasNonNull("error")) {
            // GitHub 在这一步用 200 + error 字段表达失败（如 bad_verification_code）
            throw new GithubAuthException("换取 access_token 失败: "
                    + token.path("error").asText() + " " + token.path("error_description").asText(""));
        }
        String accessToken = token.path("access_token").asText(null);
        if (accessToken == null || accessToken.isEmpty()) {
            throw new GithubAuthException("GitHub 未返回 access_token");
        }
        return accessToken;
    }

    /**
     * 用 access_token 拉取用户资料。
     * @param accessToken access_token
     * @return 用户资料
     */
    private GithubUser fetchProfile(String accessToken) {
        JsonNode user = apiClient.get()
                .uri(URI.create(USER_ENDPOINT))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(JsonNode.class);

        if (user == null || user.path("id").isMissingNode()) {
            throw new GithubAuthException("拉取 GitHub 用户资料失败：响应缺少 id");
        }

        String providerUid = user.path("id").asText();
        String login = user.path("login").asText(null);
        String name = user.path("name").isNull() ? null : user.path("name").asText(null);
        String avatar = user.path("avatar_url").asText(null);

        // /user 的 email 在用户把邮箱设为私密时是 null，这时才去查 /user/emails
        String email = user.path("email").isNull() ? null : user.path("email").asText(null);
        if (email == null || email.isEmpty()) {
            email = fetchPrimaryEmail(accessToken);
        }

        log.info("GitHub 授权成功: id={}, login={}, email={}", providerUid, login, email);
        return new GithubUser(providerUid, login, name, email, avatar);
    }

    /**
     * 取主邮箱（primary && verified）。读不到就返回 null —— 不因为邮箱缺失而拒绝登录。
     */
    private String fetchPrimaryEmail(String accessToken) {
        try {
            JsonNode emails = apiClient.get()
                    .uri(URI.create(EMAILS_ENDPOINT))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .retrieve()
                    .body(JsonNode.class);

            if (emails == null || !emails.isArray()) {
                return null;
            }
            List<String> fallback = new ArrayList<>();
            for (JsonNode node : emails) {
                String address = node.path("email").asText(null);
                if (address == null) {
                    continue;
                }
                if (node.path("primary").asBoolean(false) && node.path("verified").asBoolean(false)) {
                    return address;
                }
                if (node.path("verified").asBoolean(false)) {
                    fallback.add(address);
                }
            }
            // 没有 primary 时退而求其次用任一已验证邮箱
            return fallback.isEmpty() ? null : fallback.get(0);
        } catch (Exception e) {
            log.warn("读取 GitHub 邮箱失败（不阻塞登录）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * POST 表单 + 可重试状态码重试（429 / 5xx）。
     */
    private JsonNode postForm(URI uri, MultiValueMap<String, String> form) {
        int attempts = 3;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                JsonNode body = tokenClient.post()
                        .uri(uri)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
                if (body == null) {
                    throw new GithubAuthException("GitHub 返回空响应");
                }
                return body;
            } catch (RestClientResponseException e) {
                String status = String.valueOf(e.getStatusCode().value());
                if (attempt < attempts && RETRYABLE_STATUS.contains(status)) {
                    log.warn("GitHub token 端点返回 {}，第 {}/{} 次重试", status, attempt, attempts);
                    sleepQuietly(attempt * 500L);
                    continue;
                }
                // 不把 form（含 client_secret）写进日志或异常
                throw new GithubAuthException("请求 GitHub token 端点失败: HTTP " + status, e);
            } catch (GithubAuthException e) {
                throw e;
            } catch (Exception e) {
                if (attempt < attempts) {
                    log.warn("请求 GitHub token 端点异常，第 {}/{} 次重试: {}", attempt, attempts, e.getMessage());
                    sleepQuietly(attempt * 500L);
                    continue;
                }
                throw new GithubAuthException("请求 GitHub token 端点异常: " + e.getMessage(), e);
            }
        }
        throw new GithubAuthException("请求 GitHub token 端点失败：重试次数已用尽");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubAuthException("等待重试被中断", e);
        }
    }
}
