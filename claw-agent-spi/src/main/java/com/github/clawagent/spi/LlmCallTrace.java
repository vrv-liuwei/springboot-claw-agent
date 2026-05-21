package com.github.clawagent.spi;

/**
 * 单次 LLM 调用的结构化记录。
 * requestJson 不包含 Authorization header，只保存发给 Chat Completions 的业务 payload。
 */
public record LlmCallTrace(
        String model,
        String baseUrl,
        int statusCode,
        long elapsedMs,
        String requestJson,
        String responseJson,
        String content,
        int promptTokens,
        int completionTokens,
        int totalTokens) {
}
