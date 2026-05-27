package com.github.clawagent.spi;

/**
 * 单条搜索结果。
 */
public record WebSearchResult(
        String title,
        String url,
        String snippet,
        String summary,
        String source,
        String publishedAt,
        Double score
) {
}
