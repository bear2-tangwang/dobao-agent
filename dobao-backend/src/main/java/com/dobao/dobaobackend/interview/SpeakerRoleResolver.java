package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.dto.NormalizedTranscript;
import com.dobao.dobaobackend.interview.dto.RoleJudgment;
import com.dobao.dobaobackend.interview.dto.SentenceDTO;
import com.dobao.dobaobackend.interview.llm.LlmJsonSupport;
import com.dobao.dobaobackend.prompts.InterviewPrompts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 说话人角色判定：用 LLM 判断哪个 {@code speaker_id} 是面试官。
 *
 * <p>必须用 LLM：百炼的 {@code speaker_id} 是声学聚类编号，跨任务没有稳定含义；
 * 也不能只看时长 —— 面试官可能长篇介绍公司/团队/流程，时长会误导。
 *
 * <p>输入是采样后的片段（开头 + 中段 + 尾段），判定只需要少量代表性对话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeakerRoleResolver {

    /** 每个采样窗口的句子数 */
    private static final int SAMPLE_WINDOW = 25;

    private final LlmJsonSupport llmJsonSupport;

    /**
     * 判定面试官对应的说话人编号。
     *
     * @param transcript 归一化后的文字稿
     * @return 判定结果（含依据）
     * @throws IllegalStateException 文字稿为空、或模型返回了不存在的说话人编号
     */
    public RoleJudgment resolve(NormalizedTranscript transcript) {
        if (transcript == null || transcript.size() == 0) {
            throw new IllegalStateException("文字稿为空，无法判定说话人角色");
        }
        if (!transcript.hasExactlyTwoSpeakers()) {
            // 规格把 speaker_count 固定为 2，这里只告警不阻断：多说话人时仍可尝试判定
            log.warn("说话人数量不是 2（实际 {}），角色判定结果需人工复核", transcript.speakerIds());
        }

        RoleJudgment judgment = llmJsonSupport.callForJson(
                InterviewPrompts.ROLE_RESOLUTION_SYSTEM,
                InterviewPrompts.roleResolutionUser(sampleDialogue(transcript)),
                new ParameterizedTypeReference<>() {
                });

        Integer interviewerId = judgment.interviewerSpeakerId();
        if (interviewerId == null || !transcript.speakerIds().contains(interviewerId)) {
            // 模型偶尔会给出输入里不存在的编号，必须拦住，否则后续问答清单全错
            throw new IllegalStateException("角色判定返回了不存在的说话人编号: "
                    + interviewerId + "，实际编号=" + transcript.speakerIds());
        }

        log.info("角色判定完成: 面试官=Speaker{}, 依据={}", interviewerId, judgment.reason());
        return judgment;
    }

    /** 采样：开头 + 中段 + 尾段各 {@value #SAMPLE_WINDOW} 句；总量不足时全取。 */
    private String sampleDialogue(NormalizedTranscript transcript) {
        List<SentenceDTO> all = transcript.sentences();
        List<SentenceDTO> picked = new ArrayList<>();
        if (all.size() <= SAMPLE_WINDOW * 3) {
            picked.addAll(all);
        } else {
            picked.addAll(all.subList(0, SAMPLE_WINDOW));
            int mid = all.size() / 2 - SAMPLE_WINDOW / 2;
            picked.addAll(all.subList(mid, mid + SAMPLE_WINDOW));
            picked.addAll(all.subList(all.size() - SAMPLE_WINDOW, all.size()));
        }
        StringBuilder sb = new StringBuilder();
        for (SentenceDTO s : picked) {
            sb.append('[').append(s.seqNo()).append("] ").append(s.toLine()).append('\n');
        }
        return sb.toString();
    }
}
