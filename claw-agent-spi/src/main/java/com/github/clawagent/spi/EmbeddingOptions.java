package com.github.clawagent.spi;

/**
 * Embedding 调用参数。
 * dimensions 为 0 时表示不向模型侧传维度，让模型使用默认输出维度。
 */
public record EmbeddingOptions(String model, int dimensions, int timeoutSeconds) {
}
