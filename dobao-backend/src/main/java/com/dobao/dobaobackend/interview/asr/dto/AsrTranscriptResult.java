package com.dobao.dobaobackend.interview.asr.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 转写结果 JSON（从 {@code transcription_url} 下载得到的原文结构）
 * <pre>
 * {
 *   "file_url": "...",
 *   "properties": { "audio_format": "aac", "channels": [0,1],
 *                   "original_sampling_rate": 48000,
 *                   "original_duration_in_milliseconds": 331050 },
 *   "transcripts": [ { "channel_id": 0,
 *                      "content_duration_in_milliseconds": 226160,
 *                      "text": "...",
 *                      "sentences": [ { "begin_time": 47690, "end_time": 49810,
 *                                       "text": "哎，老师你好。", "sentence_id": 1,
 *                                       "speaker_id": 0, "words": [ ... ] } ] } ],
 *   "usage": { "input_tokens": 4729, "output_tokens": 911, "total_Tokens": 5640 }
 * }
 * </pre>
 *
 * <p><b>只建模用得到的字段</b>：
 * <ul>
 *   <li>{@code words[]} 是逐字级时间戳，占原始 JSON 约 81% 的体积，而本功能只需要句子级
 *       时间戳，因此刻意不建模 —— 原始 JSON 仍会原样落库到 {@code transcript_json} 供排障；</li>
 *   <li>{@code properties} 只取音频总时长，{@code transcripts} 只取语音时长与句子列表，
 *       {@code usage} 不参与任何业务计算，一律不建模（{@code @JsonIgnoreProperties} 会忽略）。</li>
 * </ul>
 *
 * <p>两个时长字段直接对应主表两列：
 * {@code properties.original_duration_in_milliseconds → audio_duration_ms}、
 * {@code transcripts[].content_duration_in_milliseconds → speech_duration_ms}（计费口径）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AsrTranscriptResult(
        Properties properties,
        List<Transcript> transcripts) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Properties(
            @JsonProperty("original_duration_in_milliseconds") Long originalDurationInMilliseconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transcript(
            @JsonProperty("content_duration_in_milliseconds") Long contentDurationInMilliseconds,
            List<Sentence> sentences) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sentence(
            @JsonProperty("begin_time") Long beginTime,
            @JsonProperty("end_time") Long endTime,
            String text,
            @JsonProperty("sentence_id") Integer sentenceId,
            @JsonProperty("speaker_id") Integer speakerId) {
    }

    /**
     * 音频总时长（毫秒）；缺失时返回 null
     */
    public Long audioDurationMs() {
        return properties == null ? null : properties.originalDurationInMilliseconds();
    }

    /**
     * 语音内容时长（毫秒，计费口径）：各 channel 相加
     */
    public Long speechDurationMs() {
        if (transcripts == null || transcripts.isEmpty()) {
            return null;
        }
        long total = 0L;
        boolean any = false;
        for (Transcript t : transcripts) {
            if (t.contentDurationInMilliseconds() != null) {
                total += t.contentDurationInMilliseconds();
                any = true;
            }
        }
        return any ? total : null;
    }
}
