package com.github.clawagent.core;

import java.util.Map;

/**
 * 管理台创建或更新记忆的请求。
 *
 * @param id 记忆 ID；新增时可为空。
 * @param userId 用户 ID。
 * @param scopeType 记忆范围。
 * @param scopeId 记忆范围 ID。
 * @param type 记忆类型。
 * @param status 记忆状态。
 * @param content 记忆正文。
 * @param summary 记忆摘要。
 * @param sourceSessionId 来源会话 ID。
 * @param sourceTaskId 来源任务 ID。
 * @param importance 重要性评分。
 * @param confidence 置信度评分。
 * @param metadata 轻量扩展元信息。
 */
public record MemoryUpsertRequest(
        String id,
        String userId,
        String scopeType,
        String scopeId,
        String type,
        String status,
        String content,
        String summary,
        String sourceSessionId,
        String sourceTaskId,
        Double importance,
        Double confidence,
        Map<String, String> metadata
) {
}
