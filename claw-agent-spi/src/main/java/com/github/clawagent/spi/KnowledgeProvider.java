package com.github.clawagent.spi;

import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.StoredFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库 Provider 扩展点。
 * local、RAGFlow、Qdrant、pgvector 等实现都适配为这个接口。
 */
public interface KnowledgeProvider {
    /**
     * Provider 标识，例如 local、ragflow。
     */
    String id();

    /**
     * Provider 能力声明，供管理台展示和业务层判断是否支持文件存储、向量检索、BM25、hybrid 等能力。
     */
    default Map<String, Object> capabilities() {
        return Map.of();
    }

    /**
     * 将文档写入知识库。
     *
     * @param userId 文档所属用户 ID，provider 必须用它做数据隔离。
     * @param name 原始文件名或网页标题。
     * @param contentType 原始内容 MIME 类型。
     * @param kind 内容类型归类，例如 text、word、pdf、excel、image、web。
     * @param content 原始内容字节。
     * @param metadata 轻量扩展元信息，不保存正文。
     */
    KnowledgeDocument ingest(String userId, String name, String contentType, String kind, byte[] content, Map<String, String> metadata);

    /**
     * 列出指定用户可见的知识库文档。
     */
    List<KnowledgeDocument> list(String userId, int limit);

    /**
     * 检索指定用户的知识库内容。
     *
     * @param documentIds 为空时由 provider 按用户全库检索；不为空时只检索这些文档。
     * @param mode 检索模式，例如 keyword、vector、hybrid。
     */
    List<KnowledgeSearchResult> search(String userId, String query, List<String> documentIds, String mode, int topK);

    /**
     * 按文档顺序读取 chunk，用于文档总结、概览、目录类请求。
     *
     * @param userId 当前用户 ID，provider 必须用它做数据隔离。
     * @param documentIds 为空时读取当前用户全库的前置 chunk；不为空时只读取这些文档。
     * @param maxChunks 最多返回 chunk 数，避免大文件全文压入模型上下文。
     */
    default List<KnowledgeSearchResult> readDocumentChunks(String userId, List<String> documentIds, int maxChunks) {
        throw new UnsupportedOperationException("当前知识库 provider 不支持直接读取文档内容");
    }

    /**
     * 读取原文件元信息和本地路径。
     */
    StoredFile download(String userId, String documentId);

    /**
     * 删除文档及其索引数据。
     */
    void delete(String userId, String documentId);
}
