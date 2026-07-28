package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * 执行已批准计划的请求。
 */
public record PlanRunRequest(
        String channelId,
        String userId,
        Map<String, String> metadata
) {
}
