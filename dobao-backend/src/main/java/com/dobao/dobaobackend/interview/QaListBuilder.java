package com.dobao.dobaobackend.interview;

import com.dobao.dobaobackend.interview.dto.NormalizedTranscript;
import com.dobao.dobaobackend.interview.dto.QaItem;
import com.dobao.dobaobackend.interview.dto.SentenceDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把转写句子列表**直接格式化**成问答清单。
 *
 * 报告的问答清单用的就是这份映射结果。
 */
@Slf4j
@Component
public class QaListBuilder {

    /** 问题侧为空时的占位文本（开场候选人先说话的情况） */
    private static final String PLACEHOLDER_QUESTION = "（开场发言）";

    /** 回答侧为空时的占位文本 */
    private static final String PLACEHOLDER_ANSWER = "（未作答）";

    /**
     * 明确的告别词。只在"对话末尾 {@value #CLOSING_TAIL_RATIO} 之内"生效，
     * 因此不需要覆盖所有寒暄说法。
     *
     * <p>词表按**实际转写文本**校准过：实测候选人说"那明儿见啊"，而"明儿见"是
     * "明儿见"的子串，所以两个都要列（少一个就会漏掉整段结尾截断）。
     */
    private static final List<String> CLOSING_WORDS = List.of(
            "拜拜", "再见", "再聊", "回头见", "明儿见", "明儿见", "明天见", "先这样", "先挂", "挂了");

    /**
     * 转写噪音的信号词：脏话、无意义叫喊。
     *
     * <p>实测末尾那句是"搜狗兄弟欣赏搜狗，我操。"——它显然不是面试对话，
     * 而是录音结束后环境里的杂音被 ASR 硬识别出来的。这类内容一旦进入报告，
     * 用户会以为系统在乱读。
     */
    private static final List<String> NOISE_WORDS = List.of("我操", "卧槽", "操你", "妈逼", "傻逼");

    /** 告别词截断只看清单末尾多少比例 —— 正文中段的"再见"可能只是闲聊，不该整段截断 */
    private static final double CLOSING_TAIL_RATIO = 0.7;

    /** 噪音判定只看清单的末尾多少比例 —— 中途出现脏话可能只是口头语，不该整段截断 */
    private static final double NOISE_TAIL_RATIO = 0.8;

    /** 单条日志里问答文本的截断长度 */
    private static final int LOG_TEXT_LIMIT = 40;

    /**
     * 构建问答清单。
     *
     * @param transcript           归一化文字稿
     * @param interviewerSpeakerId 角色判定给出的面试官说话人编号（可为 null，此时无法区分角色）
     * @return 问答清单（Q 与 A 均为逐字原文）；transcript 为空时返回空列表
     */
    public List<QaItem> build(NormalizedTranscript transcript, Integer interviewerSpeakerId) {
        List<SentenceDTO> sentences = transcript == null || transcript.sentences() == null
                ? List.of() : transcript.sentences();

        List<List<SentenceDTO>> blocks = mergeBySpeaker(sentences);
        List<QaItem> items = new ArrayList<>();
        List<SentenceDTO> pendingQuestion = null;
        String pendingText = null;
        String previousBlockText = null;
        int index = 1;

        for (List<SentenceDTO> block : blocks) {
            String blockText = joinText(block);

            if (isInterviewer(block, interviewerSpeakerId)) {
                if (pendingQuestion == null) {
                    pendingQuestion = block;
                    pendingText = blockText;
                } else {
                    // ① 极短确认音（"啊。"）—— 不并的话会把一个完整问答劈成两条；
                    // ② 面试官连着说的多句（岗位介绍 + 提问）—— 不并的话会产出"没有回答的中间条目"
                    List<SentenceDTO> merged = new ArrayList<>(pendingQuestion);
                    merged.addAll(block);
                    pendingQuestion = merged;
                    pendingText = joinText(merged);
                }
                continue;
            }

            // 候选人发言块：与前面的问题配对
            items.add(toItem(String.format("Q%03d", index++), pendingQuestion, pendingText, block));
            previousBlockText = blockText;
            pendingQuestion = null;
            pendingText = null;
        }

        // 结尾还有面试官发言、候选人没回：也保留成一条（不能吞掉内容）
        if (pendingQuestion != null) {
            items.add(toItem(String.format("Q%03d", index), pendingQuestion, pendingText, null));
        }

        int before = items.size();
        items = truncateAtClosing(items, previousBlockText);
        items = dropTrailingNoise(items);
        int dropped = before - items.size();

        log.info("问答清单格式化完成: 句子数={}, 发言块={}, 条目={}{}, 面试官=Speaker{}",
                sentences.size(), blocks.size(), items.size(),
                dropped > 0 ? "（结尾截断 " + dropped + " 条）" : "", interviewerSpeakerId);
        // 逐条明细只在排查时用得上，20 条问答放 INFO 会把日志刷得没有重点
        if (log.isDebugEnabled()) {
            for (QaItem item : items) {
                log.debug("问答条目 {}: Q@{}ms {} | A[{}ms~{}ms] {}",
                        item.qaId(), item.questionBeginMs(), abbreviate(item.question()),
                        item.answerBeginMs(), item.answerEndMs(), abbreviate(item.answer()));
            }
        }
        return items;
    }

    /**
     * 把连续同一说话人的句子合并成一个"发言块"。
     *
     * <p>不合并的话，面试官连着说的 5 句话会变成 5 个问题块，报告里就会出现
     * 5 条内容雷同的条目。
     */
    private List<List<SentenceDTO>> mergeBySpeaker(List<SentenceDTO> sentences) {
        List<List<SentenceDTO>> blocks = new ArrayList<>();
        if (sentences == null) {
            return blocks;
        }
        List<SentenceDTO> current = null;
        Integer currentSpeaker = null;
        for (SentenceDTO s : sentences) {
            if (s == null || !StringUtils.hasText(s.text())) {
                continue;
            }
            if (current == null || !Objects.equals(currentSpeaker, s.speakerId())) {
                current = new ArrayList<>();
                blocks.add(current);
                currentSpeaker = s.speakerId();
            }
            current.add(s);
        }
        return blocks;
    }

    /**
     * 截掉结尾的告别寒暄与转写噪音。
     *
     * <p>触发条件（两个都要满足，避免误伤正文）：
     * <ol>
     *   <li>**最后一条的候选人发言**里出现明确告别词（拜拜/再见/明儿见…）；</li>
     *   <li>该条的位置在**整份清单的后 {@value #CLOSING_TAIL_RATIO}**。</li>
     * </ol>
     *
     * <p>截断从"最后一条含告别词的条目"开始（含它自己）—— 那条通常只剩
     * "那明儿见啊""好拜拜"，已经不含任何面试信息；之后的内容实测全是寒暄与噪音
     * （例如"好还是这个链接啊"、以及末尾一句明显的转写噪音）。
     */
    private List<QaItem> truncateAtClosing(List<QaItem> items, String lastCandidateText) {
        if (items == null || items.isEmpty() || !StringUtils.hasText(lastCandidateText)) {
            return items;
        }
        if (!containsClosingWord(lastCandidateText)) {
            return items;
        }
        int start = (int) Math.floor(items.size() * CLOSING_TAIL_RATIO);
        // 从后往前找最后一条含告别词的条目，它之前的内容全部保留
        for (int i = items.size() - 1; i >= start; i--) {
            if (containsClosingWord(items.get(i).answer())) {
                List<QaItem> kept = new ArrayList<>(items.subList(0, i));
                log.info("检测到结尾告别寒暄，截断 {} 条: 「{}」 ~ 「{}」",
                        items.size() - kept.size(),
                        abbreviate(items.get(i).question()),
                        abbreviate(items.get(items.size() - 1).answer()));
                return kept;
            }
        }
        return items;
    }

    private boolean containsClosingWord(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return CLOSING_WORDS.stream().anyMatch(t::contains);
    }

    /**
     * 丢掉结尾连续的"转写噪音"条目（脏话、无意义叫喊、纯拉丁字母串）。
     *
     * <p>它是 {@link #truncateAtClosing} 的兜底：实测这段录音的最后一句是
     * "搜狗兄弟欣赏搜狗，我操。"，里面**没有任何告别词**，靠告别词规则拦不住。
     *
     * <p>只从**末尾倒着删连续命中的条目**，一遇到正常条目立刻停手 ——
     * 这样既清掉了末尾噪音，又不会因为中间某句口头语就把后面整段砍掉。
     * 且只在清单后 {@value #NOISE_TAIL_RATIO} 范围内生效。
     */
    private List<QaItem> dropTrailingNoise(List<QaItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        int floor = (int) Math.floor(items.size() * NOISE_TAIL_RATIO);
        int cut = items.size();
        while (cut - 1 >= floor && isNoise(items.get(cut - 1))) {
            cut--;
        }
        if (cut == items.size()) {
            return items;
        }
        List<QaItem> kept = new ArrayList<>(items.subList(0, cut));
        log.info("检测到结尾转写噪音，丢弃 {} 条: 「{}」",
                items.size() - cut, abbreviate(items.get(items.size() - 1).answer()));
        return kept;
    }

    /**
     * 一条问答是否"看起来像转写噪音"。
     *
     * <p>只看三个与语义无关的硬信号：命中噪音词、几乎全是拉丁字母（中文面试里不该出现）、
     * 或整条没有汉字。**不做语义判断**，避免误伤正常内容。
     */
    private boolean isNoise(QaItem item) {
        String text = item.answer();
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (NOISE_WORDS.stream().anyMatch(text::contains)) {
            return true;
        }
        if (containsHan(text)) {
            return false;
        }
        // 不含汉字：要么是英文/拼音串，要么是标点残渣
        return text.replaceAll("[\\p{Punct}\\s]", "").length() > 0;
    }

    private boolean containsHan(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private boolean isInterviewer(List<SentenceDTO> block, Integer interviewerSpeakerId) {
        if (block == null || block.isEmpty() || interviewerSpeakerId == null) {
            // 角色未知时一律当作"提问方"，至少保证内容不丢
            return true;
        }
        return interviewerSpeakerId.equals(block.get(0).speakerId());
    }

    /**
     * 一组句子 → 一条问答。
     *
     * @param questionBlock 问题侧（可为 null，表示候选人先开口的开场条目）
     * @param questionText  已拼接好的问题文本；null 时由 {@code questionBlock} 现拼
     * @param answerBlock   回答侧（可为 null，表示候选人没回）
     */
    private QaItem toItem(String qaId, List<SentenceDTO> questionBlock, String questionText,
                          List<SentenceDTO> answerBlock) {
        long questionBeginMs = questionBlock == null
                ? firstBegin(answerBlock) : firstBegin(questionBlock);
        String question = questionBlock == null
                ? PLACEHOLDER_QUESTION
                : (StringUtils.hasText(questionText) ? questionText : joinText(questionBlock));

        boolean hasAnswer = answerBlock != null && !answerBlock.isEmpty();
        String answer = hasAnswer ? joinText(answerBlock) : PLACEHOLDER_ANSWER;
        long answerBeginMs = hasAnswer ? firstBegin(answerBlock) : questionBeginMs;
        long answerEndMs = hasAnswer ? lastEnd(answerBlock) : questionBeginMs;

        return new QaItem(qaId, question, questionBeginMs, answer, answerBeginMs, answerEndMs);
    }

    /**
     * 多句合并成一段：按时间顺序直接拼接，<b>不加任何连接词、不改写</b>。
     *
     * <p>中间补一个空格而不是直接相连：转写文本常丢标点（"对吧？" "就是就业打算"），
     * 直接相连会出现"对吧？就是"这种可读性尚可、但"打算没有"这种会把两个词粘死的情况。
     */
    private String joinText(List<SentenceDTO> block) {
        StringBuilder sb = new StringBuilder();
        for (SentenceDTO s : block) {
            String text = s.text() == null ? "" : s.text().trim();
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(text);
        }
        return sb.toString();
    }

    private long firstBegin(List<SentenceDTO> block) {
        if (block == null) {
            return 0L;
        }
        for (SentenceDTO s : block) {
            if (s.beginMs() != null) {
                return s.beginMs();
            }
        }
        return 0L;
    }

    private long lastEnd(List<SentenceDTO> block) {
        long end = 0L;
        if (block == null) {
            return end;
        }
        for (SentenceDTO s : block) {
            if (s.endMs() != null) {
                end = s.endMs();
            }
        }
        return end;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= LOG_TEXT_LIMIT ? text : text.substring(0, LOG_TEXT_LIMIT) + "...";
    }
}
