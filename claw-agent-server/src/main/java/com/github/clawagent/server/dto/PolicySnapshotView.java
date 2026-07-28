package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 当前本地策略解释快照。
 * 后续企业级组织、角色和通道账号策略扩展时，可以继续在这里补充优先级和命中原因。
 */
public record PolicySnapshotView(
        ApprovalPolicyView approval,
        PermissionPolicyView permission,
        List<PolicyResolutionLayerView> resolutionOrder,
        List<String> effectiveRules,
        List<String> pendingEnhancements
) {
}
