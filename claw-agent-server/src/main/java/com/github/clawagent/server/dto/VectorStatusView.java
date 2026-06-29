package com.github.clawagent.server.dto;

/**
 * 向量化状态视图，用于知识库和长期记忆的索引覆盖情况展示。
 *
 * @param id 文档或记忆 ID。
 * @param name 文档名称或记忆摘要。
 * @param status 当前业务状态。
 * @param chunkCount 已解析的 chunk 数。
 * @param vectorCount 已生成的向量数。
 * @param vectorized 是否已经完成向量覆盖。
 */
public record VectorStatusView(
        String id,
        String name,
        String status,
        int chunkCount,
        int vectorCount,
        boolean vectorized
) {
}
