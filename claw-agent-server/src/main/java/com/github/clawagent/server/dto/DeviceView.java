package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 设备管理视图。
 * 用于展示哪些本地客户端、桌面壳或外部接入端曾连接过主服务。
 */
public record DeviceView(
        String id,
        String name,
        String type,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant revokedAt,
        Instant pairedAt,
        Instant pairingCodeExpiresAt,
        String deviceSecretPrefix,
        String permissionMode,
        List<String> approvedToolIds,
        String boundUserId,
        String boundUsername,
        Map<String, String> metadata
) {
}
