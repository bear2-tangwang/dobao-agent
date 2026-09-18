package com.dobao.dobaobackend.config;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * MySQL 业务数据源配置
 * 作为主数据源（@Primary），向量库数据源另行配置
 */
@Configuration
public class MySQLDataSourceConfig {

    /**
     * 读取 spring.datasource 配置项
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties mysqlDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 构建 MySQL 数据源
     */
    @Bean
    @Primary
    public DataSource mysqlDataSource() {
        return mysqlDataSourceProperties().initializeDataSourceBuilder().build();
    }
}
