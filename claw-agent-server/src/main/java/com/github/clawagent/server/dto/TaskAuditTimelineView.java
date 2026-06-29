package com.github.clawagent.server.dto;

import java.time.Instant;

public record TaskAuditTimelineView(
        String id,
        String type,
        String level,
        String message,
        String toolId,
        String stepId,
        String todoTitle,
        Instant createdAt
) {
}
