package com.dobao.dobaobackend.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保存用户提问请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveQuestionRequest {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 用户问题
     */
    private String question;

    /**
     * 关联的文件ID（可选）
     */
    private String fileid;

    /**
     * 使用的工具名称，逗号分隔
     */
    private String tools;

    /**
     * 首次响应时间（毫秒）
     */
    private Long firstResponseTime;

    /**
     * 智能体类型，如 chat/websearch/file
     */
    private String agentType;
}
