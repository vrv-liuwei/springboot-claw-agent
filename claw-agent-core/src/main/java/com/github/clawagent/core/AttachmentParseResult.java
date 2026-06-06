package com.github.clawagent.core;

/**
 * 附件上传解析后的轻量结果。
 * 不携带正文，避免 input 和历史消息被大文件内容撑大。
 *
 * @param id 附件文件 ID，用于下载和预览。
 * @param name 上传文件名。
 * @param contentType 文件 MIME 类型。
 * @param size 文件字节数。
 * @param kind 内容类型归类，例如 image、pdf、word、excel、text。
 * @param storageProvider 附件存储 provider，例如 local、minio。
 * @param storagePath 附件在存储 provider 内的路径或对象 key。
 * @param extractedText 兼容旧前端字段；知识库模式下保持为空，不把正文返回页面。
 * @param originalChars 解析出的原始文本字符数，用于提示是否大文件。
 * @param extractedChars 返回给前端的文本字符数；知识库模式下通常为 0。
 * @param truncated 是否因长度限制发生截断。
 * @param message 面向前端展示的解析/入库状态说明。
 * @param knowledgeDocumentId 附件自动入库后生成的知识库文档 ID。
 * @param knowledgeProvider 知识库 provider，例如 local、ragflow。
 * @param providerDocumentId 知识库 provider 内部文档 ID。
 */
public record AttachmentParseResult(
        String id,
        String name,
        String contentType,
        long size,
        String kind,
        String storageProvider,
        String storagePath,
        String extractedText,
        int originalChars,
        int extractedChars,
        boolean truncated,
        String message,
        String knowledgeDocumentId,
        String knowledgeProvider,
        String providerDocumentId
) {
}
