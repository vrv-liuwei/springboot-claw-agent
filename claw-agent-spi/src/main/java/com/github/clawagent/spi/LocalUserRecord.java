package com.github.clawagent.spi;

import java.time.Instant;
import java.util.Map;

/**
 * 本地用户持久化记录。
 * 密码只保存 salt 和 hash，任何实现都不能持久化明文密码。
 */
public record LocalUserRecord(
        String id,
        String username,
        String displayName,
        String role,
        String status,
        Instant createdAt,
        Instant disabledAt,
        Instant lastPasswordChangedAt,
        String passwordSalt,
        String passwordHash,
        Map<String, String> metadata
) {
}
