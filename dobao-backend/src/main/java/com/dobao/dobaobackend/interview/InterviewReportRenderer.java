package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.StringJoiner;

/**
 * 结构化报告 JSON → Markdown。纯渲染，无副作用。
 *
 * <p>输出三部分：问答清单 / 参考回答 / 面试总结，不含评分与总评。
 * 问答清单把转写句子直接格式化（逐字原文），不经过模型改写。
 *
 * <p>{@code referenceAnswers} / {@code summary} 为 null 时按旧报告处理，
 * 提示用户重新生成，而不是渲染成空白。
 */
@Component
public class InterviewReportRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 旧版报告缺少新字段时的统一提示 */
    private static final String LEGACY_HINT = "本场报告生成于旧版本，未包含本节内容；重新生成后即可看到。";

    /** 渲染成 Markdown 文本 */
    public String render(InterviewReport report) {
        StringBuilder md = new StringBuilder();

        md.append("# 面试总结报告\n\n");
        md.append("- 面试编号：").append(report.interviewId()).append('\n');
        md.append("- 音频时长：").append(formatDuration(report.audioDurationMs())).append('\n');
        md.append("- 生成时间：")
                .append(report.generatedAt() == null ? "-" : report.generatedAt().format(TIME_FORMAT))
                .append('\n');
        md.append("- 对话条目：").append(report.qaList() == null ? 0 : report.qaList().size()).append('\n');
        md.append("- 参考回答：")
                .append(report.referenceAnswers() == null ? 0 : report.referenceAnswers().size())
                .append(" 条\n");
        md.append('\n');

        renderQaSection(md, report.qaList());
        renderReferenceSection(md, report.referenceAnswers());
        renderSummarySection(md, report.summary());

        return md.toString();
    }

    private void renderQaSection(StringBuilder md, List<QaItem> qaList) {
        md.append("## 一、问答清单\n\n");
        if (qaList == null || qaList.isEmpty()) {
            md.append("本次面试未获取到可成对的对话内容。\n\n");
            return;
        }
        md.append("> 整场面试的问答，按对话轮次逐条列出：面试官的一段发言为一条 Q，")
                .append("紧随其后的候选人发言为对应的 A，两者均为**逐字原文**，未做任何摘要或改写。\n\n");

        for (QaItem qa : qaList) {
            md.append("**").append(oneLine(qa.qaId())).append("** [")
                    .append(formatTimestamp(qa.questionBeginMs())).append("] ")
                    .append(oneLine(qa.question())).append("\n\n");

            md.append("**A** [").append(formatTimestamp(qa.answerBeginMs()));
            if (qa.answerEndMs() > qa.answerBeginMs()) {
                md.append('~').append(formatTimestamp(qa.answerEndMs()));
            }
            md.append("] ").append(oneLine(qa.answer())).append("\n\n");
        }
    }

    private void renderReferenceSection(StringBuilder md, List<ReferenceAnswer> referenceAnswers) {
        md.append("## 二、参考回答\n\n");
        if (referenceAnswers == null) {
            md.append(LEGACY_HINT).append("\n\n");
            return;
        }
        if (referenceAnswers.isEmpty()) {
            md.append("本次面试未识别出需要给出参考回答的技术问题。\n\n");
            return;
        }
        md.append("> 下列参考回答由模型根据问答清单生成，**仅供复盘参考**，不代表标准答案；")
                .append("编号对应第一节的问答条目。\n\n");

        for (ReferenceAnswer item : referenceAnswers) {
            md.append("**").append(oneLine(item.qaId())).append("** ")
                    .append(oneLine(item.question())).append("\n\n");
            md.append("**参考回答** ").append(oneLine(item.answer())).append("\n\n");
        }
    }

    private void renderSummarySection(StringBuilder md, ReportSummary summary) {
        md.append("## 三、面试总结\n\n");
        if (summary == null) {
            md.append(LEGACY_HINT).append("\n\n");
            return;
        }
        if (summary.isEmpty()) {
            md.append("本次面试未生成总结。\n\n");
            return;
        }

        List<String> covered = summary.safeCoveredTopics();
        List<String> gaps = summary.safeGapTopics();
        if (!covered.isEmpty()) {
            md.append("- 本轮涉及知识点：").append(joinTopics(covered)).append('\n');
        }
        if (!gaps.isEmpty()) {
            md.append("- 后续需补充：").append(joinTopics(gaps)).append('\n');
        }
        if (!covered.isEmpty() || !gaps.isEmpty()) {
            md.append('\n');
        }
        if (summary.summary() != null && !summary.summary().isBlank()) {
            md.append(oneLine(summary.summary())).append("\n\n");
        }
    }

    private static String joinTopics(List<String> topics) {
        StringJoiner joiner = new StringJoiner("、");
        for (String topic : topics) {
            if (topic != null && !topic.isBlank()) {
                joiner.add(topic.trim());
            }
        }
        return joiner.toString();
    }

    /** 毫秒 → {@code mm:ss}（超过 1 小时用 {@code h:mm:ss}）；毫秒值取自转写句子，不是模型估算 */
    private static String formatTimestamp(long ms) {
        if (ms <= 0) {
            return "00:00";
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }

    /** 毫秒 → 中文时长描述 */
    private static String formatDuration(long ms) {
        if (ms <= 0) {
            return "未知";
        }
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + " 分 " + seconds + " 秒" : seconds + " 秒";
    }

    /** 折成一行，避免破坏 Markdown 结构 */
    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
