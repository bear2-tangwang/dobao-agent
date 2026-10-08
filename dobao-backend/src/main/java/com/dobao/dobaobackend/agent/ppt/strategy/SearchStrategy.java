package com.dobao.dobaobackend.agent.ppt.strategy;


import com.dobao.dobaobackend.agent.deeppresearch.SimpleReactAgent;
import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import com.dobao.dobaobackend.prompts.PptBuilderPrompts;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 信息收集策略
 */
@Slf4j
public class SearchStrategy implements PptStateStrategy {
    private static final PptInstStatus TARGET_STATUS = PptInstStatus.TEMPLATE;

    @Override
    public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                        StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        sink.tryEmitNext(context.createThinkingResponse("正在收集相关信息...\n"));

        String requirement = inst.getRequirement();

        String searchPrompt = PptBuilderPrompts.getSearchInfoPrompt(requirement);

        // 搜索由 SimpleReactAgent 执行，工具来自策略上下文
        SimpleReactAgent agent = SimpleReactAgent.builder()
                .chatModel(context.getChatModel())
                .tools(context.getToolCallbacks())
                .build();

        StringBuilder searchResultBuffer = new StringBuilder();

        Disposable disposable = agent.stream(searchPrompt)
                .doOnNext(chunk -> {
                    searchResultBuffer.append(chunk);
                    sink.tryEmitNext(context.createThinkingResponse(chunk));
                })
                .doOnComplete(() -> {
                    log.info("信息收集完成，结果长度: {}", searchResultBuffer.length());
                    String searchResult = searchResultBuffer.toString();
                    searchResult = cleanSearchResult(searchResult);
                    context.getPptInstService().updateSearchInfo(inst.getId(), searchResult, TARGET_STATUS);
                    sink.tryEmitNext(context.createThinkingResponse("\n✅相关信息收集完成，开始选择模板\n"));
                    context.continueStateMachine(inst, sink, query, thinkingBuffer);
                })
                .doOnError(err -> {
                    log.error("信息收集异常", err);
                    // 失败不回退状态，只记录错误信息
                    context.getPptInstService().updateError(inst.getId(),
                            "信息收集失败: " + err.getMessage(), PptInstStatus.SEARCH);
                    PptStateStrategyFactory.getInstance().executeFailedState(inst, sink, query, thinkingBuffer, context);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // 交给任务管理器，便于取消时中断
        context.setDisposable(inst.getConversationId(), disposable);
    }

    @Override
    public PptInstStatus getTargetStatus() {
        return TARGET_STATUS;
    }

    /**
     * 清理搜索结果，过滤掉工具调用标记等非内容部分
     */
    private String cleanSearchResult(String result) {
        if (result == null || result.trim().isEmpty()) {
            return "";
        }

        // 清掉各家工具调用标记的残留
        String cleaned = result
                .replaceAll("<tool_calls>.*?</tool_calls>", "")
                .replaceAll("\\[Tool Call.*?\\]", "")
                .replaceAll("Tool call:.*?\\n", "")
                .replaceAll("\\[TOOL_CALL\\].*?\\[\\/TOOL_CALL\\]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        return cleaned;
    }
}
