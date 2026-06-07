package com.github.clawagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryIntent;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.MemoryIntentClassifier;
import com.github.clawagent.spi.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于 LLM 的记忆意图分类器。
 * <p>
 * 该实现不依赖写死关键词，而是让模型判断当前用户表达是否具有长期复用价值。
 * </p>
 */
public class LlmMemoryIntentClassifier implements MemoryIntentClassifier {
    private static final Logger log = LoggerFactory.getLogger(LlmMemoryIntentClassifier.class);
    private static final int INPUT_LIMIT = 2_000;
    private static final int ANSWER_LIMIT = 1_200;

    /** JSON 解析器，用于读取模型返回的结构化分类结果。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 项目统一模型客户端，避免记忆模块新增第二套 LLM HTTP 客户端。 */
    private final ModelClient modelClient;
    /** 模型调用参数，复用当前默认模型配置。 */
    private final ChatOptions options;

    /**
     * 创建 LLM 记忆意图分类器。
     *
     * @param modelClient 项目统一模型客户端。
     * @param options 模型调用参数。
     */
    public LlmMemoryIntentClassifier(ModelClient modelClient, ChatOptions options) {
        this.modelClient = modelClient;
        this.options = options;
    }

    @Override
    public MemoryIntent classify(AgentTask task, AgentSession session, List<AgentMessage> messages, String answer) {
        if (task == null || task.input() == null || task.input().isBlank()) {
            return noMemory("empty input");
        }
        try {
            String response = modelClient.chat(List.of(
                    ChatMessage.system(systemPrompt()),
                    ChatMessage.user(buildPrompt(task, session, messages, answer))
            ), options);
            return parse(response);
        } catch (RuntimeException e) {
            // 分类失败时不写候选，避免模型异常导致普通聊天被误沉淀。
            log.warn("memory intent classification failed taskId={} message={}", task.id(), e.getMessage());
            return noMemory("classifier failed: " + e.getMessage());
        }
    }

    private String systemPrompt() {
        return """
                你是 ClawAgent 的长期记忆意图分类器。
                你的任务是判断当前用户消息是否应该沉淀为“候选长期记忆”。

                只有满足以下条件之一才 shouldRemember=true：
                1. 用户表达了稳定偏好、长期规则、工作习惯、个人约束。
                2. 用户明确确认了后续会话可复用的事实、决策或约定。
                3. 用户要求系统在未来默认遵守某种行为。

                以下情况必须 shouldRemember=false：
                1. 普通提问、追问、让你总结文档、让你解释代码、让你执行任务。
                2. 只询问你是否记得某个内容。
                3. 一次性上下文、临时日志、临时错误、没有长期复用价值的信息。
                4. 模型无法确定时。

                scopeType 只能是 global、channel、session：
                - global：当前用户所有会话都应复用。
                - channel：只适合当前渠道复用。
                - session：只适合当前会话复用。

                type 只能是 preference、rule、decision、fact。
                只输出 JSON，不要输出解释，不要 markdown。
                JSON 字段：
                {
                  "shouldRemember": false,
                  "scopeType": "session",
                  "type": "fact",
                  "content": "",
                  "summary": "",
                  "confidence": 0.0,
                  "reason": ""
                }
                """;
    }

    private String buildPrompt(AgentTask task, AgentSession session, List<AgentMessage> messages, String answer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("会话ID：").append(task.sessionId()).append('\n');
        prompt.append("渠道：").append(task.channelId()).append('\n');
        prompt.append("用户ID：").append(task.userId()).append('\n');
        prompt.append("会话标题：").append(session == null ? "" : session.title()).append("\n\n");
        prompt.append("当前用户消息：\n").append(preview(task.input(), INPUT_LIMIT)).append("\n\n");
        prompt.append("模型回复摘要：\n").append(preview(answer, ANSWER_LIMIT)).append("\n\n");
        prompt.append("最近上下文：\n");
        int start = messages == null ? 0 : Math.max(0, messages.size() - 6);
        if (messages != null) {
            for (int i = start; i < messages.size(); i++) {
                AgentMessage message = messages.get(i);
                // 最近上下文只用于消歧，不能让分类请求本身无限变长。
                prompt.append('[').append(message.role()).append("] ")
                        .append(preview(message.content(), 400))
                        .append('\n');
            }
        }
        prompt.append("\n请判断是否生成候选长期记忆。");
        return prompt.toString();
    }

    private MemoryIntent parse(String response) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(response));
            boolean shouldRemember = node.path("shouldRemember").asBoolean(false);
            return new MemoryIntent(
                    shouldRemember,
                    node.path("scopeType").asText("session"),
                    node.path("type").asText("fact"),
                    node.path("content").asText(""),
                    node.path("summary").asText(""),
                    clamp(node.path("confidence").asDouble(0.0)),
                    node.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("memory intent response parse failed response={}", preview(response, 500));
            return noMemory("invalid classifier json");
        }
    }

    private String extractJson(String response) {
        String text = response == null ? "" : response.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private MemoryIntent noMemory(String reason) {
        return new MemoryIntent(false, "session", "fact", "", "", 0.0, reason);
    }

    private double clamp(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private String preview(String text, int limit) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
