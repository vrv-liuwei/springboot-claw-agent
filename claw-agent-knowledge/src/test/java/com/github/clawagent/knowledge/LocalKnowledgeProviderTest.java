package com.github.clawagent.knowledge;

import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalKnowledgeProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void searchIsolatedByUserId() {
        LocalKnowledgeProvider provider = new LocalKnowledgeProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));

        provider.ingest(
                "alice",
                "alice-contract.txt",
                "text/plain",
                "text",
                "劳保用品采购合同 甲方 乙方 安全帽".getBytes(StandardCharsets.UTF_8),
                Map.of());
        provider.ingest(
                "bob",
                "bob-contract.txt",
                "text/plain",
                "text",
                "新能源设备采购合同 电池 逆变器".getBytes(StandardCharsets.UTF_8),
                Map.of());

        var alice = provider.search("alice", "劳保用品采购合同", List.of(), "hybrid", 10);
        var bob = provider.search("bob", "劳保用品采购合同", List.of(), "hybrid", 10);

        assertFalse(alice.isEmpty());
        assertEquals("alice", alice.get(0).userId());
        assertEquals(0, bob.size());
    }

    @Test
    void ingestBuildsUserScopedJVectorIndex() {
        LocalKnowledgeProvider provider = new LocalKnowledgeProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));

        provider.ingest(
                "alice",
                "alice-contract.txt",
                "text/plain",
                "text",
                "劳保用品采购合同 甲方 乙方 安全帽".getBytes(StandardCharsets.UTF_8),
                Map.of());

        Path indexFile = tempDir.resolve("vectors").resolve("alice").resolve("jvector.graph");
        assertTrue(Files.exists(indexFile), "本地向量索引必须真实落盘到用户隔离目录");
    }

    @Test
    void hybridSearchUsesRankOnlyRrfScore() {
        LocalKnowledgeProvider provider = new LocalKnowledgeProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));

        provider.ingest(
                "alice",
                "alice-contract.txt",
                "text/plain",
                "text",
                "劳保用品采购合同 甲方 乙方 安全帽".getBytes(StandardCharsets.UTF_8),
                Map.of());

        var hits = provider.search("alice", "劳保用品采购合同", List.of(), "hybrid", 10);

        assertEquals(1, hits.size());
        assertEquals(1.0 / 61.0, hits.get(0).score(), 0.000001);
    }

    @Test
    void readDocumentChunksKeepsUserAndDocumentScope() {
        LocalKnowledgeProvider provider = new LocalKnowledgeProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));

        var alice = provider.ingest(
                "alice",
                "alice-summary.txt",
                "text/plain",
                "text",
                "第一段内容\n第二段内容".getBytes(StandardCharsets.UTF_8),
                Map.of());
        var bob = provider.ingest(
                "bob",
                "bob-summary.txt",
                "text/plain",
                "text",
                "其他用户内容".getBytes(StandardCharsets.UTF_8),
                Map.of());

        var aliceChunks = provider.readDocumentChunks("alice", List.of(alice.id(), bob.id()), 10);
        var bobChunks = provider.readDocumentChunks("bob", List.of(alice.id()), 10);

        assertEquals(1, aliceChunks.size());
        assertEquals(alice.id(), aliceChunks.get(0).documentId());
        assertEquals("alice", aliceChunks.get(0).userId());
        assertTrue(bobChunks.isEmpty());
    }

    @Test
    void duplicateContentReusesExistingDocumentPerUser() {
        LocalKnowledgeProvider provider = new LocalKnowledgeProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));

        byte[] content = "知乎1 文档内容".getBytes(StandardCharsets.UTF_8);
        var first = provider.ingest("alice", "知乎1.docx", "text/plain", "word", content, Map.of());
        var second = provider.ingest("alice", "知乎1-copy.docx", "text/plain", "word", content, Map.of());
        var bob = provider.ingest("bob", "知乎1.docx", "text/plain", "word", content, Map.of());

        assertEquals(first.id(), second.id());
        assertEquals(1, provider.list("alice", 10).size());
        assertEquals(1, provider.list("bob", 10).size());
        assertEquals(bob.id(), provider.list("bob", 10).get(0).id());
    }

    private static class FakeEmbeddingClient implements EmbeddingClient {
        @Override
        public EmbeddingResult embed(String text, EmbeddingOptions options) {
            return new EmbeddingResult(options.model(), vector(text), 0, 0);
        }

        @Override
        public List<EmbeddingResult> embedAll(List<String> texts, EmbeddingOptions options) {
            return texts.stream().map(text -> new EmbeddingResult(options.model(), vector(text), 0, 0)).toList();
        }

        private static List<Double> vector(String text) {
            double a = text.contains("劳保") ? 1.0 : 0.0;
            double b = text.contains("采购") ? 1.0 : 0.0;
            double c = text.contains("合同") ? 1.0 : 0.0;
            double d = text.length() / 100.0;
            return List.of(a, b, c, d);
        }
    }
}
