package com.dobao.dobaobackend.testsupport;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * MyBatis-Plus 实体元数据（{@code TableInfo}）的测试工具。
 *
 * <p>纯单测没有 Spring 上下文，{@link TableInfoHelper} 里没有实体的元数据，
 * 于是 {@code LambdaQueryWrapper}/{@code LambdaUpdateWrapper} 的 lambda 解析会抛
 * "can not find lambda cache for this entity"，{@code getTargetSql()}/{@code getSqlSet()}
 * 也就拿不到列名。这里只注册元数据：不建连接池、不读库。
 *
 * <p>做成静态工具类而不是测试基类：需要它的测试类各自在 {@code @BeforeAll} 里显式声明
 * 依赖了哪些实体，继承关系不参与其中。
 */
public final class MybatisPlusTableInfo {

    private MybatisPlusTableInfo() {
    }

    /**
     * 幂等注册实体的 MyBatis-Plus 元数据
     * （guard 必要：{@code TableInfoHelper} 缓存是 JVM 级静态，surefire 同一 fork 跑多个测试类）。
     *
     * @param entities 需要注册的实体类，例如 {@code AiSession.class}
     */
    public static void ensure(Class<?>... entities) {
        for (Class<?> entity : entities) {
            if (TableInfoHelper.getTableInfo(entity) != null) {
                continue;
            }
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
