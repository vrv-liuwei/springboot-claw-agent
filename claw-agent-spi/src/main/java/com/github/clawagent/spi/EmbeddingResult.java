package com.github.clawagent.spi;

import java.util.List;

/**
 * 单条文本的向量化结果。
 * usage 字段保留 token 统计，后续可汇总到 cost/token 统计模块。
 */
public record EmbeddingResult(String model, List<Double> vector, int promptTokens, int totalTokens) {
    public EmbeddingResult {
        vector = vector == null ? List.of() : List.copyOf(vector);
    }
}
