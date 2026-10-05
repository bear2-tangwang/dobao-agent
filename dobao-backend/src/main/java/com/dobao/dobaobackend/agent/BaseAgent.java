package com.dobao.dobaobackend.agent;


import com.alibaba.fastjson2.JSON;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.common.AgentResponse;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.prompts.ReactAgentPrompts;
import com.dobao.dobaobackend.service.AgentTaskManager;
import com.dobao.dobaobackend.service.AiSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Agent抽象基类 提供所有agent的通用方法
 */
@Slf4j
public abstract class BaseAgent {

    protected final ChatModel chatModel;
    protected final String name;
    protected ChatMemory chatMemory;
    protected AiSessionService sessionService;
    protected AgentTaskManager taskManager;
    protected String agentType;

    protected boolean enableRecommendations = true;

    protected long startTime;
    protected long firstResponseTime;
    protected Set<String> usedTools;
    protected Long currentSessionId;
    protected String currentConversationId;
    protected String currentQuestion;
    protected String currentRecommendations;

    /**
     * 本次请求归属的用户：Agent 逻辑跑在 Reactor 线程上，那里读不到 UserContext 的
     * ThreadLocal，因此在 AgentController 构造 Agent 时注入一次，后续一律读此字段。
     */
    protected String defaultUserId;

    public BaseAgent(String name, ChatModel chatModel, String agentType) {
        this.name = name;
        this.chatModel = chatModel;
        this.agentType = agentType;
    }

    /**
     * 子类实现的执行入口，返回流式输出。
     */
    public abstract Flux<String> execute(String conversationId, String question);

    protected void loadChatHistory(String conversationId, List<Message> messages, boolean skipSystem, boolean addLabel) {
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

    protected List<Message> getChatHistory(String conversationId) {
        if (conversationId != null && chatMemory != null) {
            return chatMemory.get(conversationId);
        }
        return null;
    }

    /**
     * 从数据库读取该会话的历史记录，构建持久化 ChatMemory。
     */
    public ChatMemory createPersistentChatMemory(String sessionId, int maxMessages) {
        if (sessionService == null) {
            log.warn("会话服务为空，无法加载对话记忆");
            return MessageWindowChatMemory.builder().maxMessages(maxMessages).build();
        }

        // 必须按归属用户过滤：会话ID由前端生成，不校验归属就等于知道别人的
        // conversationId 就能把别人的对话读成自己的记忆
        List<AiSession> history = sessionService.findRecentBySessionId(sessionId, maxMessages, resolveUserId());

        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(maxMessages).build();

        if (history != null && !history.isEmpty()) {
            // 查询结果是倒序（最新在前），从尾部回放才能保证按时间顺序加入
            for (int i = history.size() - 1; i >= 0; i--) {
                AiSession record = history.get(i);
                if (record.getQuestion() != null) {
                    chatMemory.add(sessionId, new UserMessage(record.getQuestion()));
                }

                if (record.getAnswer() != null) {
                    chatMemory.add(sessionId, new AssistantMessage(record.getAnswer()));
                }
            }
            log.info("加载会话历史: sessionId={}, userId={}, recordCount={}", sessionId, resolveUserId(), history.size());
        }

        return chatMemory;
    }

    protected String createResponse(String content, String type) {
        return AgentResponse.json(type, content);
    }

    protected String createTextResponse(String content) {
        return AgentResponse.text(content);
    }

    protected String createThinkingResponse(String content) {
        return AgentResponse.thinking(content);
    }

    /**
     * content 为引用来源 JSON 数组字符串，条数由 AgentResponse 自动计算。
     */
    protected String createReferenceResponse(String content) {
        return AgentResponse.reference(content);
    }

    protected String createErrorResponse(String content) {
        return AgentResponse.error(content);
    }

    protected String createRecommendResponse(String content) {
        return AgentResponse.recommend(content);
    }

    protected void recordFirstResponse() {
        if (firstResponseTime == 0 && startTime > 0) {
            firstResponseTime = System.currentTimeMillis() - startTime;
            log.info("记录首次响应时间: {}ms", firstResponseTime);
        }
    }

    /**
     * 该会话已有任务在执行时返回错误流，无冲突返回 null。
     */
    protected Flux<String> checkRunningTask(String conversationId) {
        if (conversationId != null && taskManager != null && taskManager.hasRunningTask(conversationId)) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }
        return null;
    }

    /**
     * 注册失败（会话已有任务）时返回 null。
     */
    protected AgentTaskManager.TaskInfo registerTask(String conversationId, Sinks.Many<String> sink) {
        if (conversationId != null && taskManager != null) {
            AgentTaskManager.TaskInfo taskInfo = taskManager.registerTask(conversationId, sink, agentType);
            if (taskInfo == null) {
                log.warn("任务注册失败: conversationId={}", conversationId);
            }
            return taskInfo;
        }
        return null;
    }

    protected void removeTask(String conversationId) {
        if (conversationId != null && taskManager != null) {
            taskManager.removeTask(conversationId);
        }
    }

    protected void initTimers() {
        startTime = System.currentTimeMillis();
        firstResponseTime = 0;
    }

    protected long getTotalResponseTime() {
        if (startTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 返回逗号分隔的工具名，未使用工具时返回空串。
     */
    protected String getUsedToolsString() {
        if (usedTools == null || usedTools.isEmpty()) {
            return "";
        }
        return String.join(",", usedTools);
    }

    protected void clearUsedTools() {
        if (usedTools != null) {
            usedTools.clear();
        }
    }

    protected void recordUsedTool(String toolName) {
        if (usedTools != null && toolName != null) {
            usedTools.add(toolName);
        }
    }

    /**
     * 生成推荐问题的 JSON 字符串，未启用或生成失败时返回 null。
     */
    protected String generateRecommendations(String conversationId, String currentQuestion, String currentAnswer) {
        if (!enableRecommendations) {
            return null;
        }

        try {
            List<Message> messages = new ArrayList<>();

            // 1. 添加系统提示词
            messages.add(new SystemMessage(ReactAgentPrompts.getRecommendPrompt()));

            // 2. 添加历史消息
            loadChatHistory(conversationId, messages, true, true);

            // 3. 添加当前会话的消息（最新的消息，放在最后）
            messages.add(new UserMessage("当前会话："));
            messages.add(new UserMessage(currentQuestion));
            if (currentAnswer != null) {
                messages.add(new AssistantMessage(currentAnswer));
            }

            // 4. 添加格式说明消息
            // 使用 BeanOutputConverter 进行结构化输出
            BeanOutputConverter<List<String>> converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
            });

            // 添加格式说明消息
            messages.add(new UserMessage("请根据上述对话生成3个推荐问题。输出格式为：\n" + converter.getFormat()));

            // 5. 调用模型生成推荐问题
            String response = ChatClient.builder(chatModel).build()
                    .prompt()
                    .messages(messages)
                    .call()
                    .content();

            // 6. 使用 converter 转换响应
            if (response != null && !response.isEmpty()) {
                List<String> recommendations = converter.convert(response);
                if (recommendations != null && !recommendations.isEmpty()) {
                    String jsonStr = JSON.toJSONString(recommendations);
                    log.info("生成推荐问题成功: {}", jsonStr);
                    return jsonStr;
                }
            }

            log.warn("生成推荐问题失败，响应格式无效: {}", response);
            return null;
        } catch (Exception e) {
            log.error("生成推荐问题异常", e);
            return null;
        }
    }

    protected boolean updateAnswer(UpdateAnswerRequest request) {
        if (sessionService != null) {
            boolean result = sessionService.updateAnswer(request);
            if (result) {
                log.info("保存会话结果: sessionId={}, answerLength={}", request.getId(), request.getAnswer().length());
            }
            return result;
        }
        return false;
    }

    public void setChatMemory(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    /**
     * 注入本次请求归属的用户（AgentController 构造 Agent 后立刻调用）。
     */
    public void setDefaultUserId(String defaultUserId) {
        this.defaultUserId = defaultUserId;
    }

    /**
     * 取归属用户：优先用注入的字段；字段为空才回退到 UserContext（仅请求线程有效）。
     */
    protected String resolveUserId() {
        if (defaultUserId != null && !defaultUserId.isEmpty()) {
            return defaultUserId;
        }
        return UserContext.getUserIdOrDefault(propertiesDefaultUserId());
    }

    /**
     * 兜底用户与各业务表 {@code user_id} 列的 DEFAULT 'default' 保持一致，
     * Agent 里读不到 Spring 配置，改配置时需同时改这里与建表默认值。
     */
    private String propertiesDefaultUserId() {
        return "default";
    }

    /**
     * 构造带归属用户的"保存提问"请求。
     */
    protected SaveQuestionRequest.SaveQuestionRequestBuilder saveQuestionBuilder() {
        return SaveQuestionRequest.builder().userId(resolveUserId());
    }

    public void setSessionService(AiSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public void setTaskManager(AgentTaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public Long getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentConversationId() {
        return currentConversationId;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setEnableRecommendations(boolean enableRecommendations) {
        this.enableRecommendations = enableRecommendations;
    }

    public boolean isEnableRecommendations() {
        return enableRecommendations;
    }
}
