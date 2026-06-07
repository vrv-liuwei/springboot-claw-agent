package com.github.clawagent.memory;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.MemoryIntent;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.MemoryIntentClassifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMemoryExtractorTest {
    @Test
    void extractsOnlyExplicitMemoryCommand() {
        DefaultMemoryExtractor extractor = new DefaultMemoryExtractor(rememberIntent());

        var hits = extractor.extract(
                task("以后请记住，我喜欢中文注释，回答不要太啰嗦。"),
                session(),
                List.of(),
                "已记录");

        assertEquals(1, hits.size());
        assertEquals("pending", hits.get(0).status());
        assertEquals("rule", hits.get(0).type());
    }

    @Test
    void ignoresQuestionAboutExistingPreference() {
        DefaultMemoryExtractor extractor = new DefaultMemoryExtractor(noMemoryIntent());

        var hits = extractor.extract(
                task("你还记得我的回答偏好吗？"),
                session(),
                List.of(),
                "记得");

        assertTrue(hits.isEmpty());
    }

    @Test
    void ignoresNormalDocumentQuestion() {
        DefaultMemoryExtractor extractor = new DefaultMemoryExtractor(noMemoryIntent());

        var hits = extractor.extract(
                task("文档总结"),
                session(),
                List.of(),
                "这是文档摘要");

        assertTrue(hits.isEmpty());
    }

    private AgentTask task(String input) {
        return new AgentTask("task-1", new AgentRequest(input, "session-1", "webui", "console", Map.of()));
    }

    private AgentSession session() {
        return new AgentSession("session-1", "console", "webui", "测试会话", Map.of());
    }

    private MemoryIntentClassifier rememberIntent() {
        return (task, session, messages, answer) -> new MemoryIntent(
                true,
                "session",
                "rule",
                "用户喜欢中文注释，回答不要太啰嗦。",
                "中文注释，回答简洁",
                0.9,
                "用户表达了长期回答偏好");
    }

    private MemoryIntentClassifier noMemoryIntent() {
        return (task, session, messages, answer) -> new MemoryIntent(
                false,
                "session",
                "fact",
                "",
                "",
                0.0,
                "普通问题，不沉淀为长期记忆");
    }
}
