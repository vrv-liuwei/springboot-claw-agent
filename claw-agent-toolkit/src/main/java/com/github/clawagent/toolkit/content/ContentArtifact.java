package com.github.clawagent.toolkit.content;

import java.nio.file.Path;

/**
 * 本地内容缓存条目。
 * 用于把网页、搜索结果等长内容落到本地，并只把摘要和 artifactId 传给模型。
 */
public record ContentArtifact(
        String artifactId,
        String kind,
        String source,
        String contentType,
        Path directory,
        int originalChars,
        int readableChars,
        int summaryChars,
        int chunkCount,
        String summary
) {
}
