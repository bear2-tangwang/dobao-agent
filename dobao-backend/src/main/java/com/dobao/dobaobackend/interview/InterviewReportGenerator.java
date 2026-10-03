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
 * 报告生成（Reduce 阶段）。
 *
 * <p><b>问答清单不过模型</b>：它直接取 {@link QaListBuilder} 的格式化产物，
 * 保证报告里的问答与时间戳与文字稿逐字一致，不会被二次改写。
 *
 * <p><b>全流程只在这里调一次 LLM</b>（角色判定那次除外）：把整份问答清单交给模型，
 * 产出「技术问题的参考回答 + 面试总结（涉及知识点 / 待补充知识点 / 约 100 字总结）」。
 *
 * <p><b>2026-10 重构</b>：原实现产出「知识点清单 + 待补充知识点」并做<b>词面校验</b>
 * （{@code topic}/{@code point} 必须整串命中原文，或 2-gram 覆盖率 ≥ 60%），
 * 那套校验随两个字段一起删除；参考回答的内容本就是"应该怎么答"，不可能出现在候选人原话里，
 * 用"是否出现在原文"判定只会把正确结果全部误删。
 *
 * <p><b>现在的防编造只有一层，但落在真正有效的地方 —— 编号落地校验</b>：
 * {@code referenceAnswers[].qaId} 必须指向真实存在的问答条目，否则整条剔除并记日志；
 * 字段为空、编号重复的条目同样剔除。这样"模型凭空造一条没有出处的问答"会被拦下，
 * 而"模型把问题表述得更清楚"不会被误伤。
 *
 * <p>面试总结（{@link ReportSummary}）<b>不做词面校验</b>：它是抽象表述，
 * 允许出现原文里没有的词（如"建议补充分布式事务"）。
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
            // 没有问答条目时不调用模型：既省一次调用，也避免模型"为了凑内容"凭空编参考回答
            log.warn("问答清单为空，跳过参考回答与总结生成: interviewId={}", record.getInterviewId());
            draft = new ReportDraft(List.of(), null);
        } else {
            // 序列化失败说明问答清单里有无法映射的字段，属于编码问题，直接抛出而不是让模型收到半截输入
            String qaJson;
            try {
                qaJson = objectMapper.writeValueAsString(qaList);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("序列化问答清单失败: " + e.getMessage(), e);
            }
            // 调用模型生成报告
            draft = llmJsonSupport.callForJson(
                    InterviewPrompts.REPORT_SYSTEM,
                    InterviewPrompts.reportUser(qaJson),
                    new ParameterizedTypeReference<>() {
                    },
                    buildReportOptions());
        }

        // 编号落地校验：只有真实存在的问答条目才能被参考回答引用
        Map<String, QaItem> byQaId = new LinkedHashMap<>();
        for (QaItem item : qaList) {
            if (item != null && StringUtils.hasText(item.qaId())) {
                byQaId.putIfAbsent(item.qaId(), item);
            }
        }
        // 校验参考回答：确保每个参考回答都指向一个真实存在的问答条目
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
     * 组装报告归纳这一次调用的按次选项。
     *
     * <p>这次调用既做抽取（挑出技术问题）也做写作（写参考回答与总结），
     * 但仍沿用抽取档的低温 + 固定种子：报告要求"同一份问答清单跑出同一份报告"（需求 AC-13）。
     * 温度与种子本身写死在
     * {@link LlmJsonSupport#EXTRACTION_TEMPERATURE} / {@link LlmJsonSupport#EXTRACTION_SEED}，
     * 这里只负责把 {@code interview.report.*} 里可配的部分（换模型、关思考）带进来。
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
     * 参考回答校验：剔除没有出处的条目、空条目与重复编号。
     *
     * <p>这是重构后唯一保留的防编造手段，判定标准只有一条：**这条参考回答是不是真出自本场问答**。
     * 问题措辞允许与面试官原话不同（模型本来就被要求把原话收敛成清晰的问题），
     * 因此这里只校验编号，不校验词面。
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
            // 去重放在字段校验之后：空条目不该"占掉"这个编号，否则模型先给一条空的、
            // 后面那条正常的同一编号会被当成重复丢掉（实测踩过）
            if (!seen.add(qaId)) {
                log.warn("剔除重复编号的参考回答: qaId={}", qaId);
                continue;
            }
            kept.add(new ReferenceAnswer(qaId, candidate.question().trim(), candidate.answer().trim()));
        }
        return kept;
    }

    /**
     * 面试总结清洗：去空白、去重、去掉超出上限的内容。
     *
     * <p>上限只是兜底 —— prompt 已经要求 4~8 个知识点与约 100 字总结，
     * 但"总结不要过多"是硬要求，不能完全指望模型每次都听话。
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
