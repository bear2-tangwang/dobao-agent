package com.dobao.dobaobackend.controller;

import com.dobao.dobaobackend.agent.chat.ChatReactAgent;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话控制器
 * 提供统一对话（文件问答 + 联网搜索）的流式接口、停止生成接口，
 * 并在应用启动时初始化联网搜索（Tavily MCP）工具。
 */
@RestController
@RequestMapping("/agent")
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
            ChatReactAgent agent = initUnifiedAgent();
            // 获取当前会话历史记忆 默认30条记忆
            ChatMemory persistentMemory = agent.createPersistentChatMemory(conversationId, 30);
            agent.setChatMemory(persistentMemory);
            return agent.stream(conversationId, query, fileId);
        } catch (Exception e) {
            log.error("处理统一对话请求失败", e);
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
     * 初始化统一对话Agent，并注入联网搜索与文件检索工具
     */
    private ChatReactAgent initUnifiedAgent() {
        log.info("正在初始化统一对话Agent...");

        List<ToolCallback> allTools = new ArrayList<>();
        if (webSearchToolCallbacks != null) {
            allTools.addAll(List.of(webSearchToolCallbacks));
        }
        allTools.addAll(List.of(ToolCallbacks.from(fileContentService)));

        return ChatReactAgent.builder()
                .name("chat react")
                .chatModel(chatModel)
                .tools(allTools)
                .sessionService(sessionService)
                .taskManager(taskManager)
                .maxRounds(5)
                .build();
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
