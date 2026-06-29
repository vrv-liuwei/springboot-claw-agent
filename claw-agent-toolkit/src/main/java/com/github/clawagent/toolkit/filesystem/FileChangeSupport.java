package com.github.clawagent.toolkit.filesystem;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * 文件变更辅助能力。
 * write_file 和 rollback 共用这里生成备份、diff 和回滚目录，避免每个工具重复处理路径。
 */
class FileChangeSupport {
    private static final int MAX_DIFF_LINES = 120;
    private final Path backupRoot;

    FileChangeSupport() {
        this(Path.of(".clawagent", "backups", "filesystem"));
    }

    FileChangeSupport(Path backupRoot) {
        this.backupRoot = backupRoot.toAbsolutePath().normalize();
    }

    FileChangeSnapshot captureBefore(Path target, Charset charset) throws IOException {
        if (!Files.exists(target)) {
            return new FileChangeSnapshot(false, "", "");
        }
        String before = Files.readString(target, charset);
        Path backupPath = createBackup(target, before, charset);
        return new FileChangeSnapshot(true, before, backupPath.toString());
    }

    Path resolveBackup(String rawBackupPath) {
        if (rawBackupPath == null || rawBackupPath.isBlank()) {
            throw new IllegalArgumentException("缺少参数：backupPath");
        }
        Path backupPath = Path.of(rawBackupPath.trim()).toAbsolutePath().normalize();
        if (!backupPath.startsWith(backupRoot)) {
            throw new IllegalArgumentException("backupPath 不在文件变更备份目录内：" + backupRoot);
        }
        if (!Files.exists(backupPath)) {
            throw new IllegalArgumentException("备份文件不存在：" + backupPath);
        }
        return backupPath;
    }

    String formatWriteResult(Path path, boolean append, FileChangeSnapshot snapshot, String after) {
        String beforeForDiff = append ? snapshot.before() : snapshot.before();
        String changeType = snapshot.existed() ? (append ? "append" : "modify") : "create";
        return "changeType: " + changeType + "\n"
                + "path: " + path + "\n"
                + "backupPath: " + snapshot.backupPath() + "\n"
                + "rollbackTool: builtin.filesystem.rollback_file\n"
                + "diff:\n" + unifiedDiff(beforeForDiff, after);
    }

    String formatRollbackResult(Path path, Path backupPath, String beforeRollback, String restored) {
        return "changeType: rollback\n"
                + "path: " + path + "\n"
                + "backupPath: " + backupPath + "\n"
                + "diff:\n" + unifiedDiff(beforeRollback, restored);
    }

    private Path createBackup(Path target, String before, Charset charset) throws IOException {
        String safeName = target.getFileName() == null ? "file" : target.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Path backupPath = backupRoot.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID() + "-" + safeName + ".bak");
        Files.createDirectories(backupPath.getParent());
        Files.writeString(backupPath, before, charset);
        return backupPath;
    }

    private String unifiedDiff(String before, String after) {
        String[] oldLines = lines(before);
        String[] newLines = lines(after);
        StringBuilder diff = new StringBuilder();
        diff.append("--- before\n+++ after\n");
        int max = Math.max(oldLines.length, newLines.length);
        int emitted = 0;
        for (int i = 0; i < max; i++) {
            String oldLine = i < oldLines.length ? oldLines[i] : null;
            String newLine = i < newLines.length ? newLines[i] : null;
            if (oldLine != null && oldLine.equals(newLine)) {
                continue;
            }
            if (emitted >= MAX_DIFF_LINES) {
                diff.append("[diff 已截断，最多 ").append(MAX_DIFF_LINES).append(" 行]\n");
                break;
            }
            if (oldLine != null) {
                diff.append("-").append(oldLine).append("\n");
                emitted++;
            }
            if (newLine != null && emitted < MAX_DIFF_LINES) {
                diff.append("+").append(newLine).append("\n");
                emitted++;
            }
        }
        if (emitted == 0) {
            diff.append("[无内容变化]\n");
        }
        return diff.toString();
    }

    private String[] lines(String value) {
        if (value == null || value.isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(value.split("\\R", -1)).toArray(String[]::new);
    }

    record FileChangeSnapshot(boolean existed, String before, String backupPath) {
    }
}
