package com.dobao.dobaobackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub OAuth 配置，对应 application.yml 的 {@code github.oauth}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "github.oauth")
public class GithubOAuthProperties {

    /**
     * GitHub OAuth App 的 Client ID
     */
    private String clientId;

    /**
     * GitHub OAuth App 的 Client Secret。只能待在服务端，不得通过任何接口回传给前端。
     */
    private String clientSecret;

    /**
     * 回调地址。必须与 GitHub OAuth App 里登记的 Authorization callback URL 完全一致，
     * 主机名也要与浏览器发起授权的地址相同（统一用 127.0.0.1，不要与 localhost 混用，
     * 前端对应 VITE_AUTH_BASE），否则 authorize 写下的 state 与回调读取它的不是同一个
     * Cookie，回跳后表现为"未登录"。
     */
    private String redirectUri;

    /**
     * 访问 github.com 用的代理主机（HTTP CONNECT），留空=直连。
     *
     * <p>JVM 不读 Windows 的系统代理设置：直连 {@code github.com/login/oauth/access_token}
     * 可能只读到半个响应体，被 Jackson 报成反序列化错误。只作用于本类的两次 GitHub 调用，
     * 不用 {@code -Dhttps.proxyHost}（那会把 DashScope / MinIO 这些直连更快的调用也塞进代理）。
     */
    private String proxyHost = "";

    /**
     * 代理端口，与 {@link #proxyHost} 配套；{@code proxyHost} 留空时本项不生效。
     */
    private Integer proxyPort = 7892;

    /**
     * 登录成功后浏览器要落回的前端地址
     */
    private String frontendBaseUrl = "http://127.0.0.1:5173";

    /**
     * 未登录时数据归属的兜底用户，与各业务表 user_id 列的 DEFAULT 'default' 对齐
     */
    private String defaultUserId = "default";

    /**
     * 登录态在 Session 中占用的键名
     */
    private String sessionUserKey = "DOBAO_UID";

    /**
     * 是否开放 {@code GET /auth/diagnostics}。生产应置为 false：它会暴露当前会话 id。
     */
    private boolean diagnosticsEnabled = true;
}
