package com.github.clawagent.memory;

import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.core.MemorySearchRequest;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalMemoryProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void searchOnlyReturnsCurrentUserActiveMemory() {
        LocalMemoryProvider provider = provider();
        provider.save(item("alice", "global", "", "active", "Java 代码关键逻辑需要中文注释"));
        provider.save(item("alice", "global", "", "pending", "pending 记忆不能进入上下文"));
        provider.save(item("bob", "global", "", "active", "Java 代码关键逻辑需要中文注释"));

        var hits = provider.search(new MemorySearchRequest(
                "alice",
                "Java 中文注释",
                List.of("global"),
                "",
                List.of("active"),
                "hybrid",
                10));

        assertEquals(1, hits.size());
        assertEquals("alice", hits.get(0).userId());
        assertEquals("active", hits.get(0).status());
    }

    @Test
    void taskScopeIsRejectedForLongTermMemory() {
        LocalMemoryProvider provider = provider();

        assertThrows(IllegalArgumentException.class, () ->
                provider.save(item("alice", "task", "task-1", "active", "task 不能作为长期记忆 scope")));
    }

    @Test
    void saveBuildsUserScopedJVectorIndex() {
        LocalMemoryProvider provider = provider();
        provider.save(item("alice", "global", "", "active", "劳保采购合同处理经验"));

        assertTrue(Files.exists(tempDir.resolve("vectors").resolve("alice").resolve("jvector.graph")));
    }

    @Test
    void deleteCleansDbFtsVectorAndMarkdownFile() {
        LocalMemoryProvider provider = provider();
        MemoryItem saved = provider.save(item("alice", "global", "", "active", "删除时清理所有索引"));

        provider.delete("alice", saved.id());

        assertTrue(provider.search(new MemorySearchRequest(
                "alice",
                "删除 清理 索引",
                List.of("global"),
                "",
                List.of("active"),
                "hybrid",
                10)).isEmpty());
        assertFalse(Files.exists(tempDir.resolve("files").resolve("alice").resolve(saved.id() + ".md")));
    }

    private LocalMemoryProvider provider() {
        return new LocalMemoryProvider(
                tempDir.resolve("clawagent.db"),
                tempDir.resolve("files"),
                tempDir.resolve("vectors"),
                new FakeEmbeddingClient(),
                new EmbeddingOptions("fake", 4, 5));
    }

    private MemoryItem item(String userId, String scopeType, String scopeId, String status, String content) {
        Instant now = Instant.now();
        return new MemoryItem(
                UUID.randomUUID().toString(),
                userId,
                scopeType,
                scopeId,
                "preference",
                status,
                content,
                content,
                "session-1",
                "task-1",
                0.8,
                0.9,
                Map.of(),
                now,
                now);
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
            double a = text.contains("Java") || text.contains("代码") ? 1.0 : 0.0;
            double b = text.contains("中文") || text.contains("注释") ? 1.0 : 0.0;
            double c = text.contains("删除") || text.contains("清理") ? 1.0 : 0.0;
            double d = text.length() / 100.0;
            return List.of(a, b, c, d);
        }
    }
}
