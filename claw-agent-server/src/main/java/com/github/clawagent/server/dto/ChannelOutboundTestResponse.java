package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 管理台手动出站测试结果。
 */
public record ChannelOutboundTestResponse(
        String channelId,
        String channelType,
        boolean sent,
        String status,
        String message,
        Map<String, String> details
) {
}
