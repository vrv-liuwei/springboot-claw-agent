package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

/**
 * 创建设备配对码的请求。
 * 配对码用于桌面壳、浏览器扩展或外部网关在本机完成一次性绑定。
 */
public record DevicePairingCreateRequest(
        String name,
        String type,
        Integer ttlSeconds,
        String permissionMode,
        List<String> approvedToolIds,
        Map<String, String> metadata
) {
}
