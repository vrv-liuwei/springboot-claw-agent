package com.github.clawagent.server.dto;

public record TaskAuditSummaryView(
        int toolCalls,
        int failedToolCalls,
        int approvalRequests,
        int approvalsGranted,
        int fileChanges,
        int rollbacks,
        int commands,
        int failedCommands,
        int securityWarnings,
        int events
) {
}
