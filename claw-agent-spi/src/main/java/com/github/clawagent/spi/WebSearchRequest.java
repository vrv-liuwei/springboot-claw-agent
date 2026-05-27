package com.github.clawagent.spi;

import java.util.Map;

/**
 * Agent 层统一搜索请求。
 * query 是唯一公共字段，其余参数原样交给具体 Provider 解释，避免把 Bocha/Bing/SearXNG 的 API 参数强行统一。
 */
public record WebSearchRequest(
        String query,
        Map<String, String> providerArguments
) {
    public WebSearchRequest {
        providerArguments = providerArguments == null ? Map.of() : Map.copyOf(providerArguments);
    }
}
