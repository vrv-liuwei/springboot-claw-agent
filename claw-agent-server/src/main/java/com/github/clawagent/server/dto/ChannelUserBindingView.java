package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 通道外部用户绑定视图。
 * 用于审计“哪个 IM 用户最终以哪个本地用户策略执行任务”。
 */
public record ChannelUserBindingView(
        String id,
        String channelId,
        String externalUserId,
        String externalUsername,
        String localUserId,
        String localUsername,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> metadata
) {
}
