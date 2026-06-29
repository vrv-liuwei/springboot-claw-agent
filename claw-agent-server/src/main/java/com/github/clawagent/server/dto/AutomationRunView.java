package com.github.clawagent.server.dto;

import java.time.Instant;

/**
 * 自动化运行记录摘要。
 */
public record AutomationRunView(
        String id,
        String automationId,
        String taskId,
        String status,
        Instant startedAt,
        Instant finishedAt,
        String error,
        Long elapsedMs,
        Integer tokenCalls,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Integer toolCalls,
        Integer failedToolCalls
) {
}
