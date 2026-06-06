package com.github.clawagent.core;

import java.util.Map;

/**
 * 知识库检索命中的 chunk。
 *
 * @param documentId 命中文档 ID。
 * @param chunkId 命中 chunk ID。
 * @param userId chunk 所属用户 ID，用于校验检索结果没有跨用户串库。
 * @param documentName 命中文档名称，用于模型上下文来源和管理台展示。
 * @param chunkNo chunk 在文档中的序号。
 * @param text chunk 文本内容，只包含检索命中的片段，不是完整文档正文。
 * @param score 综合得分；hybrid 模式下是融合后的排序分。
 * @param provider 返回该结果的知识库 provider。
 * @param metadata chunk 或 provider 返回的轻量扩展元信息。
 */
public record KnowledgeSearchResult(
        String documentId,
        String chunkId,
        String userId,
        String documentName,
        int chunkNo,
        String text,
        double score,
        String provider,
        Map<String, String> metadata
) {
    public KnowledgeSearchResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
