package com.github.clawagent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Channel 接入配置。
 * 这是飞书、钉钉、通用 API、WebUI 等入口共用的领域对象，不能放在 server 层。
 */
public record ChannelDefinition(
        String id,
        String name,
        String type,
        boolean enabled,
        String approvalMode,
        List<String> approvedToolIds,
        String inboundPath,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public ChannelDefinition withTimestamps(Instant createdAt, Instant updatedAt) {
        return new ChannelDefinition(id, name, type, enabled, approvalMode,
                approvedToolIds, inboundPath, metadata, createdAt, updatedAt);
    }
}
