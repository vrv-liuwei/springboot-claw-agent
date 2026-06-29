package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 自动化任务创建/更新请求。
 */
public record AutomationUpsertRequest(
        String name,
        String prompt,
        String sessionId,
        String channelId,
        String userId,
        String scheduleType,
        String cronExpression,
        Long intervalSeconds,
        String timezone,
        Instant nextRunAt,
        String status,
        Map<String, String> metadata
) {
}
