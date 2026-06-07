package com.github.clawagent.core;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆命中审计记录。
 *
 * @param id 命中记录 ID。
 * @param userId 用户 ID。
 * @param sessionId 会话 ID。
 * @param taskId 任务 ID。
 * @param itemId 记忆条目 ID。
 * @param chunkId 记忆分块 ID。
 * @param score 命中分数。
 * @param createdAt 命中时间。
 * @param metadata 轻量扩展元信息。
 */
public record MemoryHitLog(
        String id,
        String userId,
        String sessionId,
        String taskId,
        String itemId,
        String chunkId,
        double score,
        Instant createdAt,
        Map<String, String> metadata
) {
}
