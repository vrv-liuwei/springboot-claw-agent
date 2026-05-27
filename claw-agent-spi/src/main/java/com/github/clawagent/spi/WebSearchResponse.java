package com.github.clawagent.spi;

import java.util.List;

/**
 * Provider 返回的统一搜索响应。
 */
public record WebSearchResponse(
        String provider,
        String query,
        long elapsedMs,
        List<WebSearchResult> results
) {
}
