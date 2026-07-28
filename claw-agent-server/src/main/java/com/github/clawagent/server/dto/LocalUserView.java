package com.github.clawagent.server.dto;

import java.time.Instant;
import java.util.Map;

public record LocalUserView(
        String id,
        String username,
        String displayName,
        String role,
        String status,
        Instant createdAt,
        Instant disabledAt,
        Instant lastPasswordChangedAt,
        Map<String, String> metadata
) {
}
