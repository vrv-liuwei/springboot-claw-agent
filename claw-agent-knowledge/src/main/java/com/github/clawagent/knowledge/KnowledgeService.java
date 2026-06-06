package com.github.clawagent.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.spi.KnowledgeProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库统一入口。
 * Controller、附件上传、聊天上下文增强都只依赖该服务，不直接绑定 local/RAGFlow 等具体实现。
 */
public class KnowledgeService {
    public static final String METADATA_CONTEXT_KEY = "knowledge.context";
    public static final String METADATA_DOCUMENT_IDS_KEY = "knowledge.documentIds";
    public static final String METADATA_ENABLED_KEY = "knowledge.enabled";
    public static final String METADATA_ATTACHMENT_DOCUMENT_IDS_KEY = "attachmentKnowledgeDocumentIds";
    public static final String METADATA_SCOPE_KEY = "knowledge.scope";
    public static final String METADATA_INTENT_KEY = "knowledge.intent";
    private static final int DEFAULT_CONTEXT_TOP_K = 5;
    private static final int DEFAULT_DIRECT_READ_CHUNKS = 12;
    private static final int MAX_CONTEXT_CHARS = 12_000;

    private final Map<String, KnowledgeProvider> providers = new LinkedHashMap<>();
    private final String defaultProviderId;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param providers Spring 注入的所有知识库 provider。
     * @param defaultProviderId 默认 provider 标识，例如 local、ragflow。
     */
    public KnowledgeService(List<KnowledgeProvider> providers, String defaultProviderId) {
        if (providers != null) {
            for (KnowledgeProvider provider : providers) {
                if (provider != null) {
                    this.providers.put(provider.id(), provider);
                }
            }
        }
        this.defaultProviderId = defaultProviderId == null || defaultProviderId.isBlank() ? "local" : defaultProviderId;
    }

    /**
     * 返回可用 provider 能力，用于后台页面展示和后续 provider 切换。
     */
    public List<Map<String, Object>> providers() {
        return providers.values().stream()
                .map(provider -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", provider.id());
                    boolean active = provider.id().equals(defaultProviderId);
                    item.put("default", active);
                    item.put("active", active);
                    item.put("capabilities", provider.capabilities());
                    return item;
                })
                .toList();
    }

    /**
     * 将文件或网页内容写入当前默认知识库 provider。
     */
    public KnowledgeDocument ingest(String userId,
                                    String name,
                                    String contentType,
                                    String kind,
                                    byte[] content,
                                    Map<String, String> metadata) {
        // provider 切换边界集中在这里，上层无需知道当前走 local 还是企业级 RAG 服务。
        return provider().ingest(normalizeUserId(userId), name, contentType, kind, content == null ? new byte[0] : content, safeMetadata(metadata));
    }

    /**
     * 列出指定用户的知识库文档。
     */
    public List<KnowledgeDocument> list(String userId, int limit) {
        return provider().list(normalizeUserId(userId), limit);
    }

    /**
     * 检索指定用户的知识库内容。
     */
    public List<KnowledgeSearchResult> search(String userId, String query, List<String> documentIds, String mode, int topK) {
        return provider().search(normalizeUserId(userId), query == null ? "" : query, safeDocumentIds(documentIds), mode, Math.max(1, topK));
    }

    /**
     * 按文档顺序读取 chunk，用于总结、概览、目录类请求，不做向量召回排序。
     */
    public List<KnowledgeSearchResult> readDocumentChunks(String userId, List<String> documentIds, int maxChunks) {
        return provider().readDocumentChunks(normalizeUserId(userId), safeDocumentIds(documentIds), Math.max(1, maxChunks));
    }

    /**
     * 下载文档原文件。
     */
    public StoredFile download(String userId, String documentId) {
        return provider().download(normalizeUserId(userId), documentId);
    }

    /**
     * 删除文档及索引。
     */
    public void delete(String userId, String documentId) {
        provider().delete(normalizeUserId(userId), documentId);
    }

    /**
     * 聊天请求进入模型前，按用户行为和语义选择“读文档”或“检索”后注入上下文。
     */
    public AgentRequest enrichForModel(AgentRequest request) {
        if (request == null || request.input() == null || request.input().isBlank()) {
            return request;
        }
        Map<String, String> originalMetadata = request.metadata();
        List<String> attachmentDocumentIds = idsFromMetadata(originalMetadata, METADATA_ATTACHMENT_DOCUMENT_IDS_KEY);
        List<String> selectedDocumentIds = selectedDocumentIds(originalMetadata);
        boolean enabled = Boolean.parseBoolean(value(originalMetadata, METADATA_ENABLED_KEY));
        KnowledgeRoute route = route(request.input(), originalMetadata, attachmentDocumentIds, selectedDocumentIds, enabled);
        if (route.intent() == KnowledgeIntent.NONE) {
            return request;
        }
        List<KnowledgeSearchResult> hits = switch (route.intent()) {
            case SUMMARY -> readDocumentChunks(request.userId(), route.documentIds(), DEFAULT_DIRECT_READ_CHUNKS);
            case QA -> search(request.userId(), request.input(), route.documentIds(), "hybrid", DEFAULT_CONTEXT_TOP_K);
            case NONE -> List.of();
        };
        if (route.intent() == KnowledgeIntent.QA && hits.isEmpty() && !route.documentIds().isEmpty()) {
            // 附件/选中文档问答如果检索没召回，回退读取前置 chunk，避免模型误以为没有本地上下文而转去联网搜索。
            hits = readDocumentChunks(request.userId(), route.documentIds(), DEFAULT_DIRECT_READ_CHUNKS);
        }
        String context = renderContext(hits, route);
        if (context.isBlank()) {
            return request;
        }
        Map<String, String> metadata = new LinkedHashMap<>(originalMetadata);
        metadata.put(METADATA_CONTEXT_KEY, context);
        metadata.put(METADATA_SCOPE_KEY, route.scope());
        metadata.put(METADATA_INTENT_KEY, route.intent().metadataValue());
        return new AgentRequest(request.input(), request.sessionId(), request.channelId(), request.userId(), metadata);
    }

    private KnowledgeProvider provider() {
        KnowledgeProvider provider = providers.get(defaultProviderId);
        if (provider == null) {
            throw new IllegalArgumentException("知识库 provider 不存在：" + defaultProviderId);
        }
        return provider;
    }

    private String renderContext(List<KnowledgeSearchResult> hits, KnowledgeRoute route) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder context = new StringBuilder(route.intent() == KnowledgeIntent.SUMMARY
                ? "本地知识库文档内容：\n"
                : "本地知识库检索结果：\n");
        context.append("请优先基于以下本地知识库上下文回答；如果上下文不足，请明确说明缺少哪些信息，不要用联网搜索替代本地文档。\n")
                .append("scope=").append(route.scope())
                .append(", intent=").append(route.intent().metadataValue())
                .append("\n\n");
        int index = 1;
        for (KnowledgeSearchResult hit : hits) {
            String text = hit.text() == null ? "" : hit.text().trim();
            if (text.isBlank()) {
                continue;
            }
            context.append(index++)
                    .append(". 来源：").append(hit.documentName())
                    .append(" #").append(hit.chunkNo())
                    .append(" score=").append(String.format(java.util.Locale.ROOT, "%.4f", hit.score()))
                    .append("\n")
                    .append(text)
                    .append("\n\n");
            if (context.length() >= MAX_CONTEXT_CHARS) {
                break;
            }
        }
        return context.length() > MAX_CONTEXT_CHARS
                ? context.substring(0, MAX_CONTEXT_CHARS) + "\n[知识库上下文已截断]"
                : context.toString().trim();
    }

    private KnowledgeRoute route(String input,
                                 Map<String, String> metadata,
                                 List<String> attachmentDocumentIds,
                                 List<String> selectedDocumentIds,
                                 boolean enabled) {
        String scope = explicitScope(metadata);
        List<String> documentIds;
        if (!attachmentDocumentIds.isEmpty()) {
            // 本次上传附件是最强交互信号，避免历史选中的知识库文件污染附件总结或附件问答。
            scope = "attachments";
            documentIds = attachmentDocumentIds;
        } else if (!selectedDocumentIds.isEmpty()) {
            scope = scope.isBlank() ? "selected_documents" : scope;
            documentIds = selectedDocumentIds;
        } else if (enabled) {
            scope = scope.isBlank() ? "user_library" : scope;
            documentIds = List.of();
        } else {
            return new KnowledgeRoute(KnowledgeIntent.NONE, "none", List.of());
        }
        KnowledgeIntent explicitIntent = explicitIntent(metadata);
        KnowledgeIntent intent = explicitIntent == KnowledgeIntent.NONE ? inferIntent(input, scope) : explicitIntent;
        return new KnowledgeRoute(intent, scope, documentIds);
    }

    private KnowledgeIntent inferIntent(String input, String scope) {
        String text = normalizeForIntent(input);
        if (text.isBlank()) {
            return KnowledgeIntent.NONE;
        }
        if (containsAny(text, "总结", "概括", "摘要", "主要内容", "讲了什么", "有哪些内容", "里面有什么",
                "目录", "大纲", "梳理", "提炼", "读一下", "介绍一下", "说明一下")) {
            return KnowledgeIntent.SUMMARY;
        }
        if ("user_library".equals(scope) && containsAny(text, "知识库", "文档库", "资料库")) {
            return KnowledgeIntent.SUMMARY;
        }
        return KnowledgeIntent.QA;
    }

    private List<String> selectedDocumentIds(Map<String, String> metadata) {
        String raw = value(metadata, METADATA_DOCUMENT_IDS_KEY);
        if (raw.isBlank()) {
            raw = value(metadata, "knowledgeDocumentIds");
        }
        return parseIds(raw);
    }

    private List<String> idsFromMetadata(Map<String, String> metadata, String key) {
        return parseIds(value(metadata, key));
    }

    private List<String> parseIds(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        if (raw.startsWith("[")) {
            try {
                return objectMapper.readValue(raw, new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                return List.of();
            }
        }
        List<String> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return safeDocumentIds(ids);
    }

    private KnowledgeIntent explicitIntent(Map<String, String> metadata) {
        String value = value(metadata, METADATA_INTENT_KEY).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "summary", "document_summary", "overview" -> KnowledgeIntent.SUMMARY;
            case "qa", "document_qa", "knowledge_search", "search" -> KnowledgeIntent.QA;
            default -> KnowledgeIntent.NONE;
        };
    }

    private String explicitScope(Map<String, String> metadata) {
        String value = value(metadata, METADATA_SCOPE_KEY).trim();
        return switch (value) {
            case "attachments", "selected_documents", "conversation_documents", "user_library" -> value;
            default -> "";
        };
    }

    private String normalizeForIntent(String input) {
        return input == null ? "" : input.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private List<String> safeDocumentIds(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return documentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, String> safeMetadata(Map<String, String> metadata) {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private String value(Map<String, String> metadata, String key) {
        if (metadata == null || key == null) {
            return "";
        }
        return metadata.getOrDefault(key, "");
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private enum KnowledgeIntent {
        NONE("none"),
        SUMMARY("document_summary"),
        QA("document_qa");

        private final String metadataValue;

        KnowledgeIntent(String metadataValue) {
            this.metadataValue = metadataValue;
        }

        private String metadataValue() {
            return metadataValue;
        }
    }

    /**
     * @param intent 本次请求的知识库处理分支。
     * @param scope 本次请求的知识库范围：attachments、selected_documents、conversation_documents、user_library 或 none。
     * @param documentIds 本次请求限定的文档 ID；为空且 scope=user_library 时代表当前用户全库。
     */
    private record KnowledgeRoute(
            KnowledgeIntent intent,
            String scope,
            List<String> documentIds
    ) {
    }
}
