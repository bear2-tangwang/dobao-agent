package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.KnowledgeGap;
import com.dobao.dobaobackend.interview.dto.KnowledgeTopic;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.TranscriptLine;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 结构化报告 JSON → Markdown。
 *
 * <p>纯渲染，无副作用。输出四部分：问答清单 / 知识点清单 / 待补充知识点 / **完整对话**。
 * 前三部分与需求文档 §3.2 的模板一致，**不含评分与总评**；
 * 第四部分是逐句文字稿附录，用于核对问答清单是否完整
 * （理由见 {@code docs/interview-step4-report.md} §9）。
 *
 * <p><b>问答清单的排版约定（2026-10 重构）</b>：每条固定两行 ——
 * {@code **Q001** [01:28] 面试官原话} 与 {@code **A** [01:35~01:41] 候选人原话}。
 * Q 与 A <b>都是逐字原文</b>（由 {@code QaListBuilder} 直接格式化，未经模型改写），
 * 因此这里恢复了时间戳展示：它不再是"模型给的、需要怀疑的数字"，
 * 而是直接取自真实句子数据的毫秒值，用来把清单定位回录音。
 *
 * <p>原实现里的"⚠️ 分块未抽取成功 / 已跳过 N 个不含提问的片段"两行提示已删除：
 * 分块与分块失败这两个概念不存在了。
 */
@Component
public class InterviewReportRenderer {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 渲染成 Markdown 文本
     */
    public String render(InterviewReport report) {
        StringBuilder md = new StringBuilder();

        md.append("# 面试总结报告\n\n");
        md.append("- 面试编号：").append(report.interviewId()).append('\n');
        md.append("- 音频时长：").append(formatDuration(report.audioDurationMs())).append('\n');
        md.append("- 生成时间：")
                .append(report.generatedAt() == null ? "-" : report.generatedAt().format(TIME_FORMAT))
                .append('\n');
        md.append("- 对话条目：").append(report.qaList() == null ? 0 : report.qaList().size()).append('\n');
        md.append('\n');

        renderQaSection(md, report.qaList());
        renderTopicSection(md, report.knowledgeTopics());
        renderGapSection(md, report.knowledgeGaps());
        renderTranscriptSection(md, report.transcript());

        return md.toString();
    }


    private void renderQaSection(StringBuilder md, List<QaItem> qaList) {
        md.append("## 一、问答清单\n\n");
        if (qaList == null || qaList.isEmpty()) {
            md.append("本次面试未获取到可成对的对话内容。\n\n");
            return;
        }
        md.append("> 按对话轮次逐条列出：面试官的一段发言为一条 Q，紧随其后的候选人发言为对应的 A，")
                .append("两者均为**逐字原文**，未做任何摘要或改写。\n\n");

        for (QaItem qa : qaList) {
            // 编号保留 Q001 这种三位形式：第二部分"知识点清单"的 `出自：Q002` 直接引用 qaId，
            // 两处必须字面一致（前端渲染的是同一份 Markdown）。
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

    // ==================== 第二部分：知识点 ====================

    private void renderTopicSection(StringBuilder md, List<KnowledgeTopic> topics) {
        md.append("## 二、知识点清单\n\n");
        if (topics == null || topics.isEmpty()) {
            md.append("本次面试未提炼出可确认的知识点（该场对话可能是岗位介绍、流程说明或约定时间的沟通）。\n\n");
            return;
        }
        for (KnowledgeTopic topic : topics) {
            md.append("### ").append(oneLine(topic.topic())).append("\n\n");
            if (topic.points() != null) {
                for (String point : topic.points()) {
                    md.append("- ").append(oneLine(point)).append('\n');
                }
            }
            if (topic.relatedQaIds() != null && !topic.relatedQaIds().isEmpty()) {
                md.append("- 出自：").append(String.join("、", topic.relatedQaIds())).append('\n');
            }
            md.append('\n');
        }
    }

    // ==================== 第三部分：待补充知识点 ====================

    private void renderGapSection(StringBuilder md, List<KnowledgeGap> gaps) {
        md.append("## 三、待补充知识点\n\n");
        if (gaps == null || gaps.isEmpty()) {
            md.append("本次面试未发现明显需要补充的知识点。\n\n");
            return;
        }
        for (KnowledgeGap gap : gaps) {
            md.append("### ").append(oneLine(gap.point())).append("\n\n");
            md.append("- 本次表现：").append(oneLine(gap.performance())).append('\n');
            md.append("- 为什么补：").append(oneLine(gap.why())).append('\n');
            if (gap.directions() != null && !gap.directions().isEmpty()) {
                md.append("- 补充方向：\n");
                for (String direction : gap.directions()) {
                    md.append("  - ").append(oneLine(direction)).append('\n');
                }
            }
            md.append('\n');
        }
    }

    // ==================== 第四部分：完整对话 ====================

    /**
     * 把整场对话逐句附在报告最后。
     *
     * <p><b>为什么放最后</b>：报告的阅读顺序是"结论在前、证据在后" ——
     * 用户先看问答清单与知识点，需要核对"有没有漏"时才往下翻完整对话。
     *
     * <p><b>为什么必须有它</b>：问答清单是按轮次成对的，一条 Q 里合并了面试官连着说的多句，
     * 完整对话则保留逐句边界与每句自己的时间戳（例如哪一句才是"寒暄"、哪一句才是"改约"），
     * 两者互为对照。
     */
    private void renderTranscriptSection(StringBuilder md, List<TranscriptLine> transcript) {
        md.append("## 四、完整对话\n\n");
        if (transcript == null || transcript.isEmpty()) {
            md.append("本次面试未获取到文字稿。\n\n");
            return;
        }
        md.append("> 以下是转写稿全文（逐句原话，未做任何改写），用于核对问答清单是否完整。\n\n");
        for (TranscriptLine line : transcript) {
            md.append("**").append(oneLine(line.role())).append("** [")
                    .append(formatTimestamp(line.beginMs())).append("] ")
                    .append(oneLine(line.text())).append("\n\n");
        }
    }

    // ==================== 格式化工具 ====================

    /**
     * 毫秒 → {@code mm:ss}（超过 1 小时用 {@code h:mm:ss}）
     *
     * <p>2026-10 重构后问答清单恢复展示时间戳：这些毫秒值直接来自转写句子的
     * {@code begin_ms}/{@code end_ms}，不是模型给的估算值，展示出来只增加可核对性。
     */
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

    /**
     * 毫秒 → 中文时长描述
     */
    private static String formatDuration(long ms) {
        if (ms <= 0) {
            return "未知";
        }
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes > 0 ? minutes + " 分 " + seconds + " 秒" : seconds + " 秒";
    }

    /**
     * 折成一行，避免破坏 Markdown 结构
     */
    private static String oneLine(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
