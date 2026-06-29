package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 本地权限策略快照。
 * allowed roots 和敏感路径仍由 execute/filesystem 的成熟校验链路最终执行。
 */
public record PermissionPolicyView(
        List<String> allowedRoots,
        List<String> sensitivePathPatterns,
        String defaultCwd,
        String source
) {
}
