package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.Map;

/**
 * API Token 列表视图。
 * 不包含 tokenHash，避免管理台列表泄露可验证密钥材料。
 */
public record ApiTokenView(
        String id,
        String name,
        String tokenPrefix,
        String status,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt,
        Long usageCount,
        String lastUsedMethod,
        String lastUsedPath,
        Map<String, String> metadata
) {
}
