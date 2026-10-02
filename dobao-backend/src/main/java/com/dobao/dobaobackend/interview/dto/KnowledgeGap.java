package com.dobao.dobaobackend.interview.dto;

import java.util.List;

/**
 * 报告第三部分：一个待补充的知识点。
 *
 * @param point       知识点
 * @param performance 候选人在本次面试中的表现（基于原文描述）
 * @param why         为什么需要补
 * @param directions  建议补充的方向（只给方向，不给资料链接）
 */
public record KnowledgeGap(String point, String performance, String why, List<String> directions) {
}
