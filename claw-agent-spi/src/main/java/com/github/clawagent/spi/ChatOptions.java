package com.github.clawagent.spi;

/**
 * 模型调用参数。
 * 当前只暴露 M1 必需参数，后续可扩展 top_p、max_tokens、stream 等能力。
 */
public record ChatOptions(String model, double temperature, int timeoutSeconds) {
}
