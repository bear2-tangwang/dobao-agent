package com.dobao.dobaobackend.agent.ppt.strategy;


import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import com.dobao.dobaobackend.prompts.PptBuilderPrompts;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;

/**
 * PPT 状态策略工厂：按状态分发到对应策略
 */
@Slf4j
public class PptStateStrategyFactory {

    private static final Map<PptInstStatus, PptStateStrategy> STRATEGY_MAP = new HashMap<>();

    private PptStateStrategyFactory() {
    }

    static {
        STRATEGY_MAP.put(PptInstStatus.INIT, new RequirementStrategy());
        STRATEGY_MAP.put(PptInstStatus.REQUIREMENT, new RequirementStrategy());
        STRATEGY_MAP.put(PptInstStatus.SEARCH, new SearchStrategy());
        STRATEGY_MAP.put(PptInstStatus.TEMPLATE, new TemplateStrategy());
        STRATEGY_MAP.put(PptInstStatus.OUTLINE, new OutlineStrategy());
        STRATEGY_MAP.put(PptInstStatus.SCHEMA, new SchemaStrategy());
        STRATEGY_MAP.put(PptInstStatus.RENDER, new RenderStrategy());
        STRATEGY_MAP.put(PptInstStatus.SUCCESS, new SuccessStrategy());
        STRATEGY_MAP.put(PptInstStatus.FAILED, new FailedStrategy());
    }

    public static PptStateStrategyFactory getInstance() {
        return SingletonHolder.INSTANCE;
    }

    public PptStateStrategy getStrategy(PptInstStatus status) {
        PptStateStrategy strategy = STRATEGY_MAP.get(status);
        if (strategy == null) {
            log.warn("未找到状态对应的策略: {}", status);
            return new DefaultStrategy();
        }
        return strategy;
    }

    public void executeNextState(AiPptInst inst, Sinks.Many<String> sink, String query,
                                 StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        try {
            // 前序步骤可能已改过状态，重新读一次库里的实例
            AiPptInst latestInst = context.getPptInstService().getById(inst.getId());
            if (latestInst != null) {
                inst = latestInst;
            }

            // 有错误信息说明是断点重连，清空后允许继续执行
            if (latestInst.getErrorMsg() != null && !latestInst.getErrorMsg().isEmpty()
                    && latestInst.getStatusEnum() != PptInstStatus.SUCCESS) {
                log.info("检测到断点重连: status={}, errorMsg={}",
                        latestInst.getStatusEnum(), latestInst.getErrorMsg());
                context.getPptInstService().updateError(latestInst.getId(), "", latestInst.getStatusEnum());
            }

            PptInstStatus status = inst.getStatusEnum();
            log.info("状态机执行: status={}", status);

            PptStateStrategy strategy = getStrategy(status);
            strategy.execute(inst, sink, query, thinkingBuffer, context);
        } catch (Exception e) {
            log.error("继续状态机执行失败", e);
            sink.tryEmitError(e);
        }
    }

    /**
     * 统一走 FAILED 策略，避免各策略各自 new 一个 FailedStrategy
     */
    public void executeFailedState(AiPptInst inst, Sinks.Many<String> sink, String query,
                                  StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        PptStateStrategy failedStrategy = getStrategy(PptInstStatus.FAILED);
        failedStrategy.execute(inst, sink, query, thinkingBuffer, context);
    }

    /**
     * 修改流程专用：直接用修改提示词执行 SchemaStrategy，不经过状态机流转
     */
    public void executeSchemaStrategy(AiPptInst inst, Sinks.Many<String> sink, String query,
                                      StringBuilder thinkingBuffer, PptStateStrategyContext context) {
        SchemaStrategy schemaStrategy = new SchemaStrategy();
        String modifyPrompt = PptBuilderPrompts.getSchemaModifyPrompt(query, inst.getPptSchema());
        schemaStrategy.executeWithModifyPrompt(inst, sink, query, thinkingBuffer, context, modifyPrompt);
    }

    private static class SingletonHolder {
        private static final PptStateStrategyFactory INSTANCE = new PptStateStrategyFactory();
    }

    /**
     * 未知状态的兜底策略
     */
    private static class DefaultStrategy implements PptStateStrategy {
        @Override
        public void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                            StringBuilder thinkingBuffer, PptStateStrategyContext context) {
            log.warn("未知状态: {}", inst.getStatusEnum());
            sink.tryEmitNext(context.createThinkingResponse("❌ 状态异常，终止执行\n"));
            sink.tryEmitComplete();
        }

        @Override
        public PptInstStatus getTargetStatus() {
            return PptInstStatus.FAILED;
        }
    }
}
