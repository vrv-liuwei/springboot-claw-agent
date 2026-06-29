package com.github.clawagent.server.dto;

import java.time.Instant;

public record TaskAuditApprovalView(
        String stepId,
        String toolId,
        String approvalKey,
        String status,
        String reason,
        Instant requestedAt,
        Instant approvedAt
) {
}
