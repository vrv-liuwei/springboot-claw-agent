package com.github.clawagent.core;

import java.time.Instant;
import java.util.Map;

/**
 * 知识库文档元数据。
 * 只保存可展示、可检索、可审计的轻量信息，不保存正文。
 *
 * @param id 文档在 ClawAgent 本地侧的唯一 ID，用于前端引用、检索限定、下载和删除。
 * @param userId 文档所属用户 ID，所有列表、检索、下载、删除都必须用它做隔离。
 * @param provider 文档所在知识库 provider，例如 local、ragflow。
 * @param providerDocumentId provider 内部的文档 ID；local 可与 id 相同，RAGFlow 可保存远端文档 ID。
 * @param name 原始文件名或网页标题，用于管理台展示和检索结果来源说明。
 * @param kind 内容类型归类，例如 text、word、pdf、excel、image、web。
 * @param size 原始内容字节数，用于展示和上传限制判断。
 * @param storagePath 原文件存储路径或 provider 路径；local 是相对路径，MinIO/RAGFlow 可保存对象路径。
 * @param status 入库状态，例如 READY、PARSING、FAILED。
 * @param metadata 扩展元信息，只放轻量字段，不保存正文。
 * @param createdAt 创建时间。
 * @param updatedAt 最后更新时间。
 */
public record KnowledgeDocument(
        String id,
        String userId,
        String provider,
        String providerDocumentId,
        String name,
        String kind,
        long size,
        String storagePath,
        String status,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public KnowledgeDocument {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
