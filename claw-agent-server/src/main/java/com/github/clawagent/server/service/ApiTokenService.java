package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import com.github.clawagent.server.dto.ApiTokenCreateResponse;
import com.github.clawagent.server.dto.ApiTokenView;
import com.github.clawagent.spi.ApiTokenRecord;
import com.github.clawagent.spi.ApiTokenStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 本地 API Token 管理服务。
 * 持久化由 ApiTokenStore 注入，业务层不关心底层是 SQLite、远端身份源还是测试替身。
 */
public class ApiTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenStore store;

    public ApiTokenService(ApiTokenStore store) {
        this.store = store;
    }

    public synchronized List<ApiTokenView> list() {
        return readRecords().stream().map(this::toView).toList();
    }

    public synchronized ApiTokenCreateResponse create(ApiTokenCreateRequest request) {
        String token = generateToken();
        String tokenPrefix = token.substring(0, Math.min(token.length(), 16));
        ApiTokenRecord record = new ApiTokenRecord(
                UUID.randomUUID().toString(),
                firstNonBlank(request == null ? null : request.name(), "API Token"),
                tokenPrefix,
                sha256(token),
                token,
                "active",
                firstNonBlank(request == null ? null : request.ownerUserId(), ""),
                firstNonBlank(request == null ? null : request.ownerUsername(), ""),
                normalizePermissionMode(request == null ? null : request.permissionMode()),
                normalizeToolIds(request == null ? null : request.approvedToolIds()),
                normalizeScopes(request == null ? null : request.scopes()),
                Instant.now(),
                request == null ? null : request.expiresAt(),
                null,
                null,
                0L,
                null,
                null,
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()));
        List<ApiTokenRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return new ApiTokenCreateResponse(toView(record), token);
    }

    public synchronized ApiTokenView delete(String tokenId) {
        List<ApiTokenRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            ApiTokenRecord record = records.get(i);
            if (record.id().equals(tokenId)) {
                // 管理台删除 Token 时直接移出列表，避免“撤销后还显示 active/旧数据”的误解。
                records.remove(i);
                writeRecords(records);
                return toView(record);
            }
        }
        throw new IllegalArgumentException("API Token 不存在：" + tokenId);
    }

    public synchronized boolean verify(String token) {
        return verify(token, false);
    }

    public synchronized boolean verifyAndTouch(String token) {
        return verify(token, true, null, null);
    }

    public synchronized boolean verifyAndTouch(String token, String method, String path) {
        return verify(token, true, method, path);
    }

    public synchronized Optional<ApiTokenView> authenticateAndTouch(String token, String method, String path) {
        return authenticate(token, true, method, path);
    }

    private boolean verify(String token, boolean touchLastUsedAt) {
        return verify(token, touchLastUsedAt, null, null);
    }

    private boolean verify(String token, boolean touchLastUsedAt, String method, String path) {
        return authenticate(token, touchLastUsedAt, method, path).isPresent();
    }

    private Optional<ApiTokenView> authenticate(String token, boolean touchLastUsedAt, String method, String path) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256(token.trim());
        List<ApiTokenRecord> records = new ArrayList<>(readRecords());
        Instant now = Instant.now();
        for (int i = 0; i < records.size(); i++) {
            ApiTokenRecord record = records.get(i);
            if ("active".equals(record.status()) && !isExpired(record, now) && hashEquals(record.tokenHash(), hash)) {
                ApiTokenRecord current = record;
                if (touchLastUsedAt) {
                    // 鉴权通过时记录最小使用审计，后续审计页可继续扩展为明细事件。
                    current = new ApiTokenRecord(record.id(), record.name(), record.tokenPrefix(), record.tokenHash(), record.token(),
                            record.status(), record.ownerUserId(), record.ownerUsername(), record.permissionMode(),
                            record.approvedToolIds(), record.scopes(), record.createdAt(), record.expiresAt(), record.revokedAt(), now,
                            safeUsageCount(record.usageCount()) + 1,
                            firstNonBlank(method, record.lastUsedMethod()),
                            firstNonBlank(path, record.lastUsedPath()),
                            record.metadata());
                    records.set(i, current);
                    writeRecords(records);
                }
                return Optional.of(toView(current));
            }
        }
        return Optional.empty();
    }

    private List<ApiTokenRecord> readRecords() {
        return store.read();
    }

    private void writeRecords(List<ApiTokenRecord> records) {
        store.write(records);
    }

    private ApiTokenView toView(ApiTokenRecord record) {
        return new ApiTokenView(record.id(), record.name(), record.tokenPrefix(), record.token(), record.status(),
                safeString(record.ownerUserId()), safeString(record.ownerUsername()), normalizePermissionMode(record.permissionMode()),
                normalizeToolIds(record.approvedToolIds()), normalizeScopes(record.scopes()),
                record.createdAt(), record.expiresAt(), record.revokedAt(), record.lastUsedAt(), safeUsageCount(record.usageCount()),
                record.lastUsedMethod(), record.lastUsedPath(), record.metadata());
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "cla_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
            throw new IllegalStateException("API Token 哈希失败", e);
        }
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String safeString(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizePermissionMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("full-access".equals(value) || "full".equals(value) || "auto".equals(value)
                || "custom".equals(value) || "ask".equals(value) || "read-only".equals(value)) {
            return value;
        }
        return value.isBlank() ? "" : "ask";
    }

    private List<String> normalizeToolIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(ids::add);
        return Collections.unmodifiableList(new ArrayList<>(ids));
    }

    private List<String> normalizeScopes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(scopes::add);
        return Collections.unmodifiableList(new ArrayList<>(scopes));
    }

    private boolean hashEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private long safeUsageCount(Long usageCount) {
        return usageCount == null ? 0L : usageCount;
    }

    private boolean isExpired(ApiTokenRecord record, Instant now) {
        return record.expiresAt() != null && !record.expiresAt().isAfter(now);
    }
}
