package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.LocalUserPasswordChangeRequest;
import com.github.clawagent.server.dto.LocalUserPermissionUpdateRequest;
import com.github.clawagent.server.dto.LocalUserView;
import com.github.clawagent.spi.LocalUserRecord;
import com.github.clawagent.spi.LocalUserStore;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
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
 * 本地用户服务。
 * 持久化由 LocalUserStore 注入，默认运行期使用 SQLite，企业接入时可替换为统一身份源。
 */
public class LocalUserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final List<String> SUPPORTED_ROLES = List.of("owner", "admin", "operator", "viewer", "user");

    private final LocalUserStore store;

    public LocalUserService(LocalUserStore store) {
        this.store = store;
    }

    public synchronized List<LocalUserView> list() {
        return readRecords().stream().map(this::toView).toList();
    }

    public synchronized boolean isInitialized() {
        return !readRecords().isEmpty();
    }

    public synchronized long count() {
        return readRecords().size();
    }

    public synchronized boolean ownerExists() {
        return readRecords().stream().anyMatch(record -> "owner".equalsIgnoreCase(record.role()));
    }

    public List<String> supportedRoles() {
        return SUPPORTED_ROLES;
    }

    public synchronized Optional<LocalUserView> findActive(String userIdOrUsername) {
        String normalized = normalizeUsername(userIdOrUsername);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return readRecords().stream()
                .filter(record -> "active".equalsIgnoreCase(record.status()))
                .filter(record -> record.id().equals(userIdOrUsername) || normalizeUsername(record.username()).equals(normalized))
                .findFirst()
                .map(this::toView);
    }

    public synchronized LocalUserView create(LocalUserCreateRequest request) {
        String username = normalizeUsername(request == null ? null : request.username());
        String password = request == null ? "" : request.password();
        if (username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("本地用户密码至少 6 位");
        }
        List<LocalUserRecord> records = new ArrayList<>(readRecords());
        if (records.stream().anyMatch(record -> normalizeUsername(record.username()).equals(username))) {
            throw new IllegalArgumentException("本地用户已存在：" + username);
        }
        String role = normalizeRole(request == null ? null : request.role(), records.isEmpty() ? "owner" : "user");
        Instant now = Instant.now();
        PasswordHash passwordHash = hashPassword(password);
        LocalUserRecord record = new LocalUserRecord(
                UUID.randomUUID().toString(),
                username,
                firstNonBlank(request == null ? null : request.displayName(), username),
                role,
                "active",
                now,
                null,
                now,
                passwordHash.salt(),
                passwordHash.hash(),
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()));
        records.add(record);
        writeRecords(records);
        return toView(record);
    }

    public synchronized LocalUserView setupOwner(LocalUserCreateRequest request) {
        if (isInitialized()) {
            throw new IllegalStateException("本地用户已初始化，不能重复执行 setup");
        }
        LocalUserCreateRequest ownerRequest = new LocalUserCreateRequest(
                request == null ? null : request.username(),
                request == null ? null : request.password(),
                request == null ? null : request.displayName(),
                "owner",
                request == null ? Map.of("setup", "true") : mergeSetupMetadata(request.metadata()));
        return create(ownerRequest);
    }

    public synchronized LocalUserView disable(String userId) {
        return update(userId, record -> new LocalUserRecord(
                record.id(), record.username(), record.displayName(), record.role(),
                "disabled", record.createdAt(), Instant.now(), record.lastPasswordChangedAt(),
                record.passwordSalt(), record.passwordHash(), record.metadata()));
    }

    public synchronized LocalUserView changePassword(String userId, LocalUserPasswordChangeRequest request) {
        String password = request == null ? "" : request.password();
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("本地用户密码至少 6 位");
        }
        PasswordHash passwordHash = hashPassword(password);
        return update(userId, record -> new LocalUserRecord(
                record.id(), record.username(), record.displayName(), record.role(),
                record.status(), record.createdAt(), record.disabledAt(), Instant.now(),
                passwordHash.salt(), passwordHash.hash(), record.metadata()));
    }

    public synchronized LocalUserView updatePermissions(String userId, LocalUserPermissionUpdateRequest request) {
        return update(userId, record -> new LocalUserRecord(
                record.id(), record.username(), record.displayName(), record.role(),
                record.status(), record.createdAt(), record.disabledAt(), record.lastPasswordChangedAt(),
                record.passwordSalt(), record.passwordHash(), mergePermissionMetadata(record.metadata(), request)));
    }

    public synchronized boolean verify(String username, String password) {
        return authenticate(username, password).isPresent();
    }

    public synchronized Optional<LocalUserView> authenticate(String username, String password) {
        String normalizedUsername = normalizeUsername(username);
        if (normalizedUsername.isBlank() || password == null || password.isBlank()) {
            return Optional.empty();
        }
        return readRecords().stream()
                .filter(record -> "active".equalsIgnoreCase(record.status()))
                .filter(record -> normalizeUsername(record.username()).equals(normalizedUsername))
                .filter(record -> hasStoredPassword(record.passwordSalt(), record.passwordHash()))
                .findFirst()
                .filter(record -> hashEquals(record.passwordHash(), hashPassword(password, record.passwordSalt())))
                .map(this::toView);
    }

    private LocalUserView update(String userId, UserUpdater updater) {
        List<LocalUserRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            LocalUserRecord record = records.get(i);
            if (record.id().equals(userId)) {
                LocalUserRecord updated = updater.update(record);
                records.set(i, updated);
                writeRecords(records);
                return toView(updated);
            }
        }
        throw new IllegalArgumentException("本地用户不存在：" + userId);
    }

    private List<LocalUserRecord> readRecords() {
        return store.read();
    }

    private void writeRecords(List<LocalUserRecord> records) {
        store.write(records);
    }

    private LocalUserView toView(LocalUserRecord record) {
        return new LocalUserView(record.id(), record.username(), record.displayName(), record.role(),
                record.status(), record.createdAt(), record.disabledAt(), record.lastPasswordChangedAt(), record.metadata());
    }

    private PasswordHash hashPassword(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = Base64.getUrlEncoder().withoutPadding().encodeToString(saltBytes);
        return new PasswordHash(salt, hashPassword(password, salt));
    }

    private String hashPassword(String password, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), HASH_ITERATIONS, HASH_BITS);
            byte[] bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("本地用户密码哈希失败", e);
        }
    }

    private boolean hashEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasStoredPassword(String salt, String hash) {
        // 兼容用户文件被手工编辑或旧版本数据缺字段的情况，避免登录校验直接抛异常。
        return salt != null && !salt.isBlank() && hash != null && !hash.isBlank();
    }

    private String normalizeRole(String role, String fallback) {
        String normalized = firstNonBlank(role, fallback).trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_ROLES.contains(normalized) ? normalized : "user";
    }

    private Map<String, String> mergeSetupMetadata(Map<String, String> metadata) {
        Map<String, String> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        // 标记 setup 来源，方便审计和后续排查首个 owner 的创建方式。
        merged.putIfAbsent("setup", "true");
        return merged;
    }

    private Map<String, String> mergePermissionMetadata(Map<String, String> metadata, LocalUserPermissionUpdateRequest request) {
        Map<String, String> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        merged.remove("toolPermissionMode");
        merged.remove("permissionMode");
        merged.remove("approvedToolIds");
        merged.remove("toolIds");
        String mode = normalizePermissionMode(request == null ? null : request.permissionMode());
        Set<String> approvedToolIds = normalizeToolIds(request == null ? null : request.approvedToolIds());
        // 用户维度策略沿用 metadata 存储，避免引入第二套权限字段和策略解析分支。
        merged.put("permissionMode", mode);
        if (!approvedToolIds.isEmpty()) {
            merged.put("approvedToolIds", String.join(",", approvedToolIds));
        }
        return merged;
    }

    private String normalizePermissionMode(String mode) {
        String value = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("full-access".equals(value) || "full".equals(value) || "auto".equals(value)
                || "custom".equals(value) || "ask".equals(value) || "read-only".equals(value)) {
            return value;
        }
        return "ask";
    }

    private Set<String> normalizeToolIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .forEach(ids::add);
        return Collections.unmodifiableSet(ids);
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private interface UserUpdater {
        LocalUserRecord update(LocalUserRecord record);
    }

    private record PasswordHash(String salt, String hash) {
    }

}
