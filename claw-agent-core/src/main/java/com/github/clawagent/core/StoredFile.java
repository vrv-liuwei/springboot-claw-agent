package com.github.clawagent.core;

import java.nio.file.Path;
import java.util.Map;

/**
 * 文件存储后的元信息。
 * local provider 会提供 localPath；MinIO/RAGFlow 后续可以只填 providerPath。
 *
 * @param id 文件在 ClawAgent 本地侧的唯一 ID。
 * @param originalName 上传时的原始文件名，已由存储 provider 做安全清洗。
 * @param contentType 文件 MIME 类型，未知时使用 application/octet-stream。
 * @param size 文件字节数。
 * @param provider 文件存储 provider，例如 local、minio、ragflow。
 * @param providerPath provider 内部可定位文件的路径或对象 key。
 * @param localPath 本地文件路径；非本地 provider 可以为空。
 * @param metadata 文件轻量扩展元信息，不保存正文。
 */
public record StoredFile(
        String id,
        String originalName,
        String contentType,
        long size,
        String provider,
        String providerPath,
        Path localPath,
        Map<String, String> metadata
) {
    public StoredFile {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
