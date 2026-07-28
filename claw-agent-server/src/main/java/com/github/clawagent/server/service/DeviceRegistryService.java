package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.DevicePairRequest;
import com.github.clawagent.server.dto.DevicePairResponse;
import com.github.clawagent.server.dto.DevicePairingCodeResponse;
import com.github.clawagent.server.dto.DevicePairingCreateRequest;
import com.github.clawagent.server.dto.DevicePermissionUpdateRequest;
import com.github.clawagent.server.dto.DeviceRegisterRequest;
import com.github.clawagent.server.dto.DeviceSecretVerifyRequest;
import com.github.clawagent.server.dto.DeviceSecretVerifyResponse;
import com.github.clawagent.server.dto.DeviceSecretRotateResponse;
import com.github.clawagent.server.dto.DeviceUserBindRequest;
import com.github.clawagent.server.dto.DeviceView;
import com.github.clawagent.spi.DeviceRecord;
import com.github.clawagent.spi.DeviceStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 本地设备登记服务。
 * 设备注册、配对码和密钥只依赖 DeviceStore，默认 SQLite，后续桌面壳或企业设备目录可替换 Store。
 */
public class DeviceRegistryService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DEFAULT_PAIRING_TTL_SECONDS = 600;

    private final DeviceStore store;

    public DeviceRegistryService(DeviceStore store) {
        this.store = store;
    }

    public synchronized List<DeviceView> list() {
        return readRecords().stream().map(this::toView).toList();
    }

    public synchronized Optional<DeviceView> findActive(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return Optional.empty();
        }
        return readRecords().stream()
                .filter(record -> record.id().equals(deviceId.trim()))
                .filter(record -> "active".equalsIgnoreCase(record.status()))
                .findFirst()
                .map(this::toView);
    }

    public synchronized DeviceView register(DeviceRegisterRequest request) {
        Instant now = Instant.now();
        DeviceRecord record = new DeviceRecord(
                UUID.randomUUID().toString(),
                firstNonBlank(request == null ? null : request.name(), "Local Device"),
                firstNonBlank(request == null ? null : request.type(), "local"),
                "active",
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                normalizePermissionMode(request == null ? null : request.permissionMode()),
                normalizeApprovedToolIds(request == null ? null : request.approvedToolIds()),
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()),
                null,
                null);
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return toView(record);
    }

    public synchronized DevicePairingCodeResponse createPairingCode(DevicePairingCreateRequest request) {
        Instant now = Instant.now();
        int ttlSeconds = clampPairingTtl(request == null ? null : request.ttlSeconds());
        String code = generatePairingCode();
        DeviceRecord record = new DeviceRecord(
                UUID.randomUUID().toString(),
                firstNonBlank(request == null ? null : request.name(), "Pairing Device"),
                firstNonBlank(request == null ? null : request.type(), "local"),
                "pairing",
                now,
                now,
                null,
                null,
                hashSecret(code),
                now.plusSeconds(ttlSeconds),
                null,
                null,
                normalizePermissionMode(request == null ? null : request.permissionMode()),
                normalizeApprovedToolIds(request == null ? null : request.approvedToolIds()),
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()),
                null,
                null);
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return new DevicePairingCodeResponse(toView(record), code, record.pairingCodeExpiresAt());
    }

    public synchronized DevicePairResponse pair(DevicePairRequest request) {
        String code = request == null ? "" : firstNonBlank(request.code(), "");
        if (code.isBlank()) {
            throw new IllegalArgumentException("配对码不能为空");
        }
        Instant now = Instant.now();
        String codeHash = hashSecret(code);
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            DeviceRecord record = records.get(i);
            if (isUsablePairingRecord(record, now) && hashEquals(record.pairingCodeHash(), codeHash)) {
                String deviceSecret = generateDeviceSecret();
                DeviceRecord updated = new DeviceRecord(
                        record.id(), record.name(), record.type(), "active",
                        record.firstSeenAt(), now, null, now,
                        null, null, hashSecret(deviceSecret), secretPrefix(deviceSecret),
                        normalizePermissionMode(record.permissionMode()), normalizeApprovedToolIds(record.approvedToolIds()),
                        mergeMetadata(record.metadata(), request.metadata()),
                        record.boundUserId(), record.boundUsername());
                records.set(i, updated);
                writeRecords(records);
                return new DevicePairResponse(toView(updated), deviceSecret);
            }
        }
        throw new IllegalArgumentException("配对码无效或已过期");
    }

    public synchronized DeviceView heartbeat(String deviceId) {
        return updateRecord(deviceId, record -> new DeviceRecord(
                record.id(),
                record.name(),
                record.type(),
                record.status(),
                record.firstSeenAt(),
                Instant.now(),
                record.revokedAt(),
                record.pairedAt(),
                record.pairingCodeHash(),
                record.pairingCodeExpiresAt(),
                record.deviceSecretHash(),
                record.deviceSecretPrefix(),
                record.permissionMode(),
                record.approvedToolIds(),
                record.metadata(),
                record.boundUserId(),
                record.boundUsername()));
    }

    public synchronized DeviceView revoke(String deviceId) {
        return updateRecord(deviceId, record -> new DeviceRecord(
                record.id(),
                record.name(),
                record.type(),
                "revoked",
                record.firstSeenAt(),
                record.lastSeenAt(),
                Instant.now(),
                record.pairedAt(),
                null,
                null,
                record.deviceSecretHash(),
                record.deviceSecretPrefix(),
                record.permissionMode(),
                record.approvedToolIds(),
                record.metadata(),
                record.boundUserId(),
                record.boundUsername()));
    }

    public synchronized DeviceSecretVerifyResponse verifySecret(String deviceId, DeviceSecretVerifyRequest request) {
        String secret = request == null ? "" : request.deviceSecret();
        return readRecords().stream()
                .filter(record -> record.id().equals(deviceId))
                .findFirst()
                .map(record -> {
                    boolean verified = "active".equals(record.status())
                            && hasStoredSecret(record.deviceSecretHash())
                            && secret != null
                            && !secret.isBlank()
                            && hashEquals(record.deviceSecretHash(), hashSecret(secret));
                    return new DeviceSecretVerifyResponse(deviceId, verified, record.status());
                })
                .orElseGet(() -> new DeviceSecretVerifyResponse(deviceId, false, "not_found"));
    }

    public synchronized Optional<DeviceView> authenticateSecret(String deviceId, String deviceSecret) {
        if (deviceId == null || deviceId.isBlank() || deviceSecret == null || deviceSecret.isBlank()) {
            return Optional.empty();
        }
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            DeviceRecord record = records.get(i);
            if (!record.id().equals(deviceId.trim())) {
                continue;
            }
            boolean verified = "active".equals(record.status())
                    && hasStoredSecret(record.deviceSecretHash())
                    && hashEquals(record.deviceSecretHash(), hashSecret(deviceSecret));
            if (!verified) {
                return Optional.empty();
            }
            // 设备凭证通过鉴权链使用时顺手刷新 lastSeenAt，便于管理台判断设备最近活跃状态。
            DeviceRecord touched = new DeviceRecord(
                    record.id(), record.name(), record.type(), record.status(),
                    record.firstSeenAt(), Instant.now(), record.revokedAt(), record.pairedAt(),
                    record.pairingCodeHash(), record.pairingCodeExpiresAt(), record.deviceSecretHash(), record.deviceSecretPrefix(),
                    record.permissionMode(), record.approvedToolIds(), record.metadata(),
                    record.boundUserId(), record.boundUsername());
            records.set(i, touched);
            writeRecords(records);
            return Optional.of(toView(touched));
        }
        return Optional.empty();
    }

    public synchronized DeviceSecretRotateResponse rotateSecret(String deviceId) {
        String deviceSecret = generateDeviceSecret();
        DeviceView view = updateRecord(deviceId, record -> {
            if (!"active".equals(record.status())) {
                throw new IllegalStateException("只有 active 设备允许轮换密钥：" + deviceId);
            }
            // 轮换时直接替换哈希，旧密钥立刻失效；明文仍只在本次响应中返回。
            return new DeviceRecord(
                    record.id(), record.name(), record.type(), record.status(),
                    record.firstSeenAt(), Instant.now(), record.revokedAt(), record.pairedAt(),
                    record.pairingCodeHash(), record.pairingCodeExpiresAt(), hashSecret(deviceSecret), secretPrefix(deviceSecret),
                    record.permissionMode(), record.approvedToolIds(), record.metadata(),
                    record.boundUserId(), record.boundUsername());
        });
        return new DeviceSecretRotateResponse(view, deviceSecret);
    }

    public synchronized DeviceView bindUser(String deviceId, DeviceUserBindRequest request) {
        return updateRecord(deviceId, record -> new DeviceRecord(
                record.id(), record.name(), record.type(), record.status(),
                record.firstSeenAt(), record.lastSeenAt(), record.revokedAt(), record.pairedAt(),
                record.pairingCodeHash(), record.pairingCodeExpiresAt(), record.deviceSecretHash(), record.deviceSecretPrefix(),
                record.permissionMode(), record.approvedToolIds(), record.metadata(),
                emptyToNull(request == null ? null : request.userId()),
                emptyToNull(request == null ? null : request.username())));
    }

    public synchronized DeviceView updatePermissions(String deviceId, DevicePermissionUpdateRequest request) {
        return updateRecord(deviceId, record -> new DeviceRecord(
                record.id(), record.name(), record.type(), record.status(),
                record.firstSeenAt(), record.lastSeenAt(), record.revokedAt(), record.pairedAt(),
                record.pairingCodeHash(), record.pairingCodeExpiresAt(), record.deviceSecretHash(), record.deviceSecretPrefix(),
                normalizePermissionMode(request == null ? null : request.permissionMode()),
                normalizeApprovedToolIds(request == null ? null : request.approvedToolIds()),
                record.metadata(),
                record.boundUserId(),
                record.boundUsername()));
    }

    private DeviceView updateRecord(String deviceId, RecordUpdater updater) {
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            DeviceRecord record = records.get(i);
            if (record.id().equals(deviceId)) {
                DeviceRecord updated = updater.update(record);
                records.set(i, updated);
                writeRecords(records);
                return toView(updated);
            }
        }
        throw new IllegalArgumentException("设备不存在：" + deviceId);
    }

    private List<DeviceRecord> readRecords() {
        return store.read();
    }

    private void writeRecords(List<DeviceRecord> records) {
        store.write(records);
    }

    private DeviceView toView(DeviceRecord record) {
        return new DeviceView(record.id(), record.name(), record.type(), record.status(),
                record.firstSeenAt(), record.lastSeenAt(), record.revokedAt(), record.pairedAt(),
                record.pairingCodeExpiresAt(), record.deviceSecretPrefix(),
                normalizePermissionMode(record.permissionMode()), normalizeApprovedToolIds(record.approvedToolIds()),
                record.boundUserId(), record.boundUsername(),
                record.metadata() == null ? Map.of() : record.metadata());
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int clampPairingTtl(Integer ttlSeconds) {
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return DEFAULT_PAIRING_TTL_SECONDS;
        }
        return Math.min(Math.max(ttlSeconds, 60), 3600);
    }

    private String generatePairingCode() {
        // 六位数字方便人工输入；服务端只保存哈希，避免落盘明文配对码。
        return String.valueOf(100_000 + RANDOM.nextInt(900_000));
    }

    private String generateDeviceSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "cdv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("设备凭据哈希失败", e);
        }
    }

    private boolean hashEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasStoredSecret(String secretHash) {
        return secretHash != null && !secretHash.isBlank();
    }

    private boolean isUsablePairingRecord(DeviceRecord record, Instant now) {
        return "pairing".equals(record.status())
                && record.pairingCodeHash() != null
                && record.pairingCodeExpiresAt() != null
                && record.pairingCodeExpiresAt().isAfter(now);
    }

    private String secretPrefix(String secret) {
        return secret == null || secret.length() <= 10 ? secret : secret.substring(0, 10);
    }

    private String normalizePermissionMode(String permissionMode) {
        String value = firstNonBlank(permissionMode, "ask").toLowerCase();
        return switch (value) {
            case "auto", "custom", "full-access", "read-only" -> value;
            default -> "ask";
        };
    }

    private List<String> normalizeApprovedToolIds(List<String> approvedToolIds) {
        if (approvedToolIds == null || approvedToolIds.isEmpty()) {
            return List.of();
        }
        return approvedToolIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, String> mergeMetadata(Map<String, String> current, Map<String, String> incoming) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (incoming != null) {
            merged.putAll(incoming);
        }
        return merged;
    }

    private interface RecordUpdater {
        DeviceRecord update(DeviceRecord record);
    }

}
