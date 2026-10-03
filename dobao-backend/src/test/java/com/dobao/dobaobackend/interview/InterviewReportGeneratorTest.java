package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.config.InterviewProperties;
import com.dobao.dobaobackend.entity.AiInterview;
import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.interview.dto.ReportDraft;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
import com.dobao.dobaobackend.interview.llm.LlmJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.ParameterizedTypeReference;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报告归纳的落地校验测试。
 *
 * <p>用<b>手写 stub</b> 顶替 LLM（{@link LlmJsonSupport} 的构造只依赖 ChatModel，
 * 不注入 Spring 容器），因此这些断言完全离线、可复现：
 * 模型给什么，代码就该按既定规则清洗成什么。
 */
class InterviewReportGeneratorTest {

    /** 假 LLM：直接返回构造时给的结果，并记录被调用次数 */
    private static class StubLlmJsonSupport extends LlmJsonSupport {

        private final ReportDraft draft;
        private int calls;

        StubLlmJsonSupport(ReportDraft draft) {
            super(null);
            this.draft = draft;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T callForJson(String systemPrompt, String userMessage,
                                 ParameterizedTypeReference<T> typeRef, ChatOptions options) {
            calls++;
            return (T) draft;
        }

        /** 被调用次数（0 表示这次生成没有花钱调模型） */
        int calledTimes() {
            return calls;
        }
    }

    private static AiInterview record() {
        AiInterview record = new AiInterview();
        record.setInterviewId("iv-1");
        record.setAudioDurationMs(300_000L);
        return record;
    }

    private static QaItem qa(String id) {
        return new QaItem(id, "问题 " + id, 1_000L, "回答 " + id, 2_000L, 3_000L);
    }

    private static InterviewReportGenerator generator(StubLlmJsonSupport llm) {
        return new InterviewReportGenerator(llm, new ObjectMapper(), new InterviewProperties());
    }

    @Test
    @DisplayName("问答清单为空时不调用模型，报告后两节为空")
    void skipsLlmWhenNoQa() {
        StubLlmJsonSupport llm = new StubLlmJsonSupport(
                new ReportDraft(List.of(new ReferenceAnswer("Q001", "不该被用", "不该被用")), null));

        InterviewReport report = generator(llm).generate(record(), List.of());

        assertEquals(0, llm.calledTimes(), "没有问答条目就不该花钱调模型");
        assertTrue(report.referenceAnswers().isEmpty());
        assertTrue(report.summary().isEmpty(), "总结三部分都为空时视为没有总结");
        assertEquals("iv-1", report.interviewId());
        assertEquals(300_000L, report.audioDurationMs());
    }

    @Test
    @DisplayName("参考回答必须引用真实存在的问答编号：无出处 / 空字段 / 重复编号都被剔除")
    void dropsReferenceAnswersWithoutGrounding() {
        ReportDraft draft = new ReportDraft(List.of(
                new ReferenceAnswer("Q002", "Redis 缓存穿透怎么解决？", "布隆过滤器 + 空值缓存。"),
                new ReferenceAnswer("Q999", "编造的问题", "编造的答案"),
                new ReferenceAnswer(null, "没有编号", "答案"),
                new ReferenceAnswer("Q001", "   ", "问题为空"),
                new ReferenceAnswer("Q001", "讲讲 JVM 内存结构", "堆、栈、方法区。"),
                new ReferenceAnswer("Q001", "重复引用同一条", "重复引用的答案")
        ), null);

        InterviewReport report = generator(new StubLlmJsonSupport(draft))
                .generate(record(), List.of(qa("Q001"), qa("Q002")));

        List<ReferenceAnswer> kept = report.referenceAnswers();
        assertEquals(List.of("Q002", "Q001"), kept.stream().map(ReferenceAnswer::qaId).toList(),
                "只剩有出处的条目，且同一条问答只保留第一条");
        assertEquals("讲讲 JVM 内存结构", kept.get(1).question());
        assertEquals("堆、栈、方法区。", kept.get(1).answer());
    }

    @Test
    @DisplayName("面试总结清洗：去重、截断超量条目、压平换行、限制字数")
    void normalizesSummary() {
        List<String> covered = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            covered.add("知识点" + i);
        }
        covered.add("知识点1");   // 重复
        covered.add("   ");        // 空白
        List<String> gaps = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            gaps.add("待补" + i);
        }
        String longSummary = "总结".repeat(300);

        ReportDraft draft = new ReportDraft(List.of(),
                new ReportSummary(covered, gaps, "  本轮\n覆盖了  缓存相关知识点。  " + longSummary));

        ReportSummary summary = generator(new StubLlmJsonSupport(draft))
                .generate(record(), List.of(qa("Q001"))).summary();

        assertEquals(10, summary.safeCoveredTopics().size(), "涉及知识点最多 10 条");
        assertEquals("知识点1", summary.safeCoveredTopics().get(0));
        assertEquals(8, summary.safeGapTopics().size(), "待补充知识点最多 8 条");
        assertFalse(summary.summary().contains("\n"), "总结必须折成一行，避免破坏 Markdown");
        assertTrue(summary.summary().startsWith("本轮 覆盖了 缓存相关知识点。"), summary.summary());
        assertEquals(400, summary.summary().length(), "总结超长时截断到兜底上限");
    }

    @Test
    @DisplayName("模型没给总结时返回空总结对象，而不是 null（前端与渲染器都不必判空）")
    void toleratesNullSummary() {
        ReportDraft draft = new ReportDraft(null, null);

        InterviewReport report = generator(new StubLlmJsonSupport(draft))
                .generate(record(), List.of(qa("Q001")));

        assertTrue(report.referenceAnswers().isEmpty());
        assertTrue(report.summary().isEmpty());
        assertTrue(report.summary().safeCoveredTopics().isEmpty());
        assertTrue(report.summary().safeGapTopics().isEmpty());
    }
}
