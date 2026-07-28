package com.github.clawagent.intent;

import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRoutingServiceTest {

    @Test
    void mediumRiskIntentRequiresConfirmationBeforeHandlerRuns() {
        InMemoryPendingActionService pendingActionService = new InMemoryPendingActionService();
        Counter counter = new Counter();
        IntentRoutingService service = service(pendingActionService, Map.of(
                "sessionCommand.clearContext",
                context -> {
                    counter.value++;
                    return IntentHandlerResult.handled("cleared");
                }));

        IntentRouteResult route = service.route(new IntentRequest(
                "帮我清除当前会话上下文",
                "session-1",
                "feishu",
                "user-1",
                Map.of()));

        assertTrue(route.handled());
        assertEquals(0, counter.value);
        assertTrue(route.answer().contains("请回复：确认执行"));

        PendingActionResult confirm = pendingActionService.handleUserInput("session-1", "feishu", "user-1", "确认执行");

        assertTrue(confirm.handled());
        assertEquals("cleared", confirm.answer());
        assertEquals(1, counter.value);
    }

    @Test
    void blankInputWithAttachmentDocumentRoutesToDocumentSummaryModelContext() {
        IntentRoutingService service = service(new InMemoryPendingActionService(), Map.of());

        IntentRouteResult route = service.route(new IntentRequest(
                "",
                "session-1",
                "ddio",
                "user-1",
                Map.of("attachmentKnowledgeDocumentIds", "doc-1")));

        assertTrue(route.matched());
        assertTrue(route.passToModel());
        assertEquals("document.summary", route.intentId());
        assertEquals("document_summary", route.metadata().get("knowledge.intent"));
        assertEquals("attachments", route.metadata().get("knowledge.scope"));
    }

    @Test
    void attachmentWithQuestionDefaultsToDocumentQaModelContext() {
        IntentRoutingService service = service(new InMemoryPendingActionService(), Map.of());

        IntentRouteResult route = service.route(new IntentRequest(
                "这里面有没有预算说明",
                "session-1",
                "ddio",
                "user-1",
                Map.of("attachmentKnowledgeDocumentIds", "doc-1")));

        assertTrue(route.matched());
        assertTrue(route.passToModel());
        assertEquals("document.qa", route.intentId());
        assertEquals("document_qa", route.metadata().get("knowledge.intent"));
        assertEquals("attachments", route.metadata().get("knowledge.scope"));
    }

    @Test
    void embeddingExamplesArePrecomputedAndRouteOnlyEmbedsQueryOnce() {
        FakeEmbeddingClient embeddingClient = new FakeEmbeddingClient();
        List<IntentDefinition> definitions = List.of(
                new IntentDefinition("session.clear", "清除当前会话上下文", IntentRisk.LOW, IntentRouteMode.HANDLER,
                        "sessionCommand.clearContext", 0.78, Map.of(), List.of("清除上下文"), List.of("怎么清除上下文")),
                new IntentDefinition("commands.help", "查看命令列表", IntentRisk.LOW, IntentRouteMode.HANDLER,
                        "commands.help", 0.78, Map.of(), List.of("命令列表"), List.of()));
        IntentRoutingService service = new IntentRoutingService(
                definitions,
                new IntentHandlerRegistry(Map.of()),
                new InMemoryPendingActionService(),
                embeddingClient,
                new EmbeddingOptions("fake", 0, 60),
                true);

        IntentRouteResult route = service.route(new IntentRequest(
                "语义清理",
                "session-1",
                "feishu",
                "user-1",
                Map.of()));

        assertTrue(route.matched());
        assertEquals("session.clear", route.intentId());
        assertTrue(embeddingClient.batchCallCount >= 1);
        assertEquals(1, embeddingClient.singleCallCount);
    }

    @Test
    void workspaceOpenIsNotExposedAsSystemIntent() {
        IntentRoutingService service = service(new InMemoryPendingActionService(), Map.of());

        IntentRouteResult route = service.route(new IntentRequest(
                "打开工作区 D:/tmp/project",
                "session-1",
                "feishu",
                "user-1",
                Map.of()));

        assertFalse(route.matched());
    }

    private IntentRoutingService service(PendingActionService pendingActionService, Map<String, IntentHandler> handlers) {
        List<IntentDefinition> definitions = new IntentCatalogLoader().loadDefaultCatalog(0.78);
        return new IntentRoutingService(definitions, new IntentHandlerRegistry(handlers), pendingActionService, null, null, true);
    }

    private static class Counter {
        int value;
    }

    private static class FakeEmbeddingClient implements EmbeddingClient {
        int singleCallCount;
        int batchCallCount;

        @Override
        public EmbeddingResult embed(String text, EmbeddingOptions options) {
            singleCallCount++;
            return result(text);
        }

        @Override
        public List<EmbeddingResult> embedAll(List<String> texts, EmbeddingOptions options) {
            batchCallCount++;
            return texts.stream().map(this::result).toList();
        }

        private EmbeddingResult result(String text) {
            String value = text == null ? "" : text;
            if (value.contains("怎么清除")) {
                return new EmbeddingResult("fake", List.of(0.0, -1.0), 0, 0);
            }
            if (value.contains("清理") || value.contains("清除") || value.contains("/clear") || value.contains("语义清理")) {
                // 正例向量和查询向量保持一致，用来验证请求期只计算用户输入一次。
                return new EmbeddingResult("fake", List.of(1.0, 0.0), 0, 0);
            }
            return new EmbeddingResult("fake", List.of(0.0, 1.0), 0, 0);
        }
    }
}
