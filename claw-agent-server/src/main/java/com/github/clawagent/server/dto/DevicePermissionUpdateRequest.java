package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 更新设备级权限绑定。
 * 设备维度策略会在任务入口合并进 toolPermissionMode/approvedToolIds，再由 ToolExecutionGuard 强制执行。
 */
public record DevicePermissionUpdateRequest(
        String permissionMode,
        List<String> approvedToolIds
) {
}
