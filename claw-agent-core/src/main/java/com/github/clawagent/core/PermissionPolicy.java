package com.github.clawagent.core;

import java.util.List;

/**
 * 本地权限策略。
 * 目前承接 allowed roots 和敏感路径规则，execute/filesystem 工具继续复用现有成熟校验逻辑。
 *
 * @param allowedRoots 允许访问的工作目录根。
 * @param sensitivePathPatterns 敏感路径匹配规则。
 * @param defaultCwd 默认工作目录。
 */
public record PermissionPolicy(
        List<String> allowedRoots,
        List<String> sensitivePathPatterns,
        String defaultCwd
) {
    public PermissionPolicy {
        allowedRoots = allowedRoots == null ? List.of() : List.copyOf(allowedRoots);
        sensitivePathPatterns = sensitivePathPatterns == null ? List.of() : List.copyOf(sensitivePathPatterns);
        defaultCwd = defaultCwd == null ? "" : defaultCwd;
    }
}
