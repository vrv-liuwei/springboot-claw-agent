package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 管理台手动出站测试请求。
 */
public record ChannelOutboundTestRequest(
        String externalConversationId,
        String externalUserId,
        String text,
        Map<String, String> metadata
) {
}
