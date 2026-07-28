package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.ChannelUserBindingRequest;
import com.github.clawagent.server.dto.ChannelUserBindingView;
import com.github.clawagent.spi.ChannelUserBindingRecord;
import com.github.clawagent.spi.ChannelUserBindingStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Channel 外部用户与本地用户的轻量绑定服务。
 * 绑定关系只依赖 ChannelUserBindingStore，默认 SQLite，后续可替换为企业统一身份目录。
 */
public class ChannelUserBindingService {
    private final ChannelUserBindingStore store;

    public ChannelUserBindingService(ChannelUserBindingStore store) {
        this.store = store;
    }

    public synchronized List<ChannelUserBindingView> list(String channelId) {
        return readRecords().stream()
                .filter(record -> channelId == null || channelId.isBlank() || record.channelId().equals(channelId.trim()))
                .map(this::toView)
                .toList();
    }

    public synchronized Optional<ChannelUserBindingView> findActive(String channelId, String externalUserId) {
        if (channelId == null || channelId.isBlank() || externalUserId == null || externalUserId.isBlank()) {
            return Optional.empty();
        }
        String normalizedChannelId = channelId.trim();
        String normalizedExternalUserId = externalUserId.trim();
        return readRecords().stream()
                .filter(record -> normalizedChannelId.equals(record.channelId()))
                .filter(record -> normalizedExternalUserId.equals(record.externalUserId()))
                .filter(record -> "active".equalsIgnoreCase(record.status()))
                .findFirst()
                .map(this::toView);
    }

    public synchronized ChannelUserBindingView bind(String channelId, ChannelUserBindingRequest request) {
        String safeChannelId = firstNonBlank(channelId, "");
        String externalUserId = firstNonBlank(request == null ? null : request.externalUserId(), "");
        String localUserId = firstNonBlank(request == null ? null : request.localUserId(), "");
        if (safeChannelId.isBlank()) {
            throw new IllegalArgumentException("channelId 不能为空");
        }
        if (externalUserId.isBlank()) {
            throw new IllegalArgumentException("externalUserId 不能为空");
        }
        if (localUserId.isBlank()) {
            throw new IllegalArgumentException("localUserId 不能为空");
        }
        String externalUsername = firstNonBlank(request == null ? null : request.externalUsername(), externalUserId);
        String localUsername = firstNonBlank(request == null ? null : request.localUsername(), localUserId);
        Map<String, String> requestMetadata = request == null || request.metadata() == null
                ? null
                : new LinkedHashMap<>(request.metadata());
        Instant now = Instant.now();
        List<ChannelUserBindingRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            ChannelUserBindingRecord record = records.get(i);
            if (safeChannelId.equals(record.channelId()) && externalUserId.equals(record.externalUserId())) {
                // 同一个外部用户只保留一条最新绑定，避免策略解析命中旧的本地用户。
                ChannelUserBindingRecord updated = new ChannelUserBindingRecord(
                        record.id(), safeChannelId, externalUserId,
                        firstNonBlank(externalUsername, record.externalUsername()),
                        localUserId,
                        firstNonBlank(localUsername, record.localUsername()),
                        "active", record.createdAt(), now,
                        requestMetadata == null ? record.metadata() : requestMetadata);
                records.set(i, updated);
                writeRecords(records);
                return toView(updated);
            }
        }
        ChannelUserBindingRecord created = new ChannelUserBindingRecord(
                UUID.randomUUID().toString(),
                safeChannelId,
                externalUserId,
                externalUsername,
                localUserId,
                localUsername,
                "active",
                now,
                now,
                requestMetadata == null ? Map.of() : requestMetadata);
        records.add(created);
        writeRecords(records);
        return toView(created);
    }

    public synchronized boolean unbind(String channelId, String externalUserId) {
        List<ChannelUserBindingRecord> records = new ArrayList<>(readRecords());
        for (int i = 0; i < records.size(); i++) {
            ChannelUserBindingRecord record = records.get(i);
            if (record.channelId().equals(firstNonBlank(channelId, ""))
                    && record.externalUserId().equals(firstNonBlank(externalUserId, ""))
                    && "active".equalsIgnoreCase(record.status())) {
                // 逻辑解绑保留历史，方便后续审计某个外部用户曾经映射到哪个本地用户。
                records.set(i, new ChannelUserBindingRecord(record.id(), record.channelId(), record.externalUserId(),
                        record.externalUsername(), record.localUserId(), record.localUsername(),
                        "unbound", record.createdAt(), Instant.now(), record.metadata()));
                writeRecords(records);
                return true;
            }
        }
        return false;
    }

    private List<ChannelUserBindingRecord> readRecords() {
        return store.read();
    }

    private void writeRecords(List<ChannelUserBindingRecord> records) {
        store.write(records);
    }

    private ChannelUserBindingView toView(ChannelUserBindingRecord record) {
        return new ChannelUserBindingView(record.id(), record.channelId(), record.externalUserId(),
                record.externalUsername(), record.localUserId(), record.localUsername(),
                record.status(), record.createdAt(), record.updatedAt(),
                record.metadata() == null ? Map.of() : record.metadata());
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
