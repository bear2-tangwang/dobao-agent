package com.dobao.dobaobackend.auth;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记「必须登录」的接口，标注在类上表示该 Controller 全部接口都要登录。
 *
 * <p>路径白名单表达不了 SSE：{@code /agent/**} 里既有需要登录的流式接口，也有
 * {@code /agent/stop} 这类同族接口，所以登录要求统一由本注解声明，拦截器只排除
 * 永远不会带用户上下文的路径。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface LoginRequired {

    /**
     * 未登录时的提示语，便于前端区分场景
     */
    @AliasFor("message")
    String value() default "请先登录";

    @AliasFor("value")
    String message() default "请先登录";
}
