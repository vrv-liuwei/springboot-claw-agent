package com.github.clawagent.server.dto;

import java.util.List;

public record TaskAuditView(
        String taskId,
        String sessionId,
        String status,
        String input,
        TaskAuditSummaryView summary,
        TaskAuditResumeView resume,
        List<TaskAuditToolView> tools,
        List<TaskAuditApprovalView> approvals,
        List<FileChangeView> fileChanges,
        List<CommandRunView> commands,
        List<TaskAuditTimelineView> timeline
) {
}
