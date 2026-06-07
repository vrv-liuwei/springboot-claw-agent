package com.github.clawagent.core;

import java.util.Map;

/**
 * 记忆检索命中结果。
 *
 * @param itemId 记忆条目 ID。
 * @param chunkId 记忆分块 ID。
 * @param userId 用户 ID。
 * @param scopeType 命中的记忆范围。
 * @param scopeId 命中的范围 ID。
 * @param type 记忆类型。
 * @param status 记忆状态。
 * @param content 命中的文本片段。
 * @param score 混合检索后的排序分。
 * @param metadata 轻量扩展元信息。
 */
public record MemorySearchHit(
        String itemId,
        String chunkId,
        String userId,
        String scopeType,
        String scopeId,
        String type,
        String status,
        String content,
        double score,
        Map<String, String> metadata
) {
}
