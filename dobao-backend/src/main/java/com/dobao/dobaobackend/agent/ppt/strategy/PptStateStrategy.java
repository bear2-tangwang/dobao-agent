package com.dobao.dobaobackend.agent.ppt.strategy;


import com.dobao.dobaobackend.entity.record.pptx.AiPptInst;
import com.dobao.dobaobackend.entity.record.pptx.PptInstStatus;
import reactor.core.publisher.Sinks;

/**
 * PPT 状态策略：每种 {@link PptInstStatus} 对应一个处理策略
 */
public interface PptStateStrategy {

    /**
     * 执行该状态的处理逻辑
     */
    void execute(AiPptInst inst, Sinks.Many<String> sink, String query,
                 StringBuilder thinkingBuffer, PptStateStrategyContext context);

    /**
     * 执行成功后应流转到的状态
     */
    PptInstStatus getTargetStatus();
}
