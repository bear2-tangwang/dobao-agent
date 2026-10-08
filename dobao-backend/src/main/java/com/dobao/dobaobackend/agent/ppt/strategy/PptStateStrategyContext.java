package com.dobao.dobaobackend.agent.ppt.strategy;


import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.service.*;
import com.dobao.dobaobackend.utils.ImageGenerationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Sinks;

import java.util.List;

/**
 * PPT状态策略上下文
 * 用于在策略间共享依赖和工具方法
 */
public class PptStateStrategyContext {

    private final ChatClient chatClient;
    private final ChatModel chatModel;
    private final AiPptInstService pptInstService;
    private final AiPptTemplateService pptTemplateService;
    private final PptPythonRenderService pythonRenderService;
    private final ImageGenerationService imageGenerationService;
    private final MinioService minioService;
    private final AiSessionService sessionService;
    private final AgentTaskManager taskManager;
    private final List<ToolCallback> toolCallbacks;
    private final ChatMemory chatMemory;

    private Long currentSessionId;
    private String currentConversationId;
    private boolean modifyMode;
    private String modifyQuery;  // 当前修改需求（仅在 modifyMode 为 true 时有效）

    public PptStateStrategyContext(ChatClient chatClient, ChatModel chatModel,
                                    AiPptInstService pptInstService,
                                    AiPptTemplateService pptTemplateService,
                                    PptPythonRenderService pythonRenderService,
                                    ImageGenerationService imageGenerationService,
                                    MinioService minioService,
                                    AiSessionService sessionService,
                                    AgentTaskManager taskManager,
                                    List<ToolCallback> toolCallbacks,
                                    ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatModel = chatModel;
        this.pptInstService = pptInstService;
        this.pptTemplateService = pptTemplateService;
        this.pythonRenderService = pythonRenderService;
        this.imageGenerationService = imageGenerationService;
        this.minioService = minioService;
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.toolCallbacks = toolCallbacks;
        this.chatMemory = chatMemory;
    }

    public ChatClient getChatClient() {
        return chatClient;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public AiPptInstService getPptInstService() {
        return pptInstService;
    }

    public AiPptTemplateService getPptTemplateService() {
        return pptTemplateService;
    }

    public PptPythonRenderService getPythonRenderService() {
        return pythonRenderService;
    }

    public ImageGenerationService getImageGenerationService() {
        return imageGenerationService;
    }

    public MinioService getMinioService() {
        return minioService;
    }

    public AiSessionService getSessionService() {
        return sessionService;
    }

    public AgentTaskManager getTaskManager() {
        return taskManager;
    }

    public List<ToolCallback> getToolCallbacks() {
        return toolCallbacks;
    }

    public ChatMemory getChatMemory() {
        return chatMemory;
    }

    public Long getCurrentSessionId() {
        return currentSessionId;
    }

    public void setCurrentSessionId(Long currentSessionId) {
        this.currentSessionId = currentSessionId;
    }

    public String getCurrentConversationId() {
        return currentConversationId;
    }

    public void setCurrentConversationId(String currentConversationId) {
        this.currentConversationId = currentConversationId;
    }

    public void setModifyMode(boolean modifyMode) {
        this.modifyMode = modifyMode;
    }

    public boolean isModifyMode() {
        return modifyMode;
    }

    public void setModifyQuery(String modifyQuery) {
        this.modifyQuery = modifyQuery;
    }

    public String getModifyQuery() {
        return modifyQuery;
    }

    /**
     * 登记 Disposable，供任务取消时中断流
     *
     * @param conversationId 会话ID
     * @param disposable    Disposable 对象
     */
    public void setDisposable(String conversationId, reactor.core.Disposable disposable) {
        if (conversationId != null && taskManager != null && disposable != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

    /**
     * 把历史消息追加到消息列表：skipSystem 跳过系统消息，addLabel 先插入"对话历史："标签
     *
     * @param conversationId 会话ID
     * @param messages      目标消息列表
     * @param skipSystem    是否跳过系统消息
     * @param addLabel     是否添加"对话历史："标签
     */
    public void loadChatHistory(String conversationId, List<Message> messages, boolean skipSystem, boolean addLabel) {
        if (conversationId != null && chatMemory != null) {
            List<Message> history = chatMemory.get(conversationId);
            if (history != null && !history.isEmpty()) {
                if (addLabel) {
                    messages.add(new UserMessage("对话历史："));
                }
                for (Message msg : history) {
                    if (skipSystem && msg instanceof SystemMessage) {
                        continue;
                    }
                    messages.add(msg);
                }
            }
        }
    }

    /**
     * 转义引号与换行后拼出流式响应的单行 JSON
     */
    public String createJsonResponse(String content, String type) {
        return String.format("{\"type\":\"%s\",\"content\":\"%s\"}",
                type, content.replace("\"", "\\\"").replace("\n", "\\n"));
    }

    /**
     * 创建text类型响应
     */
    public String createTextResponse(String content) {
        return createJsonResponse(content, "text");
    }

    /**
     * 创建thinking类型响应
     */
    public String createThinkingResponse(String content) {
        return createJsonResponse(content, "thinking");
    }

    /**
     * 判断是否可以进入下一步：模型输出【开始生成PPT】则继续，【暂停生成PPT】或追问性措辞则停下
     */
    public boolean shouldContinueToNextStep(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }

        // 使用 trim 避免前后空格影响匹配
        String trimmedResponse = response.trim();

        if (trimmedResponse.contains("【开始生成PPT】") || trimmedResponse.contains("【开始生成PPT】".toLowerCase())) {
            return true;
        }

        if (trimmedResponse.contains("【暂停生成PPT】") || trimmedResponse.contains("【暂停生成PPT】".toLowerCase())) {
            return false;
        }

        // 未命中显式标记时，按追问特征词兜底判断
        String[] stopKeywords = {
                "【暂停生成PPT】", "【暂停生成ppt】",
                "请问", "请问您", "请问是否", "请提供", "请问需要",
                "请问想", "请问希望", "请问要", "请问您的"
        };

        String lowerResponse = trimmedResponse.toLowerCase();
        for (String keyword : stopKeywords) {
            if (lowerResponse.contains(keyword.toLowerCase())) {
                return false;
            }
        }

        return true;
    }

    /**
     * 交给工厂执行下一个状态
     *
     * @param inst PPT 实例
     * @param sink 响应流
     * @param query 用户查询
     * @param thinkingBuffer 思考缓冲区
     */
    public void continueStateMachine(AiPptInst inst, Sinks.Many<String> sink, String query,
                                     StringBuilder thinkingBuffer) {
        PptStateStrategyFactory.getInstance().executeNextState(inst, sink, query, thinkingBuffer, this);
    }
}
