package com.github.clawagent.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import com.github.clawagent.server.dto.ApiTokenCreateResponse;
import com.github.clawagent.server.dto.ApiTokenView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地 API Token 管理服务。
 * 当前只提供单机生成/撤销/列表，后续认证拦截器可以复用这里的哈希校验能力。
 */
public class ApiTokenService {
    private static final TypeReference<List<TokenRecord>> TOKEN_LIST_TYPE = new TypeReference<>() {};
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path storePath;

    public ApiTokenService(Path storePath) {
        this.storePath = storePath;
    }

    public synchronized List<ApiTokenView> list() {
        return readRecords().stream().map(this::toView).toList();
    }

    public synchronized ApiTokenCreateResponse create(ApiTokenCreateRequest request) {
        String token = generateToken();
        String tokenPrefix = token.substring(0, Math.min(token.length(), 16));
        TokenRecord record = new TokenRecord(
                UUID.randomUUID().toString(),
                firstNonBlank(request == null ? null : request.name(), "API Token"),
                tokenPrefix,
                sha256(token),
                "active",
                Instant.now(),
                null,
                null,
                0L,
                null,
                null,
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()));
        List<TokenRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return new ApiTokenCreateResponse(toView(record), token);
    }

    public synchronized ApiTokenView revoke(String tokenId) {
        List<TokenRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if (record.id().equals(tokenId)) {
                TokenRecord revoked = new TokenRecord(record.id(), record.name(), record.tokenPrefix(), record.tokenHash(),
                        "revoked", record.createdAt(), Instant.now(), record.lastUsedAt(), record.usageCount(),
                        record.lastUsedMethod(), record.lastUsedPath(), record.metadata());
                records.set(i, revoked);
                writeRecords(records);
                return toView(revoked);
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

    private boolean verify(String token, boolean touchLastUsedAt) {
        return verify(token, touchLastUsedAt, null, null);
    }

    private boolean verify(String token, boolean touchLastUsedAt, String method, String path) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256(token.trim());
        List<TokenRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            TokenRecord record = records.get(i);
            if ("active".equals(record.status()) && hashEquals(record.tokenHash(), hash)) {
                if (touchLastUsedAt) {
                    // 鉴权通过时记录最小使用审计，后续审计页可继续扩展为明细事件。
                    records.set(i, new TokenRecord(record.id(), record.name(), record.tokenPrefix(), record.tokenHash(),
                            record.status(), record.createdAt(), record.revokedAt(), Instant.now(),
                            safeUsageCount(record.usageCount()) + 1,
                            firstNonBlank(method, record.lastUsedMethod()),
                            firstNonBlank(path, record.lastUsedPath()),
                            record.metadata()));
                    writeRecords(records);
                }
                return true;
            }
        }
        return false;
    }

    private List<TokenRecord> readRecords() {
        if (!Files.exists(storePath)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(storePath, StandardCharsets.UTF_8), TOKEN_LIST_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("读取 API Token 配置失败：" + storePath, e);
        }
    }

    private void writeRecords(List<TokenRecord> records) {
        try {
            Files.createDirectories(storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), records);
        } catch (IOException e) {
            throw new IllegalStateException("保存 API Token 配置失败：" + storePath, e);
        }
    }

    private ApiTokenView toView(TokenRecord record) {
        return new ApiTokenView(record.id(), record.name(), record.tokenPrefix(), record.status(),
                record.createdAt(), record.revokedAt(), record.lastUsedAt(), safeUsageCount(record.usageCount()),
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

    private boolean hashEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private long safeUsageCount(Long usageCount) {
        return usageCount == null ? 0L : usageCount;
    }

    private record TokenRecord(
            String id,
            String name,
            String tokenPrefix,
            String tokenHash,
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
}
