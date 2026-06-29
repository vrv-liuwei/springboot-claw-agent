package com.github.clawagent.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.server.dto.DeviceRegisterRequest;
import com.github.clawagent.server.dto.DeviceView;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地设备登记服务。
 * 先提供单机 JSON 存储，后续接桌面壳、浏览器扩展或企业 Device Pairing 时可替换为持久化实现。
 */
public class DeviceRegistryService {
    private static final TypeReference<List<DeviceRecord>> DEVICE_LIST_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Path storePath;

    public DeviceRegistryService(Path storePath) {
        this.storePath = storePath;
    }

    public synchronized List<DeviceView> list() {
        return readRecords().stream().map(this::toView).toList();
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
                request == null || request.metadata() == null ? Map.of() : new LinkedHashMap<>(request.metadata()));
        List<DeviceRecord> records = new ArrayList<>(readRecords());
        records.add(record);
        writeRecords(records);
        return toView(record);
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
                record.metadata()));
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
                record.metadata()));
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
        if (!Files.exists(storePath)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(Files.readString(storePath, StandardCharsets.UTF_8), DEVICE_LIST_TYPE);
        } catch (IOException e) {
            throw new IllegalStateException("读取设备配置失败：" + storePath, e);
        }
    }

    private void writeRecords(List<DeviceRecord> records) {
        try {
            Files.createDirectories(storePath.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(storePath.toFile(), records);
        } catch (IOException e) {
            throw new IllegalStateException("保存设备配置失败：" + storePath, e);
        }
    }

    private DeviceView toView(DeviceRecord record) {
        return new DeviceView(record.id(), record.name(), record.type(), record.status(),
                record.firstSeenAt(), record.lastSeenAt(), record.revokedAt(), record.metadata());
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private interface RecordUpdater {
        DeviceRecord update(DeviceRecord record);
    }

    private record DeviceRecord(
            String id,
            String name,
            String type,
            String status,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant revokedAt,
            Map<String, String> metadata
    ) {
    }
}
