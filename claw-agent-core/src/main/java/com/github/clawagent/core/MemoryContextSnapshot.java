package com.github.clawagent.core;

import java.util.List;
import java.util.Map;

/**
 * 单轮模型请求的记忆上下文快照。
 *
 * @param userId 用户 ID。
 * @param sessionId 会话 ID。
 * @param taskId 任务 ID。
 * @param context 已按预算拼好的模型上下文。
 * @param hits 本轮检索命中的长期记忆。
 * @param metadata 轻量扩展元信息。
 */
public record MemoryContextSnapshot(
        String userId,
        String sessionId,
        String taskId,
        String context,
        List<MemorySearchHit> hits,
        Map<String, String> metadata
) {
}
