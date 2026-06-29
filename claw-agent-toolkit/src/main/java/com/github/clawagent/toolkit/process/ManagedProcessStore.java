package com.github.clawagent.toolkit.process;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public class ManagedProcessStore {
    private final ConcurrentMap<Long, ManagedProcess> processes = new ConcurrentHashMap<>();
    private final Path persistencePath;

    public ManagedProcessStore() {
        this(defaultPersistencePath());
    }

    public ManagedProcessStore(Path persistencePath) {
        this.persistencePath = persistencePath.toAbsolutePath().normalize();
        load();
    }

    public void put(ManagedProcess process) {
        processes.put(process.pid(), process);
        save();
    }

    public Optional<ManagedProcess> get(long pid) {
        return Optional.ofNullable(processes.get(pid));
    }

    public Collection<ManagedProcess> list() {
        return processes.values();
    }

    public void remove(long pid) {
        processes.remove(pid);
        save();
    }

    public Path persistencePath() {
        return persistencePath;
    }

    private void load() {
        if (!Files.isRegularFile(persistencePath)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(persistencePath, StandardCharsets.UTF_8)) {
                ManagedProcess process = parse(line);
                if (process != null) {
                    processes.put(process.pid(), process);
                }
            }
        } catch (Exception ignored) {
            // 进程表只是运行辅助索引，读坏时保持空表，避免影响工具注册和服务启动。
            processes.clear();
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(persistencePath.getParent());
            List<String> lines = processes.values().stream()
                    .map(this::format)
                    .collect(Collectors.toCollection(ArrayList::new));
            Files.write(persistencePath, lines, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 持久化失败不能中断真实进程启动；管理台仍可显示内存态记录。
        }
    }

    private ManagedProcess parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split("\t", -1);
        if (parts.length < 5) {
            return null;
        }
        long pid = Long.parseLong(parts[0]);
        Path cwd = Path.of(parts[1]).toAbsolutePath().normalize();
        Path logPath = Path.of(parts[2]).toAbsolutePath().normalize();
        Instant startedAt = Instant.parse(parts[3]);
        List<String> command = decodeCommand(parts[4]);
        String taskId = parts.length > 5 ? emptyToNull(parts[5]) : null;
        String sessionId = parts.length > 6 ? emptyToNull(parts[6]) : null;
        String projectPath = parts.length > 7 ? emptyToNull(parts[7]) : null;
        String healthUrl = parts.length > 8 ? emptyToNull(parts[8]) : null;
        return new ManagedProcess(pid, null, command, cwd, logPath, startedAt, taskId, sessionId, projectPath, healthUrl);
    }

    private String format(ManagedProcess process) {
        return process.pid() + "\t"
                + process.cwd() + "\t"
                + process.logPath() + "\t"
                + process.startedAt() + "\t"
                + encodeCommand(process.command()) + "\t"
                + nullToEmpty(process.taskId()) + "\t"
                + nullToEmpty(process.sessionId()) + "\t"
                + nullToEmpty(process.projectPath()) + "\t"
                + nullToEmpty(process.healthUrl());
    }

    private String encodeCommand(List<String> command) {
        String joined = String.join("\u001f", command == null ? List.of() : command);
        return Base64.getEncoder().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    private List<String> decodeCommand(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return decoded.isBlank() ? List.of() : List.of(decoded.split("\u001f", -1));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Path defaultPersistencePath() {
        return Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize()
                .resolve(".clawagent")
                .resolve("processes")
                .resolve("managed-processes.tsv");
    }
}
