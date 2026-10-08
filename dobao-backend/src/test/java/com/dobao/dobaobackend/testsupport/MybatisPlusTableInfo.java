package com.dobao.dobaobackend.testsupport;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

/**
 * MyBatis-Plus 实体元数据（{@code TableInfo}）的测试工具：纯单测没有 Spring 上下文，lambda 解析会抛
 * "can not find lambda cache for this entity"，{@code getTargetSql()}/{@code getSqlSet()} 拿不到列名。
 * 这里只注册元数据，不建连接池、不读库；需要它的测试类各自在 {@code @BeforeAll} 里声明依赖的实体。
 */
public final class MybatisPlusTableInfo {

    private MybatisPlusTableInfo() {
    }

    /**
     * 幂等注册实体的 MyBatis-Plus 元数据（guard 必要：{@code TableInfoHelper} 缓存是 JVM 级静态，
     * surefire 同一 fork 会跑多个测试类）。
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
