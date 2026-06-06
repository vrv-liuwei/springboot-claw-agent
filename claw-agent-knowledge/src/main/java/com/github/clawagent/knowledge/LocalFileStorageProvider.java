package com.github.clawagent.knowledge;

import com.github.clawagent.core.StoredFile;
import com.github.clawagent.spi.FileStorageProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 本地文件存储 provider。
 * 默认用于附件原文件保存，后续可用 MinIO/OSS provider 替换。
 */
public class LocalFileStorageProvider implements FileStorageProvider {
    private final Path root;

    /**
     * @param root 本地附件根目录。
     */
    public LocalFileStorageProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String id() {
        return "local";
    }

    @Override
    public StoredFile save(String originalName, String contentType, byte[] content, Map<String, String> metadata) throws IOException {
        String fileId = UUID.randomUUID().toString();
        byte[] safeContent = content == null ? new byte[0] : content;
        Path dayDir = root.resolve(DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now())).normalize();
        Files.createDirectories(dayDir);
        Path target = dayDir.resolve(fileId + "-" + sanitizeName(originalName)).normalize();
        // 本地 provider 写文件前必须做目录边界校验，防止构造型文件名逃逸附件根目录。
        if (!target.startsWith(dayDir)) {
            throw new IOException("非法附件路径");
        }
        Files.write(target, safeContent);
        return new StoredFile(
                fileId,
                originalName(target.getFileName().toString(), fileId),
                safeContentType(contentType),
                safeContent.length,
                id(),
                root.relativize(target).toString(),
                target,
                metadata == null ? Map.of() : metadata);
    }

    @Override
    public StoredFile read(String fileId) throws IOException {
        String safeId = sanitizeId(fileId);
        if (!Files.exists(root)) {
            throw new IOException("附件不存在：" + safeId);
        }
        try (Stream<Path> paths = Files.walk(root)) {
            Optional<Path> match = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(safeId + "-"))
                    .findFirst();
            Path file = match.orElseThrow(() -> new IOException("附件不存在：" + safeId)).toAbsolutePath().normalize();
            // 读取时同样校验边界，避免异常路径绕过本地 provider 根目录。
            if (!file.startsWith(root)) {
                throw new IOException("非法附件路径");
            }
            String contentType = Files.probeContentType(file);
            return new StoredFile(
                    safeId,
                    originalName(file.getFileName().toString(), safeId),
                    safeContentType(contentType),
                    Files.size(file),
                    id(),
                    root.relativize(file).toString(),
                    file,
                    Map.of());
        }
    }

    private String sanitizeName(String filename) {
        String value = filename == null || filename.isBlank() ? "attachment" : filename;
        String cleaned = value.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
        return cleaned.isBlank() ? "attachment" : cleaned;
    }

    private String originalName(String filename, String fileId) {
        String prefix = fileId + "-";
        return filename.startsWith(prefix) ? filename.substring(prefix.length()) : filename;
    }

    private String safeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String sanitizeId(String fileId) throws IOException {
        if (fileId == null || !fileId.matches("[a-fA-F0-9\\-]{36}")) {
            throw new IOException("非法附件 ID");
        }
        return fileId;
    }
}
