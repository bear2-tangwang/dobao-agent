package com.dobao.dobaobackend.auth;

/**
 * GitHub OAuth 交互失败（换 token 失败、拉取用户失败等）。
 * 单独定义是为了让 AuthController 能把授权流程失败与代码自身缺陷分开处理：前者给用户
 * 可读提示并跳回登录页，后者按 500 处理并打日志。
 */
public class GithubAuthException extends RuntimeException {

    public GithubAuthException(String message) {
        super(message);
    }

    public GithubAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
