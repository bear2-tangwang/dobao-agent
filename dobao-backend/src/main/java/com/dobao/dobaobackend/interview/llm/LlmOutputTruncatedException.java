package com.dobao.dobaobackend.interview.llm;

/**
 * LLM 输出被 {@code maxTokens} 截断（{@code finishReason=length}）。
 *
 * <p>单独建异常类型是为了把"截断"和"格式不对"区分开：截断应该缩小分块重试，
 * 格式不对应该修正 prompt。两者混成一个 {@code JsonParseException} 时无法区分，
 * 是步骤 3/4 最难查的一类问题。
 */
public class LlmOutputTruncatedException extends RuntimeException {

    public LlmOutputTruncatedException(String message) {
        super(message);
    }
}
