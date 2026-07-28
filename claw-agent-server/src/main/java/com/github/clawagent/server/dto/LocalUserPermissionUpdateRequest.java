package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 更新本地用户维度的工具权限绑定。
 * 这些字段会保存到用户 metadata，并在任务入口合并进 toolPermissionMode/approvedToolIds。
 */
public record LocalUserPermissionUpdateRequest(
        String permissionMode,
        List<String> approvedToolIds
) {
}
