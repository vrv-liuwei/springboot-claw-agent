package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创建会话的请求对象。
 * 嵌入式业务也可以直接复用该对象创建受控 session。
 *
 * @param title 会话标题。
 * @param channelId 会话来源渠道，例如 webui、automation、api。
 * @param userId 会话所属用户 ID。
 * @param workspaceId App 工作区 ID。
 * @param metadata 会话轻量扩展元信息。
 */
public record SessionCreateRequest(String title, String channelId, String userId, String workspaceId, Map<String, String> metadata) {
    public SessionCreateRequest(String title, String channelId, String userId, Map<String, String> metadata) {
        this(title, channelId, userId, null, metadata);
    }

    public SessionCreateRequest {
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        if (workspaceId != null && !workspaceId.isBlank()) {
            metadata.putIfAbsent("workspaceId", workspaceId);
        }
    }
}
