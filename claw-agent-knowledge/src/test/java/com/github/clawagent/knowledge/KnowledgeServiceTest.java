package com.github.clawagent.knowledge;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.KnowledgeDocument;
import com.github.clawagent.core.KnowledgeSearchResult;
import com.github.clawagent.core.StoredFile;
import com.github.clawagent.spi.KnowledgeProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeServiceTest {

    @Test
    void selectsConfiguredProvider() {
        FakeKnowledgeProvider local = new FakeKnowledgeProvider("local");
        FakeKnowledgeProvider ragflow = new FakeKnowledgeProvider("ragflow");
        KnowledgeService service = new KnowledgeService(List.of(local, ragflow), "ragflow");

        KnowledgeDocument document = service.ingest(
                "console",
                "contract.txt",
                "text/plain",
                "text",
                "合同正文".getBytes(StandardCharsets.UTF_8),
                Map.of());

        assertEquals("ragflow", document.provider());
        assertEquals(1, ragflow.ingestCount);
        assertEquals(0, local.ingestCount);
    }

    @Test
    void rejectsUnknownProvider() {
        KnowledgeService service = new KnowledgeService(List.of(new FakeKnowledgeProvider("local")), "missing");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.search("console", "合同", List.of(), "hybrid", 5));

        assertEquals("知识库 provider 不存在：missing", error.getMessage());
    }

    @Test
    void attachmentServiceStoresFileOutsideServerLayer() {
        FakeKnowledgeProvider provider = new FakeKnowledgeProvider("local");
        FakeFileStorageProvider storage = new FakeFileStorageProvider();
        KnowledgeService knowledgeService = new KnowledgeService(List.of(provider), "local");
        AttachmentService service = new AttachmentService(storage, knowledgeService);

        List<com.github.clawagent.core.AttachmentParseResult> results = service.parse(List.of(new AttachmentService.UploadFile(
                "demo.txt",
                "text/plain",
                "附件正文".getBytes(StandardCharsets.UTF_8))), "console");

        assertEquals(1, results.size());
        assertEquals("file-1", results.get(0).id());
        assertEquals("doc-1", results.get(0).knowledgeDocumentId());
        assertEquals(1, storage.saveCount);
        assertEquals(1, provider.ingestCount);
    }

    @Test
    void attachmentSummaryReadsAttachmentDocumentInsteadOfSearching() {
        FakeKnowledgeProvider provider = new FakeKnowledgeProvider("local");
        KnowledgeService service = new KnowledgeService(List.of(provider), "local");

        AgentRequest enriched = service.enrichForModel(new AgentRequest(
                "文档总结",
                "session-1",
                "webui",
                "console",
                Map.of(
                        "attachmentKnowledgeDocumentIds", "doc-attachment",
                        "knowledge.documentIds", "[\"doc-selected\"]",
                        "knowledge.enabled", "true")));

        assertEquals("文档总结", enriched.input());
        assertEquals(0, provider.searchCount);
        assertEquals(1, provider.readCount);
        assertEquals(List.of("doc-attachment"), provider.lastReadDocumentIds);
        assertEquals("attachments", enriched.metadata().get("knowledge.scope"));
        assertEquals("document_summary", enriched.metadata().get("knowledge.intent"));
        assertEquals(true, enriched.metadata().get("knowledge.context").contains("附件片段"));
    }

    @Test
    void selectedDocumentQuestionUsesHybridSearch() {
        FakeKnowledgeProvider provider = new FakeKnowledgeProvider("local");
        KnowledgeService service = new KnowledgeService(List.of(provider), "local");

        AgentRequest enriched = service.enrichForModel(new AgentRequest(
                "刘璟甜是谁？",
                "session-1",
                "webui",
                "console",
                Map.of(
                        "knowledge.documentIds", "[\"doc-selected\"]",
                        "knowledge.enabled", "true",
                        "knowledge.scope", "selected_documents")));

        assertEquals(1, provider.searchCount);
        assertEquals(0, provider.readCount);
        assertEquals(List.of("doc-selected"), provider.lastSearchDocumentIds);
        assertEquals("document_qa", enriched.metadata().get("knowledge.intent"));
        assertEquals(true, enriched.metadata().get("knowledge.context").contains("检索片段"));
    }

    @Test
    void scopedQuestionFallsBackToDirectReadWhenSearchMisses() {
        FakeKnowledgeProvider provider = new FakeKnowledgeProvider("local");
        provider.searchMiss = true;
        KnowledgeService service = new KnowledgeService(List.of(provider), "local");

        AgentRequest enriched = service.enrichForModel(new AgentRequest(
                "刘璟甜是谁？",
                "session-1",
                "webui",
                "console",
                Map.of(
                        "knowledge.documentIds", "[\"doc-selected\"]",
                        "knowledge.enabled", "true")));

        assertEquals(1, provider.searchCount);
        assertEquals(1, provider.readCount);
        assertEquals(List.of("doc-selected"), provider.lastReadDocumentIds);
        assertEquals(true, enriched.metadata().get("knowledge.context").contains("附件片段"));
    }

    private static class FakeKnowledgeProvider implements KnowledgeProvider {
        private final String providerId;
        private int ingestCount;
        private int searchCount;
        private int readCount;
        private boolean searchMiss;
        private List<String> lastSearchDocumentIds = List.of();
        private List<String> lastReadDocumentIds = List.of();

        private FakeKnowledgeProvider(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public String id() {
            return providerId;
        }

        @Override
        public Map<String, Object> capabilities() {
            return Map.of("fileStorage", true, "hybridSearch", true);
        }

        @Override
        public KnowledgeDocument ingest(String userId, String name, String contentType, String kind, byte[] content, Map<String, String> metadata) {
            ingestCount++;
            Instant now = Instant.now();
            return new KnowledgeDocument("doc-1", userId, providerId, "remote-1",
                    name, kind, content.length, "stored.txt", "READY", Map.of(), now, now);
        }

        @Override
        public List<KnowledgeDocument> list(String userId, int limit) {
            return List.of();
        }

        @Override
        public List<KnowledgeSearchResult> search(String userId, String query, List<String> documentIds, String mode, int topK) {
            searchCount++;
            lastSearchDocumentIds = documentIds;
            if (searchMiss) {
                return List.of();
            }
            return List.of(new KnowledgeSearchResult(
                    documentIds.isEmpty() ? "doc-all" : documentIds.get(0),
                    "chunk-search-1",
                    userId,
                    "demo.txt",
                    1,
                    "检索片段：刘璟甜是文档中的项目负责人。",
                    1.0,
                    providerId,
                    Map.of()));
        }

        @Override
        public List<KnowledgeSearchResult> readDocumentChunks(String userId, List<String> documentIds, int maxChunks) {
            readCount++;
            lastReadDocumentIds = documentIds;
            return List.of(new KnowledgeSearchResult(
                    documentIds.isEmpty() ? "doc-all" : documentIds.get(0),
                    "chunk-read-1",
                    userId,
                    "demo.txt",
                    1,
                    "附件片段：这是用于总结或兜底问答的文档正文。",
                    1.0,
                    providerId,
                    Map.of()));
        }

        @Override
        public StoredFile download(String userId, String documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String userId, String documentId) {
        }
    }

    private static class FakeFileStorageProvider implements com.github.clawagent.spi.FileStorageProvider {
        private int saveCount;

        @Override
        public String id() {
            return "local";
        }

        @Override
        public StoredFile save(String originalName, String contentType, byte[] content, Map<String, String> metadata) {
            saveCount++;
            return new StoredFile("file-1", originalName, contentType, content.length, "local", "demo.txt", null, metadata);
        }

        @Override
        public StoredFile read(String fileId) {
            return new StoredFile(fileId, "demo.txt", "text/plain", 0, "local", "demo.txt", null, Map.of());
        }
    }
}
