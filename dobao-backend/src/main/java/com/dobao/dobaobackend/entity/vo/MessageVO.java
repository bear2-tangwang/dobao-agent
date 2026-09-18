package com.dobao.dobaobackend.entity.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息VO
 * 单条问答记录及其关联的文件信息
 */
@Data
@Builder
public class MessageVO {
    /** 记录ID */
    private Long id;
    /** 用户问题 */
    private String question;
    /** AI回复 */
    private String answer;
    /** 思考过程 */
    private String thinking;
    /** 使用的工具名称（逗号分隔） */
    private String tools;
    /** 参考链接JSON */
    private String reference;
    /** 创建时间 */
    private LocalDateTime createTime;
    /** 关联文件ID */
    private String fileid;
    /** 推荐追问问题JSON */
    private String recommend;
    /** 关联文件名 */
    private String fileName;
    /** 关联文件类型 */
    private String fileType;
    /** 关联文件大小（字节） */
    private Long fileSize;
}
