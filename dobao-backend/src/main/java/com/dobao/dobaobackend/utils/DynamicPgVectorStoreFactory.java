package com.dobao.dobaobackend.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * PgVector 向量库工厂：按表名动态创建或加载向量库实例，供不同业务表隔离存储向量数据。
 *
 * <p>数据源即全应用唯一的 {@code DataSource}（PG 库 dobao-vector）：合库前这里注入的是
 * 独立的 {@code pgVectorDataSource}，与业务侧 MySQL 分属两个连接池，导致
 * {@code FileManageService} 的事务管不住向量写入；合库后业务表与 vector_file_info
 * 同库同池，跨库一致性缺口随之消失。
 */
@Component
@Slf4j
public class DynamicPgVectorStoreFactory {

    private final DataSource dataSource;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public DynamicPgVectorStoreFactory(DataSource dataSource, EmbeddingModel embeddingModel) {
        this.dataSource = dataSource;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 创建（或加载已存在的）PgVectorStore
     *
     * @param tableName 向量表名
     */
    public PgVectorStore createPgVectorStore(String tableName) {
        if (tableName == null || tableName.trim().isEmpty()) {
            throw new IllegalArgumentException("向量表名称不能为空！");
        }
        String actualTableName = tableName.trim();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        boolean tableExists = tableExists(actualTableName);

        if (tableExists) {
            log.info("向量表 [{}] 已存在，开始直接加载PgVectorStore", actualTableName);
        } else {
            log.info("向量表 [{}] 不存在，将自动创建并初始化PgVectorStore", actualTableName);
        }

        PgVectorStore pgVectorStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .removeExistingVectorStoreTable(false)
                .vectorTableName(actualTableName)
                .maxDocumentBatchSize(100)
                .build();

        try {
            pgVectorStore.afterPropertiesSet();
            log.info("PgVectorStore加载/创建完成，表名：{}", actualTableName);
        } catch (Exception e) {
            log.error("PgVectorStore初始化失败，表名：{}", actualTableName, e);
            throw new RuntimeException("初始化PgVectorStore失败", e);
        }

        return pgVectorStore;
    }

    /**
     * 查询向量表是否已存在
     */
    private boolean tableExists(String tableName) {
        try {
            String checkSql = """
                    SELECT EXISTS (
                        SELECT 1
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND LOWER(table_name) = LOWER(?)
                    );
                    """;
            Boolean exists = new JdbcTemplate(dataSource).queryForObject(checkSql, Boolean.class, tableName);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.error("检查向量表 [{}] 是否存在时发生异常", tableName, e);
            return false;
        }
    }
}
