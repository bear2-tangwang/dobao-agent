package com.dobao.dobaobackend.controller;

import com.dobao.dobaobackend.agent.chat.ChatReactAgent;
import com.dobao.dobaobackend.agent.deeppresearch.PlanExecuteAgent;
import com.dobao.dobaobackend.agent.ppt.PPTBuilderAgent;
import com.dobao.dobaobackend.auth.LoginRequired;
import com.dobao.dobaobackend.auth.UserContext;
import com.dobao.dobaobackend.config.GithubOAuthProperties;
import com.dobao.dobaobackend.service.AgentTaskManager;
import com.dobao.dobaobackend.service.AiSessionService;
import com.dobao.dobaobackend.tool.FileContentService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;

/**
 * Agent 对话控制器
 * 提供统一对话（文件问答 + 联网搜索）的流式接口、停止生成接口，
 * 并在应用启动时初始化联网搜索（Tavily MCP）工具。
 */
@RestController
@RequestMapping("/agent")
@LoginRequired
@Slf4j
public class AgentController implements InitializingBean {

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private AiSessionService sessionService;

    @Autowired
    private AgentTaskManager taskManager;

    @Autowired
    private FileContentService fileContentService;

    @Autowired
    private GithubOAuthProperties authProperties;

    /**
     * 取当前登录用户ID（未登录时回退配置的兜底用户）
     */
    private String currentUserId() {
        return UserContext.getUserIdOrDefault(authProperties.getDefaultUserId());
    }

    /**
     * 把归属用户写进 Reactor Context。
     *
     * <p>Agent 的 {@code @Tool} 方法（如文件内容检索）由 Spring AI 在 Reactor 链内部调用，
     * 那里没有请求线程的 ThreadLocal，也不该让 LLM 自己传用户身份。
     * 工具侧用 {@code Mono.deferContextual} 读 {@link UserContext#REQUEST_USER_KEY}。
     */
    private Flux<String> withRequestUser(Flux<String> stream, String userId) {
        return stream.contextWrite(context -> context.put(UserContext.REQUEST_USER_KEY, userId));
    }

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    @Value("${tavily.mcp-url}")
    private String tavilyMcpUrl;

    private ToolCallback[] webSearchToolCallbacks;

    /**
     * 对话/文件问答（SSE 流式返回）
     *
     * @param query          用户问题
     * @param conversationId 会话ID
     * @param fileId         关联的文件ID（可选）
     * @return 流式输出内容
     */
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "统一对话", description = "统一处理文件问答与联网搜索的流式对话接口")
    public Flux<String> chatStream(@RequestParam(required = true) String query,
                                   @RequestParam(required = true) String conversationId,
                                   @RequestParam(required = false) String fileId) {
        log.info("收到统一对话请求: query={}, conversationId={}, fileId={}", query, conversationId, fileId);

        if (query == null || query.trim().isEmpty()) {
            log.warn("用户问题为空");
            return Flux.error(new IllegalArgumentException("问题内容不能为空"));
        }

        try {
            // 必须在请求线程上把归属用户取出来：一旦返回 Flux，后续逻辑跑在 Reactor 线程，
            // UserContext(ThreadLocal) 在那边是 null
            String userId = currentUserId();
            ChatReactAgent agent = initUnifiedAgent(userId);
            ChatMemory persistentMemory = agent.createPersistentChatMemory(conversationId, 30);
            agent.setChatMemory(persistentMemory);
            // Agent 的 @Tool 方法（FileContentService）跑在链内部，读不到请求线程的 ThreadLocal，
            // 身份只能从这里取（见 UserContext#REQUEST_USER_KEY）
            return withRequestUser(agent.stream(conversationId, query, fileId), userId);
        } catch (Exception e) {
            log.error("处理统一对话请求失败", e);
            return Flux.error(e);
        }
    }
    @GetMapping(value = "/pptx/stream", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "PPT 生成", description = "接收用户需求并返回流式响应，基于模板驱动生成PPT")
    public Flux<String> pptxStream(@RequestParam(required = true) String query,
                                   @RequestParam(required = true) String conversationId) {
        log.info("收到PPT Builder请求: query={}, conversationId={}", query, conversationId);

        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        try {
            String userId = currentUserId();
            PPTBuilderAgent pptBuilderAgent = initPPTBuilderAgent(userId);
            ChatMemory persistentMemory = pptBuilderAgent.createPersistentChatMemory(conversationId, 30);
            pptBuilderAgent.setChatMemory(persistentMemory);
            return withRequestUser(pptBuilderAgent.execute(conversationId, query), userId);
        } catch (Exception e) {
            log.error("处理PPT Builder请求时发生错误: ", e);
            return Flux.error(e);
        }
    }

    @GetMapping(value = "/deep/stream", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "深度研究", description = "接收用户查询并返回流式响应，使用计划-执行模式进行深度研究")
    public Flux<String> deepStream(@RequestParam(required = true) String query,
                                   @RequestParam(required = true) String conversationId) {
        log.info("收到深度研究请求: query={}, conversationId={}", query, conversationId);

        if (query == null || query.trim().isEmpty()) {
            log.warn("查询参数为空或无效");
            return Flux.error(new IllegalArgumentException("查询参数不能为空"));
        }

        try {
            String userId = currentUserId();
            PlanExecuteAgent planExecuteAgent = initPlanExecuteAgent(userId);
            ChatMemory persistentMemory = planExecuteAgent.createPersistentChatMemory(conversationId, 30);
            planExecuteAgent.setChatMemory(persistentMemory);
            return withRequestUser(planExecuteAgent.stream(conversationId, query), userId);
        } catch (Exception e) {
            log.error("处理深度研究请求时发生错误: ", e);
            return Flux.error(e);
        }
    }

    /**
     * 停止正在执行的 Agent 任务
     *
     * @param conversationId 会话ID
     * @return 停止结果
     */
    @GetMapping("/stop")
    @Operation(summary = "停止生成", description = "停止当前会话正在执行的Agent任务")
    public Map<String, Object> stopAgent(@RequestParam String conversationId) {
        log.info("收到停止生成请求: conversationId={}", conversationId);

        boolean success = taskManager.stopTask(conversationId);

        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("success", true);
            result.put("message", "已停止");
            result.put("type", "text");
        } else {
            result.put("success", false);
            result.put("message", "没有正在执行的任务或任务已停止");
        }
        return result;
    }

    /**
     * 初始化 PlanExecute Agent
     *
     * @param userId 本次请求归属的用户，显式注入（SSE 的 Reactor 线程读不到 ThreadLocal）
     */
    private PlanExecuteAgent initPlanExecuteAgent(String userId) {
        log.info("初始化 PlanExecute Agent...");

        PlanExecuteAgent agent = PlanExecuteAgent.builder()
                .chatModel(chatModel)
                .tools(webSearchToolCallbacks)
                .sessionService(sessionService)
                .taskManager(taskManager)
                .maxRounds(3)
                .build();
        agent.setDefaultUserId(userId);
        return agent;
    }

    /**
     * 初始化PPT Builder Agent
     */
    private PPTBuilderAgent initPPTBuilderAgent(String userId) {
        log.info("初始化PPT Builder Agent...");

        PPTBuilderAgent agent = new PPTBuilderAgent(
                chatModel,
                Arrays.asList(webSearchToolCallbacks),
                sessionService,
                taskManager);
        agent.setDefaultUserId(userId);
        return agent;
    }

    /**
     * 初始化统一对话Agent，并注入联网搜索与文件检索工具
     */
    private ChatReactAgent initUnifiedAgent(String userId) {
        log.info("正在初始化统一对话Agent...");

        List<ToolCallback> allTools = new ArrayList<>();
        if (webSearchToolCallbacks != null) {
            allTools.addAll(List.of(webSearchToolCallbacks));
        }
        allTools.addAll(List.of(ToolCallbacks.from(fileContentService)));

        ChatReactAgent agent = ChatReactAgent.builder()
                .name("chat react")
                .chatModel(chatModel)
                .tools(allTools)
                .sessionService(sessionService)
                .taskManager(taskManager)
                .maxRounds(5)
                .build();
        agent.setDefaultUserId(userId);
        return agent;
    }

    /**
     * 初始化联网搜索工具回调（通过 Tavily MCP 服务）
     */
    private void initWebSearchToolCallbacks() {
        log.info("正在初始化联网搜索工具回调...");

        String authorizationHeader = "Bearer " + tavilyApiKey;

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .header("Authorization", authorizationHeader);

        HttpClientStreamableHttpTransport tavTransport = HttpClientStreamableHttpTransport.builder(tavilyMcpUrl)
                .requestBuilder(requestBuilder).build();
        McpSyncClient tavilyMcp = McpClient.sync(tavTransport)
                .requestTimeout(Duration.ofSeconds(120))
                .build();
        tavilyMcp.initialize();

        List<McpSyncClient> mcpClients = List.of(tavilyMcp);
        SyncMcpToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder().mcpClients(mcpClients).build();

        webSearchToolCallbacks = provider.getToolCallbacks();
        log.info("联网搜索工具回调初始化完成，工具数量: {}", webSearchToolCallbacks.length);
    }

    /**
     * Bean 属性注入完成后，统一初始化所有工具
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        log.info("正在初始化所有工具...");
        initWebSearchToolCallbacks();
        log.info("所有工具初始化完成");
    }
}
