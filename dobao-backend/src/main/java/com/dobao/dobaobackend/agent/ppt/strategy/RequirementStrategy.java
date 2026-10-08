package com.dobao.dobaobackend.agent.ppt.strategy;

import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import com.dobao.dobaobackend.prompts.PptBuilderPrompts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求澄清策略
 */
@Slf4j
public class RequirementStrategy implements PptStateStrategy {

    private static final PptInstStatus TARGET_STATUS = PptInstStatus.SEARCH;

    @Override
    public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                        StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        sink.tryEmitNext(context.createThinkingResponse("正在分析您的需求...\n"));

        List<Message> messages = new ArrayList<>();
        String prompt = PptBuilderPrompts.REQUIREMENT_PROMPT;

        messages.add(new SystemMessage(prompt));

        context.loadChatHistory(inst.getConversationId(), messages, true, true);

        messages.add(new UserMessage("<question>" + query + "</question>"));

        if (context.getChatMemory() != null) {
            context.getChatMemory().add(inst.getConversationId(), new UserMessage(query)); // 用户问题写入记忆，供后续状态复用
        }

        StringBuilder responseBuffer = new StringBuilder();

        String conversationId = inst.getConversationId();

        Disposable disposable = context.getChatClient().prompt()
                .messages(messages)
                .stream()
                .content()
                .doOnNext(chunk -> {
                    responseBuffer.append(chunk);
                    sink.tryEmitNext(context.createThinkingResponse(chunk));
                })
                .doOnComplete(() -> {
                    log.info("需求分析完成: {}", responseBuffer);
                    String response = responseBuffer.toString();

                    if (context.shouldContinueToNextStep(response)) {
                        // 信息完整 → 进入信息收集
                        context.getPptInstService().updateRequirement(inst.getId(), response, TARGET_STATUS);
                        sink.tryEmitNext(context.createThinkingResponse("\n✅ 需求已确认，开始收集相关信息\n"));
                        context.continueStateMachine(inst, sink, query, thinkingBuffer);
                    } else {
                        // 信息不足 → 交 FAILED 策略统一输出
                        context.getPptInstService().updateRequirement(inst.getId(), response, PptInstStatus.REQUIREMENT);
                        context.getPptInstService().updateError(inst.getId(), "需要补充信息：\n" + response, PptInstStatus.REQUIREMENT);

                        if (context.getChatMemory() != null) {
                            context.getChatMemory().add(inst.getConversationId(), new AssistantMessage(response));
                        }
                        PptStateStrategyFactory.getInstance().executeFailedState(inst, sink, query, thinkingBuffer, context);
                    }
                })
                .doOnError(err -> {
                    log.error("需求分析异常", err);
                    // 失败不回退状态，只记录错误信息
                    context.getPptInstService().updateError(inst.getId(),
                            "需求分析失败: " + err.getMessage(), PptInstStatus.REQUIREMENT);
                    PptStateStrategyFactory.getInstance().executeFailedState(inst, sink, query, thinkingBuffer, context);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        // 交给任务管理器，便于取消时中断
        context.setDisposable(conversationId, disposable);
    }

    @Override
    public PptInstStatus getTargetStatus() {
        return TARGET_STATUS;
    }
}
