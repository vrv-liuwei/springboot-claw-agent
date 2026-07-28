package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API Token 列表视图。
 * 不包含 tokenHash；本地管理台需要复制完整 token，因此只返回明文 token，不返回可校验 hash。
 */
public record ApiTokenView(
        String id,
        String name,
        String tokenPrefix,
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
        Long usageCount,
        String lastUsedMethod,
        String lastUsedPath,
        Map<String, String> metadata
) {
}
