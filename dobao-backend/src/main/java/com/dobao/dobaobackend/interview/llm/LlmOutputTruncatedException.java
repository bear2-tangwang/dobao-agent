package com.dobao.dobaobackend.interview.llm;

/**
 * LLM 输出被 {@code maxTokens} 截断（{@code finishReason=length}）。
 *
 * <p>单独建异常类型是为了把"截断"和"格式不对"分开：截断应缩小分块重试，
 * 格式不对应修正 prompt。混成一个 {@code JsonParseException} 时两者无法区分。
 */
public class LlmOutputTruncatedException extends RuntimeException {

    public LlmOutputTruncatedException(String message) {
        super(message);
    }
}
