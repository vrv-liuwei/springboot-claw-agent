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
                ChatMessage.user(userPromptWithSessionContext(task))
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
        prompt.append("参数约定：weather 使用 city；time 无参数；builtin.web.search 必须使用 query，其他可选参数以工具定义中当前 Provider 暴露的字段为准；builtin.web.fetch 使用 url，默认 format=markdown、extractMode=readable，可选 extractMode=readable/raw、maxOutputChars、headers(JSON)、timeoutMs、maxBytes。");
        prompt.append("web.search/web.fetch 返回 artifactId 和摘要；已有 artifactId 时，如需原文细节必须优先调用 builtin.content.read，避免重复请求相同 URL 或重复搜索相同 query。");
        prompt.append("实时信息、最新版本、新闻、资料检索、事实核验优先调用 builtin.web.search；拿到具体 URL 后，如需阅读全文再调用 builtin.web.fetch；如果 Observation 已有 artifactId，则用 builtin.content.read 按 query/chunk 读取缓存内容。");
        prompt.append("复杂、多步骤、需要拆解的任务，优先调用 builtin.todo.create_plan，items 参数为 JSON 数组字符串。");
        prompt.append("Todo 执行规则：用户说执行第 N 步时，先用 builtin.todo.update_item 将 order=N 标记为 running，再调用完成该步骤所需工具，成功后再标记 completed，失败则标记 failed。");
        prompt.append("用户说执行全部 Todo 时，按 order 从小到大执行所有 pending/running Todo；未知下一步需要先调用 builtin.todo.list 获取当前会话 Todo。");
        return prompt.toString();
    }

    static String sessionContext(AgentTask task) {
        String context = task.metadata().getOrDefault("runtime.sessionContext", "");
        return context == null ? "" : context.trim();
    }

    static String memoryContext(AgentTask task) {
        String context = task.metadata().getOrDefault("runtime.memoryContext", "");
        return context == null ? "" : context.trim();
    }

    static String knowledgeContext(AgentTask task) {
        String context = task.metadata().getOrDefault("knowledge.context", "");
        return context == null ? "" : context.trim();
    }

    static String userPromptWithSessionContext(AgentTask task) {
        StringBuilder prompt = new StringBuilder();
        String knowledge = knowledgeContext(task);
        if (!knowledge.isBlank()) {
            prompt.append("知识库上下文：\n").append(knowledge).append("\n\n");
        }
        String memory = memoryContext(task);
        if (!memory.isBlank()) {
            prompt.append("记忆上下文：\n").append(memory).append("\n\n");
        }
        String context = sessionContext(task);
        if (!context.isBlank()) {
            prompt.append("近期会话上下文：\n").append(context).append("\n\n");
        }
        prompt.append("用户请求：").append(task.input());
        return prompt.toString();
    }

    private List<ToolCall> parseToolCalls(String content) {
        try {
            JsonNode callsNode = objectMapper.readTree(extractJsonObject(stripCodeFence(content))).path("calls");
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

    private String extractJsonObject(String content) throws java.io.IOException {
        String text = content.trim();
        try {
            objectMapper.readTree(text);
            return text;
        } catch (java.io.IOException ignored) {
            // 整段不是纯 JSON 时，继续扫描其中的 JSON 对象。
        }
        String latest = "";
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') {
                continue;
            }
            String candidate = readBalancedJsonCandidate(text, start);
            if (candidate.isBlank()) {
                continue;
            }
            JsonNode node = objectMapper.readTree(candidate);
            if (node.has("calls")) {
                // 模型偶尔会先输出草稿 JSON 再自我修正，取最后一个 calls 对象作为最终计划。
                latest = candidate;
            }
        }
        return latest.isBlank() ? text : latest;
    }

    private String readBalancedJsonCandidate(String text, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return "";
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
