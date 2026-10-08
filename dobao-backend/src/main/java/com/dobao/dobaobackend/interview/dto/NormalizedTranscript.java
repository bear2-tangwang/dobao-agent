package com.dobao.dobaobackend.interview.dto;

import java.util.List;
import java.util.Set;

/**
 * 一次归一化的完整结果（内存对象，不落库）。
 *
 * @param sentences        句子列表，按时间顺序
 * @param audioDurationMs  音频总时长（毫秒）→ 落 {@code ai_interview.audio_duration_ms}
 * @param speechDurationMs 语音内容时长（毫秒，计费口径）→ 落 {@code ai_interview.speech_duration_ms}
 * @param plainText        归一化纯文本（每句一行）→ 落 {@code ai_interview.transcript_text}
 * @param speakerIds       出现过的说话人编号集合，供角色判定判断"是否恰好 2 人"
 */
public record NormalizedTranscript(
        List<SentenceDTO> sentences,
        Long audioDurationMs,
        Long speechDurationMs,
        String plainText,
        Set<Integer> speakerIds) {

    /**
     * 句子总数
     */
    public int size() {
        return sentences == null ? 0 : sentences.size();
    }

    /**
     * 是否恰好两个说话人（规格把 speaker_count 固定为 2，这是角色判定成立的前提）
     */
    public boolean hasExactlyTwoSpeakers() {
        return speakerIds != null && speakerIds.size() == 2;
    }
}
