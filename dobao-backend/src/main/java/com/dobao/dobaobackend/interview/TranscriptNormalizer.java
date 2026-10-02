package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.asr.dto.AsrTranscriptResult;
import com.dobao.dobaobackend.interview.dto.NormalizedTranscript;
import com.dobao.dobaobackend.interview.dto.SentenceDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * ASR 结果 JSON → 内存句子列表。
 *
 * <p><b>纯转换，不落库</b>：原始 JSON 已存在 {@code ai_interview.transcript_json}，
 * 本类的结果只在本次处理（角色判定、问答清单格式化、报告附录）中使用，
 * 需要时重新解析一次即可。这样做的代价接近于零（40 分钟音频约 80KB JSON），
 * 换来的是少一张表、少一处数据漂移风险。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptNormalizer {

    private final ObjectMapper objectMapper;

    /**
     * 解析转写结果 JSON。
     *
     * @param asrJson 百炼转写结果原文
     * @return 句子列表 + 时长 + 纯文本 + 说话人集合
     */
    public NormalizedTranscript normalize(String asrJson) {
        AsrTranscriptResult result;
        try {
            result = objectMapper.readValue(asrJson, AsrTranscriptResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析转写结果 JSON 失败", e);
        }

        List<AsrTranscriptResult.Sentence> raw = new ArrayList<>();
        if (result.transcripts() != null) {
            for (AsrTranscriptResult.Transcript t : result.transcripts()) {
                if (t.sentences() != null) {
                    raw.addAll(t.sentences());
                }
            }
        }

        List<SentenceDTO> sentences = new ArrayList<>(raw.size());
        Set<Integer> speakerIds = new TreeSet<>();
        Set<String> lines = new LinkedHashSet<>();
        int fallbackSeq = 1;
        for (AsrTranscriptResult.Sentence s : raw) {
            // sentence_id 实测连续可用，缺失时用递增序号兜底，保证 seqNo 非空
            Integer seq = s.sentenceId() != null ? s.sentenceId() : fallbackSeq;
            fallbackSeq = seq + 1;
            SentenceDTO dto = new SentenceDTO(seq, s.speakerId(), s.beginTime(), s.endTime(), s.text());
            sentences.add(dto);
            if (s.speakerId() != null) {
                speakerIds.add(s.speakerId());
            }
            lines.add(dto.toLine());
        }
        // 双保险：按起始时间排序（正常情况百炼已按时间返回，实测严格单调）
        sentences.sort(Comparator.comparing(SentenceDTO::beginMs, Comparator.nullsLast(Comparator.naturalOrder())));

        NormalizedTranscript normalized = new NormalizedTranscript(
                sentences,
                result.audioDurationMs(),
                result.speechDurationMs(),
                String.join("\n", lines),
                speakerIds);

        // 本方法也会被"取文字稿"这类按需路径调用，用 info 会在前端轮询期间把日志刷满
        log.debug("转写稿归一化完成: 句子数={}, 说话人数={}, 音频时长={}ms, 语音时长={}ms",
                normalized.size(), speakerIds.size(),
                normalized.audioDurationMs(), normalized.speechDurationMs());
        return normalized;
    }
}
