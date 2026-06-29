package com.github.clawagent.server.dto;

import java.time.Instant;

public record TaskAuditToolView(
        String stepId,
        String toolId,
        String status,
        String riskLevel,
        String approvalMode,
        String todoTitle,
        String inputPreview,
        String outputPreview,
        String error,
        Long elapsedMs,
        Instant startedAt,
        Instant finishedAt
) {
}
