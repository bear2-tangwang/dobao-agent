package com.dobao.dobaobackend.utils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring 应用上下文静态工具类
 * 用于在非Spring管理的类中静态获取Bean、环境变量
 */
@Component
@Slf4j
public class AppContextClient {
    @Resource
    private ApplicationContext applicationContext;
    private static ApplicationContext applicationContextRef;

    public AppContextClient() {
    }

    /**
     * 启动时把Spring上下文缓存到静态变量
     */
    @PostConstruct
    public void init() {
        applicationContextRef = this.applicationContext;
    }

    /**
     * 上下文是否已就绪
     */
    public static boolean ready() {
        return applicationContextRef == null;
    }

    /**
     * 根据Bean名称获取Bean
     */
    public static <T> T getBean(String beanName) {
        if (StringUtils.isNotEmpty(beanName) && applicationContextRef != null) {
            try {
                return (T) applicationContextRef.getBean(beanName);
            } catch (Exception var2) {
                log.error("未找到 Bean: " + beanName, var2);
            }
        }

        return null;
    }

    /**
     * 根据类型获取Bean
     */
    public static <T> T getBean(Class<T> requiredType) {
        try {
            return applicationContextRef.getBean(requiredType);
        } catch (Exception var2) {
            log.error("未找到 Bean 类型: " + requiredType, var2);
            return null;
        }
    }

    /**
     * 根据类型获取该类型的所有Bean
     */
    public static <T> Map<String, T> getBeansOfType(Class<T> type) {
        try {
            return applicationContextRef.getBeansOfType(type);
        } catch (Exception var2) {
            log.error("未找到 Bean 类型集合: " + type, var2);
            return null;
        }
    }

    /**
     * 获取配置项
     */
    public static String getEnvProperty(String key) {
        return applicationContextRef.getEnvironment().getProperty(key);
    }

    /**
     * 获取应用名称
     */
    public static String getAppName() {
        return getEnvProperty("spring.application.name");
    }
}
