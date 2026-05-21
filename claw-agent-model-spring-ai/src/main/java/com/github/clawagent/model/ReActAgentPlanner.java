package com.github.clawagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.spi.AgentPlan;
import com.github.clawagent.spi.AgentReActPlanner;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct Planner。
 * 每轮读取历史 observation 后决定继续调用工具，或直接给出最终答案。
 */
public class ReActAgentPlanner implements AgentReActPlanner {
    private static final Logger log = LoggerFactory.getLogger(ReActAgentPlanner.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModelClient modelClient;
    private final ChatOptions options;
    private final AgentToolRegistry toolRegistry;

    public ReActAgentPlanner(ModelClient modelClient, ChatOptions options, AgentToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.options = options;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public List<ToolCall> plan(AgentTask task) {
        return planNext(task, List.of(), 1).calls();
    }

    @Override
    public AgentPlan planNext(AgentTask task, List<AgentStep> observations, int round) {
        log.info("react planner started taskId={} model={} round={} observationCount={}",
                task.id(), options.model(), round, observations.size());
        String content = modelClient.chat(List.of(
                ChatMessage.system(systemPrompt()),
                ChatMessage.user(buildUserPrompt(task, observations, round))
        ), options);
        AgentPlan plan = parsePlan(content);
        log.info("react planner finished taskId={} round={} finished={} toolCallCount={}",
                task.id(), round, plan.finished(), plan.calls().size());
        log.debug("react planner raw response taskId={} round={} response={}", task.id(), round, content);
        return plan;
    }

    private String systemPrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是 ClawAgent 的 ReAct Planner，只输出 JSON，不输出解释。\n");
        prompt.append("你可以根据 Observation 多轮规划工具调用。\n");
        prompt.append("如果还需要工具，输出：{\"thought\":\"简短思考\",\"finished\":false,\"calls\":[{\"toolId\":\"工具ID\",\"arguments\":{\"参数名\":\"参数值\"}}]}。\n");
        prompt.append("如果已经可以回答，输出：{\"thought\":\"简短思考\",\"finished\":true,\"answer\":\"最终答案\",\"calls\":[]}。\n");
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

    private String buildUserPrompt(AgentTask task, List<AgentStep> observations, int round) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户请求：").append(task.input()).append("\n");
        prompt.append("当前轮次：").append(round).append("\n\n");
        if (observations.isEmpty()) {
            prompt.append("Observation：暂无工具结果。\n");
            return prompt.toString();
        }
        prompt.append("Observation：\n");
        for (AgentStep step : observations) {
            // observation 保留必要字段和截断后的输出，防止工具返回过长挤占上下文。
            prompt.append("- tool=").append(step.name())
                    .append(", status=").append(step.status())
                    .append(", input=").append(step.input())
                    .append(", output=").append(preview(step.output(), 1200))
                    .append(", error=").append(preview(step.error(), 500))
                    .append("\n");
        }
        return prompt.toString();
    }

    private AgentPlan parsePlan(String content) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(content));
            if (root.path("finished").asBoolean(false)) {
                return AgentPlan.finalAnswer(root.path("answer").asText(""));
            }
            return AgentPlan.calls(parseToolCalls(root.path("calls")));
        } catch (Exception e) {
            throw new IllegalStateException("模型 ReAct 计划不是有效 JSON：" + content, e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode callsNode) {
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

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
