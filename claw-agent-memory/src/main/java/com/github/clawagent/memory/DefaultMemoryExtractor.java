package com.github.clawagent.memory;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryIntent;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.spi.MemoryExtractor;
import com.github.clawagent.spi.MemoryIntentClassifier;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认候选记忆提炼器。
 * <p>
 * 该实现只做保守规则提炼：用户明确要求记住偏好、规则或决策时才生成 pending 候选。
 * </p>
 */
public class DefaultMemoryExtractor implements MemoryExtractor {
    private static final int CONTENT_LIMIT = 1_200;
    /** 记忆意图分类器，默认由 LLM 判断是否应该进入候选记忆。 */
    private final MemoryIntentClassifier classifier;

    public DefaultMemoryExtractor(MemoryIntentClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public List<MemoryItem> extract(AgentTask task, AgentSession session, List<AgentMessage> messages, String answer) {
        if (task == null || task.input() == null || task.input().isBlank()) {
            return List.of();
        }
        if (classifier == null) {
            return List.of();
        }
        MemoryIntent intent = classifier.classify(task, session, messages, answer);
        if (intent == null || !intent.shouldRemember() || intent.confidence() < 0.65) {
            return List.of();
        }
        String content = nonBlank(intent.content(), task.input().trim());
        String scopeType = normalizeScope(intent.scopeType());
        String scopeId = resolveScopeId(scopeType, task, session);
        Instant now = Instant.now();
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "runtime-extractor");
        metadata.put("extractor", getClass().getSimpleName());
        metadata.put("reviewRequired", "true");
        metadata.put("classifier", classifier.getClass().getSimpleName());
        metadata.put("classifierReason", nonBlank(intent.reason(), ""));
        // 候选只进入 pending，必须在管理台审核后才会进入模型上下文。
        return List.of(new MemoryItem(
                UUID.randomUUID().toString(),
                normalizeUserId(task.userId()),
                scopeType,
                scopeId,
                normalizeType(intent.type()),
                "pending",
                preview(content, CONTENT_LIMIT),
                preview(nonBlank(intent.summary(), content), 120),
                task.sessionId(),
                task.id(),
                0.6,
                intent.confidence(),
                metadata,
                now,
                now));
    }

    private String normalizeScope(String scopeType) {
        if ("global".equals(scopeType) || "channel".equals(scopeType) || "session".equals(scopeType)) {
            return scopeType;
        }
        // 分类器输出异常时回落到 session，避免误把私人会话内容提升到全局。
        return "session";
    }

    private String resolveScope(String text) {
        return normalizeScope(text);
    }

    private String resolveScopeId(String scopeType, AgentTask task, AgentSession session) {
        if ("global".equals(scopeType)) {
            return "";
        }
        if ("channel".equals(scopeType)) {
            return session == null ? task.channelId() : session.channelId();
        }
        return task.sessionId();
    }

    private String normalizeType(String type) {
        if ("preference".equals(type) || "rule".equals(type) || "decision".equals(type) || "fact".equals(type)) {
            return type;
        }
        return "fact";
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId.trim();
    }

    private String preview(String text, int limit) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
