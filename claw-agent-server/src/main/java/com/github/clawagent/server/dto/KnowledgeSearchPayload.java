package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 知识库检索 HTTP 请求。
 *
 * @param userId 当前用户 ID，用于知识库检索隔离。
 * @param query 检索关键词或自然语言问题。
 * @param documentIds 限定检索的文档 ID；为空时按当前用户全库检索。
 * @param mode 检索模式，例如 keyword、vector、hybrid。
 * @param topK 返回 chunk 数量上限。
 */
public record KnowledgeSearchPayload(
        String userId,
        String query,
        List<String> documentIds,
        String mode,
        Integer topK
) {
}
