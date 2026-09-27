package com.dobao.dobaobackend.agent.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dobao.dobaobackend.agent.BaseAgent;
import com.dobao.dobaobackend.entity.AiSession;
import com.dobao.dobaobackend.entity.record.AgentState;
import com.dobao.dobaobackend.entity.record.RoundMode;
import com.dobao.dobaobackend.entity.record.RoundState;
import com.dobao.dobaobackend.entity.record.SearchResult;
import com.dobao.dobaobackend.entity.vo.SaveQuestionRequest;
import com.dobao.dobaobackend.entity.vo.UpdateAnswerRequest;
import com.dobao.dobaobackend.prompts.ReactAgentPrompts;
import com.dobao.dobaobackend.service.AgentTaskManager;
import com.dobao.dobaobackend.service.AiSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一 React 智能体
 * 在同一个 Agent 中同时支持文件问答与联网搜索
 */
@Slf4j
public class ChatReactAgent extends BaseAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatClient chatClient;
    private final List<ToolCallback> tools;
    private final String systemPrompt;
    private int maxRounds;
    private String currentFileId;
    private AgentState agentState;

    public ChatReactAgent(String name, ChatModel chatModel, List<ToolCallback> tools,
                          String systemPrompt, int maxRounds, ChatMemory chatMemory,
                          AiSessionService sessionService, AgentTaskManager taskManager) {
        super(name, chatModel, "chat");
        this.tools = tools;
        this.systemPrompt = systemPrompt;
        this.maxRounds = maxRounds;
        this.chatMemory = chatMemory;
        this.sessionService = sessionService;
        this.taskManager = taskManager;
        this.usedTools = new HashSet<>();

        initChatClient();

        if (this.chatClient == null) {
            throw new IllegalStateException("ChatClient 初始化失败");
        }
    }

    /**
     * 初始化 ChatClient，关闭框架内置的工具自动执行（由本类手动执行工具调用）
     */
    private void initChatClient() {
        try {
            ToolCallingChatOptions toolOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(tools)
                    .internalToolExecutionEnabled(false)
                    .build();

            ChatClient.Builder builder = ChatClient.builder(chatModel);
            this.chatClient = builder.defaultOptions(toolOptions).defaultToolCallbacks(tools).build();
        } catch (Exception e) {
            throw new RuntimeException("ChatClient 初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Flux<String> execute(String conversationId, String question) {
        return streamInternal(conversationId, question);
    }

    public Flux<String> stream(String conversationId, String question) {
        return streamInternal(conversationId, question);
    }

    /**
     * 流式对话入口（带文件ID）
     *
     * @param conversationId 会话ID
     * @param question       用户问题
     * @param fileId         关联文件ID（可为空）
     */
    public Flux<String> stream(String conversationId, String question, String fileId) {
        this.currentFileId = fileId;
        return streamInternal(conversationId, question);
    }

    /**
     * 流式对话核心逻辑：组装提示词与历史消息、保存提问、启动首轮推理
     */
    private Flux<String> streamInternal(String conversationId, String question) {
        List<Message> messages = Collections.synchronizedList(new ArrayList<>());
        boolean useMemory = conversationId != null && chatMemory != null;

        // 同一会话不允许并发执行
        Flux<String> checkResult = checkRunningTask(conversationId);
        if (checkResult != null) {
            return checkResult;
        }

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 注册任务管理器
        AgentTaskManager.TaskInfo taskInfo = registerTask(conversationId, sink);
        if (taskInfo == null && conversationId != null && taskManager != null) {
            return Flux.error(new IllegalStateException("该会话正在执行中，请稍后再试"));
        }

        //初始化定时器和清空已使用工具
        initTimers();
        clearUsedTools();

        // 统一系统提示词 + 业务自定义提示词
        messages.add(new SystemMessage(ReactAgentPrompts.getUnifiedPrompt()));
        if (StringUtils.isNotBlank(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }

        loadChatHistory(conversationId, messages, true, true);

        messages.add(new UserMessage("<question>" + question + "</question>"));
        if (StringUtils.isNotBlank(currentFileId)) {
            messages.add(new UserMessage("<fileid>" + currentFileId + "</fileid>"));
        }
        currentQuestion = question;

        // 先落库保存用户问题，答案在流结束后回填
        if (sessionService != null) {
            AiSession savedSession = sessionService.saveQuestion(
                    SaveQuestionRequest.builder()
                            .sessionId(conversationId)
                            .question(question)
                            .fileid(currentFileId)
                            .agentType("chat")
                            .build()
            );
            currentSessionId = savedSession.getId();
        }

        AtomicLong roundCounter = new AtomicLong(0); // 记录当前轮次
        AtomicBoolean hasSentFinalResult = new AtomicBoolean(false); // false : 未发送最终总结结果 ，true : 已发送最终总结结果
        StringBuilder finalAnswerBuffer = new StringBuilder(); // 累积最终答案
        StringBuilder thinkingBuffer = new StringBuilder(); // 累积思考过程

        agentState = new AgentState(); // 保存联网搜索引用来源

        scheduleRound(messages, sink, roundCounter, hasSentFinalResult, finalAnswerBuffer,
                useMemory, conversationId, agentState, thinkingBuffer);

        // 边流式下发边累积文本，用于结束后统一入库
        return sink.asFlux()
                .doOnNext(chunk -> {
                    try {
                        JSONObject json = JSON.parseObject(chunk);
                        String type = json.getString("type");
                        if ("text".equals(type)) {
                            finalAnswerBuffer.append(json.getString("content"));
                        } else if ("thinking".equals(type)) {
                            thinkingBuffer.append(json.getString("content"));
                        }
                    } catch (Exception e) {
                        finalAnswerBuffer.append(chunk);
                    }
                })
                .doOnCancel(() -> {
                    // 流被取消时，标记为已发送最终总结结果
                    hasSentFinalResult.set(true);
                    if (taskManager != null) {
                        taskManager.stopTask(conversationId);
                    }
                })
                .doFinally(signalType -> {
                    // 流结束时，统一入库会话结果
                    saveSessionResult(conversationId, finalAnswerBuffer, thinkingBuffer);
                    if (taskManager != null) {
                        taskManager.stopTask(conversationId);
                    }
                });
    }

    /**
     * 将本轮问答结果（答案、思考过程、工具、引用、推荐问题、耗时）写入数据库
     */
    private void saveSessionResult(String conversationId, StringBuilder finalAnswerBuffer, StringBuilder thinkingBuffer) {
        if (sessionService != null && currentSessionId != null && finalAnswerBuffer.length() > 0) {
            long totalResponseTime = getTotalResponseTime();
            String toolsStr = getUsedToolsString();
            String referenceJson = "";
            if (agentState != null && !agentState.searchResults.isEmpty()) {
                referenceJson = createReferenceResponse(JSON.toJSONString(agentState.searchResults));
            }
            UpdateAnswerRequest request = UpdateAnswerRequest.builder()
                    .id(currentSessionId)
                    .answer(finalAnswerBuffer.toString())
                    .thinking(thinkingBuffer.toString())
                    .tools(toolsStr)
                    .reference(referenceJson)
                    .recommend(currentRecommendations)
                    .firstResponseTime(firstResponseTime)
                    .totalResponseTime(totalResponseTime)
                    .build();
            sessionService.updateAnswer(request);
            log.info("会话结果已保存: conversationId={}", conversationId);
        }
    }

    /**
     * 发起一轮模型流式推理，并挂载完成/异常回调
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<String> sink, AtomicLong roundCounter,
                               AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer,
                               boolean useMemory, String conversationId, AgentState agentState,
                               StringBuilder thinkingBuffer) {
        roundCounter.incrementAndGet();
        RoundState state = new RoundState();

        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, sink, state))  // 处理每一段chunk，根据是否有工具调用判断是否需要累积
                .doOnComplete(() -> finishRound(messages, sink, state, roundCounter,  // 完成本轮问答 React -> think
                        hasSentFinalResult, finalAnswerBuffer, useMemory, conversationId, agentState, thinkingBuffer))
                .doOnError(err -> {
                    if (!hasSentFinalResult.get()) {
                        hasSentFinalResult.set(true);
                        sink.tryEmitError(err);
                    }
                })
                .subscribe();

        if (conversationId != null && taskManager != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

    /**
     * 处理单个流式分片：有工具调用则累积工具调用，否则作为正文下发
     */
    private void processChunk(ChatResponse chunk, Sinks.Many<String> sink, RoundState state) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }

        Generation gen = chunk.getResult();
        String text = gen.getOutput().getText();
        List<AssistantMessage.ToolCall> tc = gen.getOutput().getToolCalls();

        if (tc != null && !tc.isEmpty()) {
            state.mode = RoundMode.TOOL_CALL;
            for (AssistantMessage.ToolCall incoming : tc) {
                mergeToolCall(state, incoming);
            }
            return;
        }

        if (text != null) {
            sink.tryEmitNext(createTextResponse(text));
            state.textBuffer.append(text);
        }
    }

    /**
     * 合并同一工具调用的分片参数（流式返回时参数会被拆分多次下发）
     */
    private void mergeToolCall(RoundState state, AssistantMessage.ToolCall incoming) {
        for (int i = 0; i < state.toolCalls.size(); i++) {
            AssistantMessage.ToolCall existing = state.toolCalls.get(i);
            if (existing.id().equals(incoming.id())) {
                String mergedArgs = Objects.toString(existing.arguments(), "") + Objects.toString(incoming.arguments(), "");
                state.toolCalls.set(i,
                        new AssistantMessage.ToolCall(existing.id(), "function", existing.name(), mergedArgs)
                );
                return;
            }
        }
        state.toolCalls.add(incoming);
    }

    /**
     * 单轮结束处理：无工具调用则输出最终答案；有工具调用则执行工具并进入下一轮
     */
    private void finishRound(List<Message> messages, Sinks.Many<String> sink, RoundState state,
                             AtomicLong roundCounter, AtomicBoolean hasSentFinalResult, StringBuilder finalAnswerBuffer,
                             boolean useMemory, String conversationId, AgentState agentState, StringBuilder thinkingBuffer) {
        // 无工具调用，直接输出最终答案
        if (state.getMode() != RoundMode.TOOL_CALL) {
            String finalText = state.textBuffer.toString();

            // 附加联网搜索引用来源
            if (!agentState.searchResults.isEmpty()) {
                String reference = JSON.toJSONString(agentState.searchResults);
                sink.tryEmitNext(createReferenceResponse(reference));
            }

            // 生成推荐追问问题
            if (enableRecommendations) {
                // TODO 响应很慢， 后续优化
                String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                if (recommendations != null) {
                    currentRecommendations = recommendations;
                    sink.tryEmitNext(createRecommendResponse(recommendations));
                }
            }

            // 发送最终总结结果
            sink.tryEmitComplete();
            hasSentFinalResult.set(true);
            return;
        }

        AssistantMessage assistantMsg = AssistantMessage.builder().toolCalls(state.toolCalls).build();
        messages.add(assistantMsg);

        // 达到最大轮次，强制输出最终答案
        if (maxRounds > 0 && roundCounter.get() >= maxRounds) {
            forceFinalStream(messages, sink, hasSentFinalResult, state, conversationId, useMemory, agentState, thinkingBuffer);
            return;
        }

        executeToolCalls(sink, state.toolCalls, messages, hasSentFinalResult, state, agentState, () -> {
            if (!hasSentFinalResult.get()) {
                scheduleRound(messages, sink, roundCounter,
                        hasSentFinalResult, finalAnswerBuffer,
                        useMemory, conversationId, agentState, thinkingBuffer); // 执行下一轮 React -> think
            }
        });
    }

    /**
     * 达到最大推理轮次后，强制模型基于已有上下文直接输出最终答案
     */
    private void forceFinalStream(List<Message> messages, Sinks.Many<String> sink, AtomicBoolean hasSentFinalResult, RoundState state,
                                  String conversationId, boolean useMemory, AgentState agentState, StringBuilder thinkingBuffer) {
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(ReactAgentPrompts.getUnifiedPrompt()));
        if (StringUtils.isNotBlank(systemPrompt)) {
            newMessages.add(new SystemMessage(systemPrompt));
        }

        for (Message msg : messages) {
            if (!(msg instanceof SystemMessage)) {
                newMessages.add(msg);
            }
        }

        newMessages.add(new UserMessage("""
                你已经达到最大推理轮次限制。
                请直接基于当前上下文给出最终答案。
                不要再调用任何工具。
                如果信息不完整，请总结并明确说明。
                """));

        messages.clear();
        messages.addAll(newMessages);

        StringBuilder finalTextBuffer = new StringBuilder();

        // 强制输出最终答案：不再解析工具调用，收到文本直接下发
        Disposable disposable = chatClient.prompt()
                .messages(messages)
                .stream()
                .chatResponse()
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                        return;
                    }
                    String text = chunk.getResult().getOutput().getText();
                    if (text != null && !hasSentFinalResult.get()) {
                        sink.tryEmitNext(createTextResponse(text));
                        finalTextBuffer.append(text);
                    }
                })
                .doOnComplete(() -> {
                    String finalText = finalTextBuffer.toString();

                    if (!agentState.searchResults.isEmpty()) {
                        String reference = JSON.toJSONString(agentState.searchResults);
                        sink.tryEmitNext(createReferenceResponse(reference));
                    }

                    if (enableRecommendations) {
                        String recommendations = generateRecommendations(conversationId, currentQuestion, finalText);
                        if (recommendations != null) {
                            currentRecommendations = recommendations;
                            sink.tryEmitNext(createRecommendResponse(recommendations));
                        }
                    }

                    hasSentFinalResult.set(true);
                    sink.tryEmitComplete();
                })
                .doOnError(err -> {
                    hasSentFinalResult.set(true);
                    sink.tryEmitError(err);
                })
                .subscribe();

        if (conversationId != null && taskManager != null) {
            taskManager.setDisposable(conversationId, disposable);
        }
    }

    /**
     * 顺序执行本轮所有工具调用，并把工具结果写回消息上下文
     */
    private void executeToolCalls(Sinks.Many<String> sink, List<AssistantMessage.ToolCall> toolCalls, List<Message> messages,
                                  AtomicBoolean hasSentFinalResult, RoundState state, AgentState agentState, Runnable onComplete) {
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (hasSentFinalResult.get()) {
                break;
            }

            String toolName = tc.name();
            String argsJson = tc.arguments();

            ToolCallback callback = findTool(toolName);
            if (callback == null) {
                addErrorToolResponse(messages, tc, "未找到工具: " + toolName);
                continue;
            }

            // 文件检索工具：向前端推送思考提示
            if (toolName.contains("loadContent") && StringUtils.isNotBlank(argsJson)) {
                try {
                    JSONObject args = JSON.parseObject(argsJson);
                    String question = args.getString("question");
                    String think = StringUtils.isNotBlank(question)
                            ? "📄 正在检索文件内容...\n"
                            : "📄 正在检索文件内容...\n";
                    sink.tryEmitNext(createThinkingResponse(think));
                } catch (Exception ignore) {
                    // 忽略参数解析异常
                }
            }

            // 联网搜索工具：向前端推送思考提示
            if (toolName.contains("search") && StringUtils.isNotBlank(argsJson)) {
                try {
                    JSONObject args = JSON.parseObject(argsJson);
                    String query = args.getString("query");
                    String think = StringUtils.isNotBlank(query)
                            ? "🔍 正在搜索信息: " + query + "\n"
                            : "🔍 正在搜索相关信息\n";
                    sink.tryEmitNext(createThinkingResponse(think));
                } catch (Exception ignore) {
                    // 忽略参数解析异常
                }
            }

            try {
                Object result = callback.call(argsJson);
                ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                        tc.id(), toolName, result.toString());
                messages.add(ToolResponseMessage.builder()
                        .responses(List.of(tr))
                        .build());
                recordUsedTool(toolName);

                // 联网搜索结果解析为引用来源
                if (toolName.contains("tavily")) {
                    parseSearchResult(result.toString(), agentState);
                }
            } catch (Exception ex) {
                addErrorToolResponse(messages, tc, "工具执行失败: " + ex.getMessage());
            }
        }

        onComplete.run();
    }

    /**
     * 解析 Tavily 搜索结果，提取引用来源（url/标题/摘要）
     */
    private void parseSearchResult(String resultJson, AgentState state) {
        try {
            JsonNode root = MAPPER.readTree(resultJson);

            if (!root.isArray() || root.isEmpty()) {
                return;
            }

            JsonNode first = root.get(0);
            JsonNode textNode = first.get("text");
            if (textNode == null || textNode.isNull()) {
                return;
            }

            JsonNode textJson;
            if (textNode.isTextual()) {
                textJson = MAPPER.readTree(textNode.asText());
            } else {
                textJson = textNode;
            }

            JsonNode results = textJson.get("results");
            if (results == null || !results.isArray()) {
                return;
            }

            for (JsonNode item : results) {
                String url = getSafe(item, "url");
                String title = getSafe(item, "title");
                String content = getSafe(item, "content");
                if (url != null && !url.isBlank()) {
                    state.searchResults.add(new SearchResult(url, title, content));
                }
            }
        } catch (Exception e) {
            log.warn("解析 Tavily 搜索结果失败: {}", e.getMessage());
        }
    }

    /**
     * 安全读取JSON节点文本，不存在或为null时返回null
     */
    private String getSafe(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * 向消息上下文写入工具执行失败的响应
     */
    private void addErrorToolResponse(List<Message> messages, AssistantMessage.ToolCall toolCall, String errMsg) {
        ToolResponseMessage.ToolResponse tr = new ToolResponseMessage.ToolResponse(
                toolCall.id(),
                toolCall.name(),
                "{ \"error\": \"" + errMsg + "\" }"
        );
        messages.add(ToolResponseMessage.builder()
                .responses(List.of(tr))
                .build());
    }

    /**
     * 按名称查找已注册的工具回调
     */
    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void setMaxRounds(int maxRounds) {
        this.maxRounds = maxRounds;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * ChatReactAgent 构建器
     */
    public static class Builder {
        private String name;
        private ChatModel chatModel;
        private List<ToolCallback> tools;
        private String systemPrompt = "";
        private int maxRounds = 5;
        private ChatMemory chatMemory;
        private AiSessionService sessionService;
        private AgentTaskManager taskManager;

        public Builder chatMemory(ChatMemory chatMemory) {
            this.chatMemory = chatMemory;
            return this;
        }

        public Builder sessionService(AiSessionService sessionService) {
            this.sessionService = sessionService;
            return this;
        }

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public Builder tools(ToolCallback... tools) {
            this.tools = Arrays.asList(tools);
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public ChatReactAgent build() {
            if (chatModel == null) {
                throw new IllegalArgumentException("chatModel 不能为空");
            }
            return new ChatReactAgent(name, chatModel, tools, systemPrompt, maxRounds, chatMemory, sessionService, taskManager);
        }
    }
}
