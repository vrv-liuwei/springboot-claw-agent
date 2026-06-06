package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TodoItem 表示一次复杂任务拆解后的单个待办项。
 * 它绑定 session/task，便于后续把 todo 和 checkpoint/resume 串起来。
 *
 * @param id Todo ID。
 * @param sessionId Todo 所属会话 ID。
 * @param taskId Todo 所属任务 ID。
 * @param itemOrder Todo 在计划中的顺序。
 * @param title Todo 标题。
 * @param description Todo 详细描述。
 * @param status Todo 状态，例如 pending、running、completed、failed。
 * @param metadata Todo 轻量扩展元信息。
 * @param createdAt 创建时间。
 * @param updatedAt 最后更新时间。
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
