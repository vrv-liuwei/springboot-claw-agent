package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 审批策略快照。
 * 这是给管理台展示的解释视图，不作为新的配置写入源。
 */
public record ApprovalPolicyView(
        String mode,
        List<String> approvedToolIds,
        boolean autoApprovesHighRisk,
        boolean fullAccess,
        boolean requiresApprovalForMediumOrUnknown,
        String source,
        String scope,
        String resolutionOrder,
        String overrideReason,
        List<String> conflictNotes
) {
}
