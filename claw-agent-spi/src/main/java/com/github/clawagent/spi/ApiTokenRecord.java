package com.github.clawagent.spi;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API Token 持久化记录。
 * token 明文用于本地管理台复制；鉴权仍以 hash 为准，审计日志不能记录明文。
 */
public record ApiTokenRecord(
        String id,
        String name,
        String tokenPrefix,
        String tokenHash,
        String token,
        String status,
        String ownerUserId,
        String ownerUsername,
        String permissionMode,
        List<String> approvedToolIds,
        List<String> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt,
        long usageCount,
        String lastUsedMethod,
        String lastUsedPath,
        Map<String, String> metadata
) {
}
