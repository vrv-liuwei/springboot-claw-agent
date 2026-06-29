package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 当前本地策略解释快照。
 * 后续 Channel/User/Agent 维度策略合并时，可以继续在这里补充优先级和命中原因。
 */
public record PolicySnapshotView(
        ApprovalPolicyView approval,
        PermissionPolicyView permission,
        List<PolicyResolutionLayerView> resolutionOrder,
        List<String> effectiveRules,
        List<String> pendingEnhancements
) {
}
