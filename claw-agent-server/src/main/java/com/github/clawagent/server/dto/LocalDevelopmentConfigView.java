package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 本地开发任务配置视图。
 *
 * @param workspaceRoot 默认工作区目录。
 * @param defaultShell 默认 Shell 名称。
 * @param permissionMode 本地权限模式。
 * @param approvedToolIds custom 权限模式下默认批准的工具 ID。
 * @param allowedRoots execute/filesystem 允许访问根目录。
 * @param recentProjects 用户确认过的最近项目目录。
 * @param testCommands 手动配置的全局验证命令，开发摘要会在项目命令之后展示。
 * @param projectTestCommands 按项目目录配置的验证命令。
 * @param ignorePatterns 批量搜索、审查和摘要默认忽略的路径模式。
 * @param sensitivePathPatterns 敏感路径模式；filesystem 拦截，execute 命中后升高风险。
 */
public record LocalDevelopmentConfigView(
        String workspaceRoot,
        String defaultShell,
        String permissionMode,
        List<String> approvedToolIds,
        List<String> allowedRoots,
        List<String> recentProjects,
        List<String> testCommands,
        Map<String, List<String>> projectTestCommands,
        List<String> ignorePatterns,
        List<String> sensitivePathPatterns
) {
}
