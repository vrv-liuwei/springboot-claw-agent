package com.github.clawagent.server.dto;

import com.github.clawagent.core.KnowledgeSearchResult;

import java.util.List;

/**
 * 知识库检索 HTTP 响应。
 *
 * @param hits 检索命中的 chunk 列表。
 */
public record KnowledgeSearchResponse(
        List<KnowledgeSearchResult> hits
) {
}
