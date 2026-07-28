package com.github.clawagent.spi;

import java.time.Instant;

/**
 * 本地登录会话持久化记录。
 * 只保存 token hash 和前缀，明文 session token 只允许出现在登录响应里。
 */
public record LocalUserSessionRecord(
        String id,
        String userId,
        String username,
        String displayName,
        String role,
        String tokenPrefix,
        String tokenHash,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
}
