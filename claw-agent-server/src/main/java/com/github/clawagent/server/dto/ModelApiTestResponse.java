package com.github.clawagent.server.dto;

/**
 * 模型在线测试响应。
 *
 * @param success 是否测试成功。
 * @param statusCode HTTP 状态码；网络异常时为 0。
 * @param message 模型返回摘要或错误说明。
 * @param rawError 错误响应片段，避免页面展示超长原文。
 * @param promptTokens 请求 token 数；供应商未返回时为 0。
 * @param completionTokens 回复 token 数；供应商未返回时为 0。
 * @param totalTokens 总 token 数；供应商未返回时为 0。
 * @param elapsedMs 请求耗时毫秒。
 */
public record ModelApiTestResponse(
        boolean success,
        int statusCode,
        String message,
        String rawError,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long elapsedMs
) {
}
