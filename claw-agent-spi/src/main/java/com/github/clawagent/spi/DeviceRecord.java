package com.github.clawagent.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 设备登记持久化记录。
 * 配对码和设备密钥只保存 hash/前缀，Store 实现不得持久化明文凭据。
 */
public record DeviceRecord(
        String id,
        String name,
        String type,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        Instant revokedAt,
        Instant pairedAt,
        String pairingCodeHash,
        Instant pairingCodeExpiresAt,
        String deviceSecretHash,
        String deviceSecretPrefix,
        String permissionMode,
        List<String> approvedToolIds,
        Map<String, String> metadata,
        String boundUserId,
        String boundUsername
) {
}
