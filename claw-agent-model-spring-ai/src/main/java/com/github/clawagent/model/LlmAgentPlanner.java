package com.github.clawagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.AgentToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于真实模型的 Planner。
 * 模型只负责产出结构化工具计划，工具执行仍由 Runtime 接管，避免模型直接执行高风险动作。
 */
public class LlmAgentPlanner implements AgentPlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmAgentPlanner.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelClient modelClient;
    private final ChatOptions options;
    private final AgentToolRegistry toolRegistry;

    public LlmAgentPlanner(ModelClient modelClient, ChatOptions options, AgentToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.options = options;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public List<ToolCall> plan(AgentTask task) {
        log.info("llm planner started taskId={} model={}", task.id(), options.model());
        String content = modelClient.chat(List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user("用户请求：" + task.input())
        ), options);
        List<ToolCall> calls = parseToolCalls(content);
        log.info("llm planner finished taskId={} toolCallCount={}", task.id(), calls.size());
        log.debug("llm planner raw response taskId={} response={}", task.id(), content);
        return calls;
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 ClawAgent 的工具规划器，只输出 JSON，不输出解释。\n");
        prompt.append("如果用户请求需要工具，返回 {\"calls\":[{\"toolId\":\"工具ID\",\"arguments\":{\"参数名\":\"参数值\"}}]}。\n");
        prompt.append("如果不需要工具，返回 {\"calls\":[]}。\n");
        prompt.append("只能使用下面列出的工具，不能编造工具 ID。\n");
        prompt.append("可用工具：\n");
        for (ToolDefinition definition : toolRegistry.definitions()) {
            prompt.append("- id=").append(definition.id())
                    .append(", name=").append(definition.name())
                    .append(", description=").append(definition.description())
                    .append(", risk=").append(definition.riskLevel())
                    .append("\n");
        }
        prompt.append("参数约定：weather 使用 city；time 无参数；builtin.web.fetch 使用 url，可选 format=html/text/markdown/json、headers(JSON)、timeoutMs、maxBytes。");
        return prompt.toString();
    }

    private List<ToolCall> parseToolCalls(String content) {
        try {
            JsonNode callsNode = objectMapper.readTree(stripCodeFence(content)).path("calls");
            List<ToolCall> calls = new ArrayList<>();
            if (!callsNode.isArray()) {
                return calls;
            }
            for (JsonNode callNode : callsNode) {
                String toolId = callNode.path("toolId").asText();
                if (toolRegistry.find(toolId).isEmpty()) {
                    continue;
                }
                calls.add(new ToolCall(toolId, readStringMap(callNode.path("arguments"))));
            }
            return calls;
        } catch (Exception e) {
            throw new IllegalStateException("模型工具计划不是有效 JSON：" + content, e);
        }
    }

    private Map<String, String> readStringMap(JsonNode node) {
        Map<String, String> arguments = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return arguments;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            arguments.put(field.getKey(), field.getValue().asText());
        }
        return arguments;
    }

    private String stripCodeFence(String content) {
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }
}
