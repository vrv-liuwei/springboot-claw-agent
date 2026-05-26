package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TodoItem 表示一次复杂任务拆解后的单个待办项。
 * 它绑定 session/task，便于后续把 todo 和 checkpoint/resume 串起来。
 */
public record TodoItem(
        String id,
        String sessionId,
        String taskId,
        int itemOrder,
        String title,
        String description,
        String status,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {
    public TodoItem {
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }
}
