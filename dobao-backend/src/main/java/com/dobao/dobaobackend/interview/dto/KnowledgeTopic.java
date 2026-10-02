package com.dobao.dobaobackend.interview.dto;

import java.util.List;

/**
 * 报告第二部分：一个知识点主题及其具体要点。
 *
 * @param topic        知识点主题（如 "Redis"、"JVM"）
 * @param points       本次面试中**实际答到**的要点（来自原文，不得编造）
 * @param relatedQaIds 这些要点出自哪些问答对
 */
public record KnowledgeTopic(String topic, List<String> points, List<String> relatedQaIds) {
}
