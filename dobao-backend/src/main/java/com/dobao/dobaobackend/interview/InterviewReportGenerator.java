package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.KnowledgeGap;
import com.dobao.dobaobackend.interview.dto.KnowledgeTopic;
import com.dobao.dobaobackend.interview.dto.NormalizedTranscript;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReportDraft;
import com.dobao.dobaobackend.interview.dto.SentenceDTO;
import com.dobao.dobaobackend.interview.dto.TranscriptLine;
import com.dobao.dobaobackend.interview.llm.LlmJsonSupport;
import com.dobao.dobaobackend.prompts.InterviewPrompts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告生成（Reduce 阶段）。
 *
 * <p><b>问答清单不过模型</b>：它直接取 {@link QaListBuilder} 的格式化产物，
 * 保证报告里的问答与时间戳与文字稿逐字一致，不会被二次改写。
 *
 * <p><b>全流程只在这里调一次 LLM</b>（角色判定那次除外）：把整份问答清单交给模型，
 * 产出「知识点清单 + 待补充知识点」。原实现还额外承担了"合并各分块问答对"的职责，
 * 2026-10 重构后分块已不存在，本类只做这一件事。
 *
 * <p><b>防编造</b>（编码方案 §6 风险表的要求）分两层：
 * <ol>
 *   <li>prompt 强约束"只能依据输入内容"；</li>
 *   <li>生成后做<b>落地校验</b>：{@code relatedQaIds} 必须指向真实存在的问答条目；
 *       {@code topic}/{@code point} 必须在原文里有词面依据（整串命中，或 2-gram 覆盖率 ≥ 60%），
 *       否则整条剔除并记日志。这样"模型凭空造一个候选人没提过的技术点"会被拦下，
 *       而正常改写（同义表述）仍能保留。</li>
 * </ol>
 *
 * <p>待补充知识点<b>不做词面校验</b>：它描述的本就是原文里没答好的内容，
 * 用"是否出现在原文"来判定会把正确结果误删。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReportGenerator {

    /** 词面覆盖率阈值：低于它认为该要点在原文中没有依据 */
    private static final double GROUNDING_THRESHOLD = 0.6;

    private final LlmJsonSupport llmJsonSupport;
    private final ObjectMapper objectMapper;
    private final InterviewProperties properties;

    /**
     * 生成报告。
     *
     * @param record               面试记录（取 interviewId 与音频时长）
     * @param transcript           归一化文字稿（用于防编造校验与"完整对话"附录）
     * @param qaItems              问答清单（{@link QaListBuilder} 的格式化产物，逐字原文）
     * @param interviewerSpeakerId 判定出的面试官说话人编号（可为 null）
     */
    public InterviewReport generate(AiInterview record,
                                    NormalizedTranscript transcript,
                                    List<QaItem> qaItems,
                                    Integer interviewerSpeakerId) {
        List<QaItem> qaList = qaItems == null ? List.of() : qaItems;

        ReportDraft draft;
        if (qaList.isEmpty()) {
            // 没有问答条目时不调用模型：既省一次调用，也避免模型"为了凑内容"凭空编知识点
            log.warn("问答清单为空，跳过知识点生成: interviewId={}", record.getInterviewId());
            draft = new ReportDraft(List.of(), List.of());
        } else {
            // 序列化失败说明问答清单里有无法映射的字段，属于编码问题，直接抛出而不是让模型收到半截输入
            String qaJson;
            try {
                qaJson = objectMapper.writeValueAsString(qaList);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("序列化问答清单失败: " + e.getMessage(), e);
            }
            draft = llmJsonSupport.callForJson(
                    InterviewPrompts.REPORT_SYSTEM,
                    InterviewPrompts.reportUser(qaJson),
                    new ParameterizedTypeReference<>() {
                    },
                    buildKnowledgeOptions());
        }

        Set<String> validQaIds = new HashSet<>();
        qaList.forEach(p -> validQaIds.add(p.qaId()));

        List<KnowledgeTopic> topics = validateTopics(draft.safeTopics(), validQaIds, transcript);
        List<KnowledgeGap> gaps = draft.safeGaps();

        InterviewReport report = new InterviewReport(
                record.getInterviewId(),
                record.getAudioDurationMs() == null ? 0L : record.getAudioDurationMs(),
                LocalDateTime.now(),
                qaList,
                topics,
                gaps,
                buildTranscript(transcript, interviewerSpeakerId));

        log.info("报告生成完成: interviewId={}, 问答条目={}, 知识点主题={}, 待补充={}, 对话行数={}",
                record.getInterviewId(), qaList.size(), topics.size(), gaps.size(),
                report.transcript() == null ? 0 : report.transcript().size());
        return report;
    }

    /**
     * 组装知识点归纳这一次调用的按次选项。
     *
     * <p>与问答清单无关，但仍然是一次**信息抽取**（不是写作），所以沿用抽取档的低温 + 固定种子，
     * 保证"同一份问答清单跑出同一份知识点"（需求 AC-13）。温度与种子本身写死在
     * {@link LlmJsonSupport#EXTRACTION_TEMPERATURE} / {@link LlmJsonSupport#EXTRACTION_SEED}，
     * 这里只负责把 {@code interview.report.*} 里可配的部分（换模型、关思考）带进来。
     */
    private ChatOptions buildKnowledgeOptions() {
        InterviewProperties.Report report = properties.getReport();
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String key : report.getExtraBodyKeys()) {
            if (report.getExtraBody().containsKey(key)) {
                extra.put(key, report.getExtraBody().get(key));
            }
        }
        return LlmJsonSupport.extractionOptions(
                LlmJsonSupport.EXTRACTION_TEMPERATURE,
                report.getSeed(),
                report.getModel(),
                extra);
    }

    /**
     * 把整场转写稿转成报告附录的"完整对话"。
     *
     * <p><b>逐句原话、不改写、不摘要</b>：附录的作用是让用户核对问答清单有没有漏，
     * 一旦经过模型改写就失去核对价值。角色只是按判定结果贴标签
     * （{@code speakerId == interviewerSpeakerId} 即面试官）。
     */
    private List<TranscriptLine> buildTranscript(NormalizedTranscript transcript, Integer interviewerSpeakerId) {
        if (transcript == null || transcript.sentences() == null) {
            return List.of();
        }
        List<TranscriptLine> lines = new ArrayList<>(transcript.sentences().size());
        for (SentenceDTO s : transcript.sentences()) {
            if (s == null || !StringUtils.hasText(s.text())) {
                continue;
            }
            boolean isInterviewer = interviewerSpeakerId != null && interviewerSpeakerId.equals(s.speakerId());
            lines.add(new TranscriptLine(
                    isInterviewer ? TranscriptLine.ROLE_INTERVIEWER : TranscriptLine.ROLE_CANDIDATE,
                    s.beginMs() == null ? 0L : s.beginMs(),
                    s.text().trim()));
        }
        return lines;
    }

    /**
     * 知识点校验：剔除没有原文依据的要点与主题，并清理无效的 relatedQaIds。
     */
    private List<KnowledgeTopic> validateTopics(List<KnowledgeTopic> topics,
                                                Set<String> validQaIds,
                                                NormalizedTranscript transcript) {
        String corpus = transcript == null || transcript.plainText() == null
                ? "" : transcript.plainText();
        List<KnowledgeTopic> kept = new ArrayList<>();

        for (KnowledgeTopic topic : topics) {
            if (topic == null || !StringUtils.hasText(topic.topic())) {
                continue;
            }
            List<String> groundedPoints = new ArrayList<>();
            if (topic.points() != null) {
                for (String point : topic.points()) {
                    if (!StringUtils.hasText(point)) {
                        continue;
                    }
                    if (isGrounded(point, corpus)) {
                        groundedPoints.add(point);
                    } else {
                        log.warn("剔除无原文依据的知识点要点: topic={}, point={}", topic.topic(), point);
                    }
                }
            }

            List<String> related = new ArrayList<>();
            if (topic.relatedQaIds() != null) {
                for (String qaId : topic.relatedQaIds()) {
                    if (validQaIds.contains(qaId)) {
                        related.add(qaId);
                    } else {
                        log.warn("剔除不存在的问答条目引用: topic={}, qaId={}", topic.topic(), qaId);
                    }
                }
            }

            // 主题名本身也要有原文依据，且必须留下至少一个要点，否则整条丢弃
            if (groundedPoints.isEmpty() || !isGrounded(topic.topic(), corpus)) {
                log.warn("剔除无原文依据的知识点主题: topic={}", topic.topic());
                continue;
            }
            kept.add(new KnowledgeTopic(topic.topic(), groundedPoints, related));
        }
        return kept;
    }

    /**
     * 判断文本在原文中是否有词面依据：整串命中，或 2-gram 覆盖率达标（容忍改写）。
     */
    private boolean isGrounded(String text, String corpus) {
        if (!StringUtils.hasText(text) || !StringUtils.hasText(corpus)) {
            return false;
        }
        String t = text.trim();
        if (corpus.contains(t)) {
            return true;
        }
        if (t.length() < 2) {
            return false;
        }
        int hit = 0;
        int total = 0;
        for (int i = 0; i + 2 <= t.length(); i++) {
            total++;
            if (corpus.contains(t.substring(i, i + 2))) {
                hit++;
            }
        }
        return total > 0 && (double) hit / total >= GROUNDING_THRESHOLD;
    }
}
