package com.dobao.dobaobackend.tool;


import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.entity.record.FileInfo;
import com.dobao.dobaobackend.service.EmbeddingService;
import com.dobao.dobaobackend.service.FileManageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 文件内容服务工具：合并文件加载与 RAG 检索，按文件的 {@code embed} 字段自动选择加载方式。
 *
 * <p><b>归属校验</b>：本类由 Spring AI 在做工具调用时执行，跑在 Reactor 链内部，
 * 读不到请求线程的 {@code UserContext}(ThreadLocal)，也不能让 LLM 自己传用户身份
 * （那等于把授权交给模型），因此身份从 <b>Reactor Context</b> 里取
 * （AgentController 在返回 Flux 时写入，见 {@link UserContext#REQUEST_USER_KEY}）。
 * 取不到时退化为"不做归属过滤"。
 */
@Service
@Slf4j
public class FileContentService {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private FileManageService fileManageService;

    /**
     * 加载文件内容或进行RAG检索
     * 根据文件的 embed 字段自动选择合适的加载方式：
     * - embed=1: 使用RAG语义检索（适用于大文件）
     * - embed=0 或 null: 直接加载完整文件内容（适用于小文件）
     *
     * @param fileId   文件ID
     * @param question 用户问题（用于RAG检索）
     * @return 文件信息或检索结果
     */
    @Tool(description = "根据文件ID加载文件内容或进行RAG语义检索。如果文件已向量化(embed=1)则使用语义搜索返回相关片段，否则直接返回完整文件内容。")
    public String loadContent(
            @ToolParam(description = "文件ID") String fileId,
            @ToolParam(description = "用户的问题，用于语义检索（可选）") String question) {
        log.info("执行工具 loadContent: fileId={}, question={}", fileId, question);

        if (fileId == null || fileId.trim().isEmpty()) {
            return "文件ID不能为空";
        }

        // 从 Reactor Context 里取归属用户；不在 Reactor 链里时是空 Context，拿到 null
        String userId = Mono.deferContextual(ctx -> Mono.justOrEmpty(
                        ctx.getOrEmpty(UserContext.REQUEST_USER_KEY)))
                .cast(String.class)
                .block();

        try {
            // fileId 是全局唯一的，只按它查等于"猜到 ID 就能读别人的文件"，所以带上归属用户
            var fileInfo = fileManageService.getFileInfo(fileId, userId);
            if (fileInfo == null) {
                return "文件不存在，文件ID: " + fileId;
            }

            if (fileInfo.getStatus() != FileInfo.FileStatus.SUCCESS) {
                return String.format("文件处理中或处理失败，当前状态: %s，文件ID: %s", fileInfo.getStatus(), fileId);
            }

            Integer embed = fileInfo.getEmbed();
            if (embed != null && embed == 1) {
                // embed=1: 使用RAG语义检索
                return retrieveWithRAG(fileId, fileInfo, question);
            } else {
                // embed=0 或 null: 直接加载完整文件内容
                return loadDirectly(fileId, fileInfo, userId);
            }

        } catch (IllegalArgumentException e) {
            // 归属不符时 FileManageService 也抛这个异常（按"不存在"处理，不泄露存在性）
            return e.getMessage();
        } catch (Exception e) {
            log.error("加载文件内容失败: fileId={}, question={}", fileId, question, e);
            return "加载文件内容失败: " + e.getMessage();
        }
    }

    /**
     * 使用RAG语义检索方式加载文件内容
     */
    private String retrieveWithRAG(String fileId, FileInfo fileInfo, String question) {
        if (question == null || question.trim().isEmpty()) {
            return buildResponse(fileId, fileInfo, "请提供具体问题以进行语义检索。", null);
        }

        List<String> results = embeddingService.ragRetrieve(fileId, question);

        if (results == null || results.isEmpty()) {
            return buildResponse(fileId, fileInfo, "未检索到与问题相关的内容", null);
        }

        return buildResponse(fileId, fileInfo, "RAG检索", results);
    }

    /**
     * 直接加载完整文件内容
     */
    private String loadDirectly(String fileId, FileInfo fileInfo, String userId) {
        String content = fileManageService.getFileContent(fileId, userId);
        String contentText = (content != null && !content.trim().isEmpty()) ? content : "该文件没有可识别的内容";

        return buildResponse(fileId, fileInfo, contentText, null);
    }

    /**
     * 统一构建响应格式
     *
     * @param fileId   文件ID
     * @param fileInfo 文件信息
     * @param content  内容或检索结果
     * @param segments 检索片段列表（RAG模式）
     * @return 统一格式的响应字符串
     */
    private String buildResponse(String fileId, FileInfo fileInfo, String content, List<String> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 文件信息 ===\n");
        sb.append("文件名: ").append(fileInfo.getFileName()).append("\n");
        sb.append("文件类型: ").append(fileInfo.getFileType()).append("\n");

        sb.append("\n=== 文件内容 ===\n");

        if (segments != null && !segments.isEmpty()) {
            // RAG检索结果格式
            sb.append("相关内容: ").append("\n\n");
            for (int i = 0; i < segments.size(); i++) {
                sb.append(segments.get(i)).append("\n\n");
            }
        } else if (content != null) {
            sb.append(content);
        } else {
            sb.append("无内容可显示");
        }

        return sb.toString();
    }
}
