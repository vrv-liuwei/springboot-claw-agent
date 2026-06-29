package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 审批和本地权限策略的专用保存请求。
 * 与模型配置分开，避免前端只修改权限时误触模型、记忆或成本配置。
 */
public record PolicyConfigUpdate(
        String permissionMode,
        List<String> approvedToolIds,
        String workspaceRoot,
        String defaultShell,
        List<String> allowedRoots,
        List<String> sensitivePathPatterns
) {
}
