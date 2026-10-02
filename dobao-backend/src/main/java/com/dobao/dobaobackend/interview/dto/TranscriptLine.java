package com.dobao.dobaobackend.interview.dto;

/**
 * 报告附录"完整对话"里的一行。
 *
 * <p><b>为什么要有它</b>：报告只放抽出来的问答对时，一旦某段对话没被识别成问答
 * （例如面试官只在介绍岗位、约定改约时间），用户在报告里就**看不到那部分内容**，
 * 会以为系统漏了。把整场对话逐句附在报告最后，用户才能自己核对
 * "抽取遗漏了没有" —— 这正是需求文档里"原始文字稿要保留、可信度靠可核对"的意思。
 *
 * <p><b>角色是判定结果推导的，不是猜的</b>：{@code speakerId == interviewerSpeakerId}
 * 即面试官，否则候选人。规格把 {@code speaker_count} 固定为 2，所以一个字段就够
 * （多说话人场景由 {@code SpeakerRoleResolver} 告警，这里按"非面试官即候选人"处理）。
 *
 * @param role     角色标签：{@code 面试官} / {@code 候选人}
 * @param beginMs  起始时间戳（毫秒，直接来自真实句子数据）
 * @param text     原话，逐字不改写
 */
public record TranscriptLine(
        String role,
        long beginMs,
        String text) {

    public static final String ROLE_INTERVIEWER = "面试官";
    public static final String ROLE_CANDIDATE = "候选人";
}
