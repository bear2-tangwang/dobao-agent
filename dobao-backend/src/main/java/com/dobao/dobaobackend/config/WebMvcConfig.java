package com.dobao.dobaobackend.config;

import com.dobao.dobaobackend.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把登录拦截器挂到 MVC 上。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final GithubOAuthProperties properties;

    @Bean
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor(properties);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor())
                .addPathPatterns("/**")
                // 只排除"永远不带业务用户上下文"的路径。业务接口的登录要求由 @LoginRequired
                // 声明，不用白名单表达：/agent/** 里既有需要登录的 SSE 流，也有同族接口。
                //
                // /auth/** 不能排除：它虽然不需要 @LoginRequired 校验，但必须靠拦截器把
                // session 里的 uid 写进 UserContext，否则 /auth/me 读到的一直是 null，
                // 登录成功也会返回 401。
                .excludePathPatterns(
                        "/oauth/**",              // 授权发起与回调（浏览器顶层跳转）
                        "/favicon.ico",
                        "/error",
                        "/css/**", "/js/**", "/images/**", "/webjars/**"
                );
    }
}
