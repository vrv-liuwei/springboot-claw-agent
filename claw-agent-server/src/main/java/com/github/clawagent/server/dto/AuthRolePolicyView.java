package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 本地用户角色策略快照。
 * 该 DTO 只用于管理台查看当前生效配置，不直接参与鉴权或工具拦截。
 */
public record AuthRolePolicyView(
        boolean enabled,
        String permissionMode,
        String approvalMode,
        List<String> approvedToolIds
) {
}
