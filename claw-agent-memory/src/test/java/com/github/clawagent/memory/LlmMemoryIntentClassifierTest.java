package com.github.clawagent.memory;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmMemoryIntentClassifierTest {
    @Test
    void parsesPositiveClassifierJson() {
        LlmMemoryIntentClassifier classifier = new LlmMemoryIntentClassifier(
                new FakeModelClient("""
                        {
                          "shouldRemember": true,
                          "scopeType": "global",
                          "type": "preference",
                          "content": "用户偏好中文注释和简洁回答。",
                          "summary": "中文注释，简洁回答",
                          "confidence": 0.92,
                          "reason": "长期回答偏好"
                        }
                        """),
                new ChatOptions("fake", 0.0, 5));

        var intent = classifier.classify(task("我希望你以后回答都更简洁，并且 Java 代码加中文注释。"), session(), List.of(), "");

        assertTrue(intent.shouldRemember());
        assertEquals("global", intent.scopeType());
        assertEquals("preference", intent.type());
        assertEquals("中文注释，简洁回答", intent.summary());
    }

    @Test
    void parsesNegativeClassifierJson() {
        LlmMemoryIntentClassifier classifier = new LlmMemoryIntentClassifier(
                new FakeModelClient("""
                        {
                          "shouldRemember": false,
                          "scopeType": "session",
                          "type": "fact",
                          "content": "",
                          "summary": "",
                          "confidence": 0.1,
                          "reason": "普通问题"
                        }
                        """),
                new ChatOptions("fake", 0.0, 5));

        var intent = classifier.classify(task("你还记得我的回答偏好吗？"), session(), List.of(), "");

        assertFalse(intent.shouldRemember());
        assertEquals("普通问题", intent.reason());
    }

    private AgentTask task(String input) {
        return new AgentTask("task-1", new AgentRequest(input, "session-1", "webui", "console", Map.of()));
    }

    private AgentSession session() {
        return new AgentSession("session-1", "测试会话", "webui", "console", Map.of());
    }

    private record FakeModelClient(String response) implements ModelClient {
        @Override
        public String chat(List<ChatMessage> messages, ChatOptions options) {
            return response;
        }
    }
}
