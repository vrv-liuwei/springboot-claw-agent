package com.github.clawagent.spi;

import java.util.List;
import java.util.Map;

/**
 * Web 搜索厂商适配接口。
 * Runtime 和 Toolkit 只依赖统一搜索语义，具体 API 参数由各 Provider 自己映射。
 */
public interface WebSearchProvider {
    /**
     * Provider 标识，例如 bocha、bing、searxng。
     */
    String id();

    /**
     * 当前 Provider 暴露给 Agent 的专属参数。
     * 例如 Bocha 可以暴露 count/freshness/summary，Bing 后续可以暴露 market/safeSearch 等自己的字段。
     */
    default Map<String, Object> inputProperties() {
        return Map.of();
    }

    /**
     * 当前 Provider 除 query 外额外必填的参数。
     */
    default List<String> requiredArguments() {
        return List.of();
    }

    /**
     * 执行搜索并返回统一结果。
     */
    WebSearchResponse search(WebSearchRequest request);
}
