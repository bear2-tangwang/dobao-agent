package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.interview.dto.ReportDraft;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告生成：把整份问答清单交给模型做<b>一次</b>调用，产出参考回答与面试总结。
 *
 * <p>问答清单直接取 {@link QaListBuilder} 的格式化产物（逐字原文），不让模型改写；
 * 模型输出只做两层清洗：参考回答的编号落地校验、总结的去重与限长。
 *
 * <p>参考回答只校验 {@code qaId} 是否有出处：它答的是"应该怎么答"，本就不该出现在候选人原话里，
 * 用词面匹配判定只会把正确结果全部误删。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewReportGenerator {

    /** 涉及知识点最多保留几条：prompt 要求 4~8 个，这里只是防"模型写成长清单"的兜底 */
    private static final int MAX_COVERED_TOPICS = 10;

    /** 待补充知识点最多保留几条（同上） */
    private static final int MAX_GAP_TOPICS = 8;

    /** 总结文字的长度上限（prompt 要求约 100 字，这里只兜底防止模型写成长文） */
    private static final int MAX_SUMMARY_CHARS = 400;

    private final LlmJsonSupport llmJsonSupport;
    private final ObjectMapper objectMapper;
    private final InterviewProperties properties;

    /**
     * 生成报告。
     *
     * @param record  面试记录（取 interviewId 与音频时长）
     * @param qaItems 问答清单（{@link QaListBuilder} 的格式化产物，逐字原文）
     */
    public InterviewReport generate(AiInterview record, List<QaItem> qaItems) {
        List<QaItem> qaList = qaItems == null ? List.of() : qaItems;

        ReportDraft draft;
        if (qaList.isEmpty()) {
            // 没有问答条目时不调模型：既省一次调用，也避免模型"为了凑内容"凭空编参考回答
            log.warn("问答清单为空，跳过参考回答与总结生成: interviewId={}", record.getInterviewId());
            draft = new ReportDraft(List.of(), null);
        } else {
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
                    buildReportOptions());
        }

        // 只有真实存在的问答条目才能被参考回答引用
        Map<String, QaItem> byQaId = new LinkedHashMap<>();
        for (QaItem item : qaList) {
            if (item != null && StringUtils.hasText(item.qaId())) {
                byQaId.putIfAbsent(item.qaId(), item);
            }
        }
        List<ReferenceAnswer> referenceAnswers =
                validateReferenceAnswers(draft.safeReferenceAnswers(), byQaId);
        ReportSummary summary = normalizeSummary(draft.safeSummary());

        InterviewReport report = new InterviewReport(
                record.getInterviewId(),
                record.getAudioDurationMs() == null ? 0L : record.getAudioDurationMs(),
                LocalDateTime.now(),
                qaList,
                referenceAnswers,
                summary);

        log.info("报告生成完成: interviewId={}, 问答条目={}, 参考回答={}, 涉及知识点={}, 待补充={}, 总结字数={}",
                record.getInterviewId(), qaList.size(), referenceAnswers.size(),
                summary.safeCoveredTopics().size(), summary.safeGapTopics().size(),
                summary.summary() == null ? 0 : summary.summary().length());
        return report;
    }

    /**
     * 组装这次调用的按次选项。
     *
     * <p>这次调用既做抽取也做写作，但沿用抽取档的低温 + 固定种子，
     * 这样同一份问答清单能跑出同一份报告。温度与种子写死在
     * {@link LlmJsonSupport#EXTRACTION_TEMPERATURE} / {@link LlmJsonSupport#EXTRACTION_SEED}，
     * 这里只带上 {@code interview.report.*} 里可配的部分（换模型、关思考）。
     */
    private ChatOptions buildReportOptions() {
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
     * 参考回答校验：只保留有出处的条目，剔除空条目与重复编号。
     *
     * <p>问题措辞允许与面试官原话不同（模型本就被要求把原话收敛成清晰的问题），
     * 因此只校验编号，不校验词面。
     */
    private List<ReferenceAnswer> validateReferenceAnswers(List<ReferenceAnswer> candidates,
                                                           Map<String, QaItem> byQaId) {
        List<ReferenceAnswer> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReferenceAnswer candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            String qaId = candidate.qaId() == null ? null : candidate.qaId().trim();
            if (!StringUtils.hasText(qaId) || !byQaId.containsKey(qaId)) {
                log.warn("剔除无出处的参考回答: qaId={}, question={}", qaId, abbreviate(candidate.question()));
                continue;
            }
            if (!StringUtils.hasText(candidate.question()) || !StringUtils.hasText(candidate.answer())) {
                log.warn("剔除问题或参考答案为空的条目: qaId={}", qaId);
                continue;
            }
            // 去重必须在字段校验之后：否则一条空条目会"占掉"这个编号，
            // 后面同编号的正常条目反而被当成重复丢掉
            if (!seen.add(qaId)) {
                log.warn("剔除重复编号的参考回答: qaId={}", qaId);
                continue;
            }
            kept.add(new ReferenceAnswer(qaId, candidate.question().trim(), candidate.answer().trim()));
        }
        return kept;
    }

    /**
     * 面试总结清洗：去空白、去重、截断超限内容。
     *
     * <p>上限只是兜底 —— prompt 已要求 4~8 个知识点与约 100 字总结，
     * 但"总结不要过多"是硬要求，不能指望模型每次都听话。
     */
    private ReportSummary normalizeSummary(ReportSummary raw) {
        List<String> covered = normalizeTopics(raw.safeCoveredTopics(), MAX_COVERED_TOPICS, "涉及知识点");
        List<String> gaps = normalizeTopics(raw.safeGapTopics(), MAX_GAP_TOPICS, "待补充知识点");
        String summary = raw.summary() == null ? "" : raw.summary().replaceAll("\\s+", " ").trim();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            log.warn("总结文字超长，截断到 {} 字（原 {} 字）", MAX_SUMMARY_CHARS, summary.length());
            summary = summary.substring(0, MAX_SUMMARY_CHARS);
        }
        return new ReportSummary(covered, gaps, summary);
    }

    private List<String> normalizeTopics(List<String> topics, int max, String label) {
        Set<String> unique = new LinkedHashSet<>();
        for (String topic : topics) {
            if (StringUtils.hasText(topic)) {
                unique.add(topic.replaceAll("\\s+", " ").trim());
            }
        }
        List<String> kept = new ArrayList<>(unique);
        if (kept.size() > max) {
            log.warn("{} 条数超上限，截断到 {} 条（原 {} 条）: {}", label, max, kept.size(), kept);
            return new ArrayList<>(kept.subList(0, max));
        }
        return kept;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }
}
