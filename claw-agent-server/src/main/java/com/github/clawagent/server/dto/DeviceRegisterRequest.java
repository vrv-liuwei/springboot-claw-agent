package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 本地设备登记请求。
 * metadata 只保存设备环境说明，不保存系统凭证或访问 token。
 */
public record DeviceRegisterRequest(
        String name,
        String type,
        String permissionMode,
        List<String> approvedToolIds,
        Map<String, String> metadata
) {
}
