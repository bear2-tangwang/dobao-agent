package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.dto.InterviewReport;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.ReferenceAnswer;
import com.dobao.dobaobackend.interview.dto.ReportSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染契约测试：报告必须是「问答清单 / 参考回答 / 面试总结」三节，且**不再有完整对话**。
 *
 * <p>这些断言锁的是"结构"而不是"措辞"：措辞可以调，但少一节、多一节、
 * 或者把已删除的「完整对话」加回来，都必须让测试变红。
 */
class InterviewReportRendererTest {

    private final InterviewReportRenderer renderer = new InterviewReportRenderer();

    private static QaItem qa(String id, String question, String answer) {
        return new QaItem(id, question, 88_000L, answer, 95_000L, 101_000L);
    }

    private static InterviewReport report(List<QaItem> qaList,
                                          List<ReferenceAnswer> referenceAnswers,
                                          ReportSummary summary) {
        return new InterviewReport("iv-1", 300_000L, LocalDateTime.of(2026, 10, 2, 10, 0, 0),
                qaList, referenceAnswers, summary);
    }

    @Test
    @DisplayName("三节标题齐全，且不再渲染「完整对话」")
    void rendersThreeSectionsAndNoTranscript() {
        String md = renderer.render(report(
                List.of(qa("Q001", "自我介绍一下", "我做过三年后端")),
                List.of(new ReferenceAnswer("Q002", "Redis 缓存穿透怎么解决？", "布隆过滤器 + 空值缓存。")),
                new ReportSummary(List.of("Redis"), List.of("分布式锁"), "本轮覆盖了缓存相关知识点，整体回答较完整。")));

        assertTrue(md.contains("## 一、问答清单"), md);
        assertTrue(md.contains("## 二、参考回答"), md);
        assertTrue(md.contains("## 三、面试总结"), md);
        assertFalse(md.contains("完整对话"), "完整对话已经不属于报告结构：" + md);
    }

    @Test
    @DisplayName("问答清单：Q 与 A 逐字原文 + 时间戳")
    void rendersQaListVerbatim() {
        String md = renderer.render(report(
                List.of(qa("Q001", "你了解 Redis 吗", "了解，用过缓存与分布式锁")),
                List.of(), null));

        assertTrue(md.contains("**Q001** [01:28] 你了解 Redis 吗"), md);
        // A 侧是时间区间：95s ~ 101s
        assertTrue(md.contains("**A** [01:35~01:41] 了解，用过缓存与分布式锁"), md);
    }

    @Test
    @DisplayName("参考回答：带 Q 编号与「参考回答」标签，并声明仅供参考")
    void rendersReferenceAnswers() {
        String md = renderer.render(report(
                List.of(qa("Q001", "讲讲 JVM 内存结构", "堆、栈、方法区")),
                List.of(new ReferenceAnswer("Q001", "JVM 内存结构是怎样的？", "堆、虚拟机栈、本地方法栈、方法区、程序计数器。")),
                null));

        assertTrue(md.contains("**Q001** JVM 内存结构是怎样的？"), md);
        assertTrue(md.contains("**参考回答** 堆、虚拟机栈、本地方法栈、方法区、程序计数器。"), md);
        assertTrue(md.contains("仅供复盘参考"), md);
    }

    @Test
    @DisplayName("没有技术问题时给出说明，而不是空白章节")
    void rendersEmptyReferenceAnswers() {
        String md = renderer.render(report(
                List.of(qa("Q001", "方便什么时候来面试", "下周二可以")),
                List.of(), new ReportSummary(List.of(), List.of(), "本场对话以流程沟通为主。")));

        assertTrue(md.contains("本次面试未识别出需要给出参考回答的技术问题。"), md);
    }

    @Test
    @DisplayName("面试总结：两行短列表 + 一段总结文字")
    void rendersSummary() {
        String md = renderer.render(report(
                List.of(qa("Q001", "讲讲 MySQL 索引", "B+ 树")),
                List.of(),
                new ReportSummary(List.of("MySQL 索引", "B+ 树"), List.of("索引下推"),
                        "本轮围绕数据库索引展开，能说清 B+ 树结构，建议补索引下推与执行计划。")));

        assertTrue(md.contains("- 本轮涉及知识点：MySQL 索引、B+ 树"), md);
        assertTrue(md.contains("- 后续需补充：索引下推"), md);
        assertTrue(md.contains("建议补索引下推与执行计划。"), md);
    }

    @Test
    @DisplayName("旧结构报告（新字段为 null）提示重新生成，而不是渲染成空白")
    void rendersLegacyReportHint() {
        String md = renderer.render(report(List.of(qa("Q001", "问题", "回答")), null, null));

        assertTrue(md.contains("- 参考回答：0 条"), md);
        // 两节各提示一次
        assertEquals(2, md.split("重新生成后即可看到", -1).length - 1, md);
    }

    @Test
    @DisplayName("问答清单为空时不抛异常，给出说明")
    void rendersEmptyQaList() {
        String md = renderer.render(report(List.of(), List.of(), null));

        assertTrue(md.contains("本次面试未获取到可成对的对话内容。"), md);
        assertTrue(md.contains("- 对话条目：0"), md);
    }
}
