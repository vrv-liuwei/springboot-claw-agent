package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.LocalUserLoginResponse;
import com.github.clawagent.server.dto.LocalUserSessionView;
import com.github.clawagent.server.dto.LocalUserView;
import com.github.clawagent.spi.LocalUserSessionRecord;
import com.github.clawagent.spi.LocalUserSessionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地用户会话服务。
 * 会话 token 只在登录响应中返回一次，落盘文件只保存哈希和前缀，避免从配置文件恢复登录凭证。
 */
public class LocalUserSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);
    private static final String TOKEN_PREFIX = "clas_";

    private final LocalUserSessionStore store;
    private final LocalUserService userService;
    private final Duration ttl;

    public LocalUserSessionService(LocalUserSessionStore store, LocalUserService userService, Duration ttl) {
        this.store = store;
        this.userService = userService;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    }

    public synchronized LocalUserLoginResponse create(LocalUserView user) {
        if (user == null || user.id() == null || user.id().isBlank()) {
            throw new IllegalArgumentException("本地用户不能为空");
        }
        Instant now = Instant.now();
        String token = generateToken();
        LocalUserSessionRecord record = new LocalUserSessionRecord(
                UUID.randomUUID().toString(),
                user.id(),
                user.username(),
                user.displayName(),
                user.role(),
                token.substring(0, Math.min(token.length(), 16)),
                sha256(token),
                now,
                now.plus(ttl),
                null,
                null);
        List<LocalUserSessionRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return new LocalUserLoginResponse(user, toView(record, now), token);
    }

    public synchronized List<LocalUserSessionView> list() {
        Instant now = Instant.now();
        return readRecords().stream()
                .map(record -> toView(record, now))
                .toList();
    }

    public synchronized Optional<LocalUserLoginResponse> authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = sha256(sessionToken.trim());
        Instant now = Instant.now();
        List<LocalUserSessionRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            LocalUserSessionRecord record = records.get(i);
            if (!hashEquals(record.tokenHash(), tokenHash) || !isActive(record, now)) {
                continue;
            }
            Optional<LocalUserView> user = userService.findActive(record.userId());
            if (user.isEmpty()) {
                return Optional.empty();
            }
            // 会话使用时间单独 touch，方便后续设备/用户维度审计和自动清理空闲会话。
            LocalUserSessionRecord touched = new LocalUserSessionRecord(record.id(), record.userId(), record.username(),
                    record.displayName(), record.role(), record.tokenPrefix(), record.tokenHash(),
                    record.createdAt(), record.expiresAt(), record.revokedAt(), now);
            records.set(i, touched);
            writeRecords(records);
            return Optional.of(new LocalUserLoginResponse(user.get(), toView(touched, now), null));
        }
        return Optional.empty();
    }

    public synchronized Optional<LocalUserSessionView> revoke(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = sha256(sessionToken.trim());
        Instant now = Instant.now();
        List<LocalUserSessionRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            LocalUserSessionRecord record = records.get(i);
            if (!hashEquals(record.tokenHash(), tokenHash) || record.revokedAt() != null) {
                continue;
            }
            LocalUserSessionRecord revoked = new LocalUserSessionRecord(record.id(), record.userId(), record.username(),
                    record.displayName(), record.role(), record.tokenPrefix(), record.tokenHash(),
                    record.createdAt(), record.expiresAt(), now, record.lastUsedAt());
            records.set(i, revoked);
            writeRecords(records);
            return Optional.of(toView(revoked, now));
        }
        return Optional.empty();
    }

    public synchronized Optional<LocalUserSessionView> revokeBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        List<LocalUserSessionRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            LocalUserSessionRecord record = records.get(i);
            if (!sessionId.trim().equals(record.id()) || record.revokedAt() != null) {
                continue;
            }
            // 管理员撤销会话时只按 sessionId 命中，避免要求管理员拿到用户的 sessionToken 明文。
            LocalUserSessionRecord revoked = new LocalUserSessionRecord(record.id(), record.userId(), record.username(),
                    record.displayName(), record.role(), record.tokenPrefix(), record.tokenHash(),
                    record.createdAt(), record.expiresAt(), now, record.lastUsedAt());
            records.set(i, revoked);
            writeRecords(records);
            return Optional.of(toView(revoked, now));
        }
        return Optional.empty();
    }

    private boolean isActive(LocalUserSessionRecord record, Instant now) {
        return record.revokedAt() == null && record.expiresAt() != null && record.expiresAt().isAfter(now);
    }

    private LocalUserSessionView toView(LocalUserSessionRecord record, Instant now) {
        String status = record.revokedAt() != null ? "revoked"
                : record.expiresAt() == null || !record.expiresAt().isAfter(now) ? "expired" : "active";
        return new LocalUserSessionView(record.id(), record.userId(), record.username(), record.displayName(),
                record.role(), status, record.tokenPrefix(), record.createdAt(), record.expiresAt(),
                record.revokedAt(), record.lastUsedAt());
    }

    private List<LocalUserSessionRecord> readRecords() {
        return store.read();
    }

    private void writeRecords(List<LocalUserSessionRecord> records) {
        store.write(records);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("本地用户会话哈希失败", e);
        }
    }

    private boolean hashEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

}
