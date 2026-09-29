package com.dobao.dobaobackend.entity.record;

/**
 * 批评结果记录
 */
public record CritiqueResult(
        boolean passed,
        String feedback
) {
}
