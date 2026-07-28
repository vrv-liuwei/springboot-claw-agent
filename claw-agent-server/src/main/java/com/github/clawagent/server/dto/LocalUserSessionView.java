package com.github.clawagent.server.dto;

import java.time.Instant;

public record LocalUserSessionView(
        String sessionId,
        String userId,
        String username,
        String displayName,
        String role,
        String status,
        String tokenPrefix,
        Instant createdAt,
        Instant expiresAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
}
