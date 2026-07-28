package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 创建计划草稿的请求。
 */
public record PlanCreateRequest(
        String input,
        String sessionId,
        String channelId,
        String userId,
        String mode,
        String templateId,
        Map<String, String> metadata
) {
}
