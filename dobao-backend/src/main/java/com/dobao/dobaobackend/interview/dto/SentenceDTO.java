package com.dobao.dobaobackend.interview.dto;

/**
 * 归一化后的一个句子（内存对象，<b>不落库</b>）。
 *
 * <p>为什么句子级数据不单独建表：它 100% 可由 {@code ai_interview.transcript_json} 推导
 * （见 {@code docs/interview-step1-analysis.md} §9），落库只是把同一份数据存两遍。
 * 原始 JSON 才是句子级数据的唯一持久化来源，本记录只在处理过程中存活。
 *
 * @param seqNo     句子序号，直接采用百炼的 {@code sentence_id}（实测连续 1..N），保留溯源能力
 * @param speakerId 百炼给出的说话人编号。<b>跨任务没有稳定含义</b>（同一场里 0 可能是候选人、
 *                  另一场可能是面试官），因此业务代码绝不能硬编码"0 就是面试官"，
 *                  必须结合 {@code ai_interview.interviewer_speaker_id} 判断
 * @param beginMs   起始时间戳（毫秒），对应百炼的 {@code begin_time}
 * @param endMs     结束时间戳（毫秒），对应百炼的 {@code end_time}
 * @param text      文本
 */
public record SentenceDTO(
        Integer seqNo,
        Integer speakerId,
        Long beginMs,
        Long endMs,
        String text) {

    /**
     * 格式化为一行可读文本，用于 prompt 与日志（只有说话人编号，不含角色语义）
     */
    public String toLine() {
        return "[Speaker" + speakerId + "] " + text;
    }
}
