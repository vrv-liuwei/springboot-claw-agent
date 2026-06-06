package com.github.clawagent.spi;

import com.github.clawagent.core.StoredFile;

import java.io.IOException;
import java.util.Map;

/**
 * 文件存储 Provider 扩展点。
 * 默认 local 实现放在 knowledge 模块，后续可替换为 MinIO/OSS。
 */
public interface FileStorageProvider {
    /**
     * Provider 标识，例如 local、minio。
     */
    String id();

    /**
     * 保存文件并返回轻量元信息。
     *
     * @param originalName 上传时的原始文件名。
     * @param contentType 文件 MIME 类型。
     * @param content 文件原始字节。
     * @param metadata 轻量扩展元信息。
     */
    StoredFile save(String originalName, String contentType, byte[] content, Map<String, String> metadata) throws IOException;

    /**
     * 按文件 ID 读取文件元信息和定位信息。
     */
    StoredFile read(String fileId) throws IOException;
}
