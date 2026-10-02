package com.dobao.dobaobackend.interview.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * "要 LLM 回 JSON"的统一调用封装。
 *
 * <p>步骤 3/4 现在只剩 2 类调用（角色判定、知识点归纳），形态完全一样：
 * 系统提示 + 用户内容 + JSON schema → 结构化对象。集中在这里的好处是不会出现
 * "有的地方判了截断、有的地方没判"。
 *
 * <p><b>核心是截断检测（方案 1）</b>：先看 {@code finishReason}，为 {@code length} 说明输出被
 * {@code maxTokens} 打满、JSON 必然是半截的，此时直接抛
 * {@link LlmOutputTruncatedException}，而不是把半截文本丢给 Jackson 换一个
 * 毫无信息量的 {@code JsonParseException}。原分块抽取链路里这是最可能发生的失败模式
 * （一块要一次吐出多个问答对），2026-10 重构后风险主要落在知识点归纳上。
 *
 * <p>think 标签不需要在这里处理：{@code BeanOutputConverter} 默认挂的
 * {@code ResponseTextCleaner} 链（Whitespace → ThinkingTag → MarkdownCodeBlock → Whitespace）
 * 已经会剥掉 {@code <think>}/{@code  thinking}/{@code <reasoning>} 与 ``` 围栏
 * —— 这是读 Spring AI 1.1 字节码确认的，不是猜的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmJsonSupport {

    /**
     * 失败时打印的原文长度上限，避免日志被大段文本淹没
     */
    private static final int RAW_LOG_LIMIT = 400;

    private final ChatModel chatModel;

    /**
     * 结构化抽取的默认温度。
     *
     * <p><b>为什么不沿用全局的 0.7</b>：实测同一个音频连续跑 5 次，问答对数量在 1 和 2 之间跳、
     * 待补充知识点在 0 和 1 之间跳（`temperature: 0.7` 来自 {@code application.yml} 的
     * {@code spring.ai.openai.chat.options}）。问答抽取与知识点归纳都是**信息抽取**任务，
     * 不是在写文章，采样发散只会让同一份输入产出不同报告 —— 这直接违反需求文档
     * "抽出问答对数量与实际提问数量偏差 ≤ 2" 与 AC-6 的稳定性要求。
     *
     * <p>不用 0.0 而用 0.1：0.1 已经能把随机性压到实测一致，同时避免部分服务端
     * 对 temperature=0 的边界处理差异。
     */
    public static final double EXTRACTION_TEMPERATURE = 0.1;

    /**
     * 固定随机种子（配合低温进一步保证"同一输入同一输出"）。
     *
     * <p>需求 AC-13 要求"同一 interviewId 连续重跑 ≥3 次结果一致"。
     * 温度只是把概率压小，`seed` 才能让采样路径真正可复现 —— 两者一起用。
     */
    public static final int EXTRACTION_SEED = 42;

    /**
     * 调用 LLM 并解析为结构化对象，按次选项用"抽取档"默认值（{@value #EXTRACTION_TEMPERATURE} 低温 + 固定种子）。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息（不含格式说明，本方法会自动附上 schema）
     * @param typeRef      目标类型
     */
    public <T> T callForJson(String systemPrompt, String userMessage,
                             ParameterizedTypeReference<T> typeRef) {
        return callForJson(systemPrompt, userMessage, typeRef,
                extractionOptions(EXTRACTION_TEMPERATURE, EXTRACTION_SEED, null, null));
    }

    /**
     * 调用 LLM 并解析为结构化对象，**按次选项完全由调用方提供**。
     *
     * <p>这是最灵活的重载：温度、种子、模型覆盖、以及关掉思考这类厂商扩展参数
     * 都可以一次带进来（见 {@link #extractionOptions}）。
     *
     * @param options 本次调用的 {@link ChatOptions}；传 null 表示沿用全局默认
     * @throws LlmOutputTruncatedException 输出被 maxTokens 截断
     * @throws IllegalStateException       模型输出无法解析为目标类型
     */
    public <T> T callForJson(String systemPrompt, String userMessage,
                             ParameterizedTypeReference<T> typeRef,
                             @Nullable ChatOptions options) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(typeRef);
        List<Message> messages = List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userMessage + "\n\n输出格式要求：\n" + converter.getFormat()));

        long start = System.currentTimeMillis();
        ChatResponse response = chatModel.call(new Prompt(messages, options));
        long costMs = System.currentTimeMillis() - start;
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("LLM 返回为空");
        }

        String finishReason = response.getResult().getMetadata() == null
                ? null : response.getResult().getMetadata().getFinishReason();
        String raw = response.getResult().getOutput().getText();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("LLM 返回内容为空, finishReason=" + finishReason);
        }

        // 打印耗时与 token 明细：单次调用实测可达 220 秒以上（completion 里绝大部分是模型"思考"），
        // 没有这行日志就无法判断"慢"是卡在输入、输出还是模型思考
        log.info("LLM 调用完成: type={}, 耗时={}ms, promptTokens={}, completionTokens={}, 输出字符={}, finishReason={}",
                typeRef.getType(), costMs,
                tokens(response, true), tokens(response, false), raw.length(), finishReason);

        // 方案 1：先判截断，再解析
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new LlmOutputTruncatedException(
                    "LLM 输出被 maxTokens 截断（finishReason=length），已输出 " + raw.length() + " 字符");
        }

        try {
            T result = converter.convert(raw);
            log.info("LLM JSON 解析成功: type={}, rawChars={}", typeRef.getType(), raw.length());
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("LLM 输出无法解析为目标类型, finishReason="
                    + finishReason + ", raw=" + abbreviate(raw), e);
        }
    }

    /**
     * 构造"抽取专用"的按次选项：低温 + 固定种子 + 可选模型覆盖 + 可选厂商扩展参数。
     *
     * <p>由调用方（{@code InterviewReportGenerator}，知识点归纳那一次调用）从
     * {@code interview.report.*} 配置组装，
     * 这样"要不要关思考 / 换哪个模型"是配置问题，不需要改代码。
     * 选项会与全局默认合并，所以 {@code maxTokens} 等仍沿用 yml 里的设置。
     *
     * @param model     抽取模型名，null/空表示不覆盖
     * @param extraBody 厂商扩展参数（如 {@code enable_thinking=false}），null/空表示不加
     */
    public static ChatOptions extractionOptions(double temperature, Integer seed,
                                                @Nullable String model,
                                                @Nullable Map<String, Object> extraBody) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(temperature)
                .seed(seed == null ? EXTRACTION_SEED : seed);
        if (model != null && !model.isBlank()) {
            builder.model(model);
        }
        if (extraBody != null && !extraBody.isEmpty()) {
            builder.extraBody(extraBody);
        }
        return builder.build();
    }

    private static String abbreviate(String raw) {
        String flat = raw.replaceAll("\\s+", " ");
        return flat.length() <= RAW_LOG_LIMIT ? flat : flat.substring(0, RAW_LOG_LIMIT) + "...";
    }

    /**
     * 取 token 用量（拿不到时返回 -1，避免日志里出现 null 干扰阅读）
     */
    private static long tokens(ChatResponse response, boolean prompt) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return -1L;
        }
        Integer value = prompt
                ? response.getMetadata().getUsage().getPromptTokens()
                : response.getMetadata().getUsage().getCompletionTokens();
        return value == null ? -1L : value;
    }

}
