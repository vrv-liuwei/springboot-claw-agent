package com.github.clawagent.server.dto;

import java.time.Instant;

public record TaskAuditResumeView(
        boolean resumed,
        String resumeFromTaskId,
        String resumeFromStatus,
        String todoId,
        String todoOrder,
        String todoTitle,
        String todoStatus,
        String resumeMode,
        String resumeInstruction,
        String checkpoint,
        Instant requestedAt,
        Instant checkpointAt
) {
}
