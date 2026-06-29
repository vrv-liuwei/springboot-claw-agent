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

import java.io.IOException;
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
        prompt.append("Todo 状态展示必须一致：pending/未执行/待执行只能使用灰点或 ⏳，running/执行中使用红点或 🔴，completed/已完成/完成才可以使用 ✅，failed/失败使用 ❌；禁止输出“✅ pending”“✅ 未执行”“✅ 待执行”。\n");
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
        prompt.append("用户请求属于简单本地操作时必须走最短路径，例如“启动项目/运行项目/查看状态/执行测试/查看端口/安装依赖”：不要创建 Todo，不要读取业务源码，不要为了确认而遍历文件；只允许做必要的目录/构建文件检查，然后直接调用 execute/process 类工具。");
        prompt.append("启动项目时，优先使用 builtin.process.start 托管长期进程；只有需要一次性命令输出时才使用 builtin.execute.command。已知项目路径时不要先搜索源码。");
        prompt.append("执行启动/构建/测试命令前必须确认真实项目目录：如果用户给了项目路径，cwd 必须传该路径；如果只知道 workspace 根目录，先检查一级子目录里的 pom.xml/package.json；多个候选项目时不要执行，先询问用户确认。");
        prompt.append("只有在编译、启动、测试失败且错误明确指向某个文件时，才读取该文件；禁止无错误地批量读取 Service/Controller/Entity 等业务源码。");
        prompt.append("复杂、多步骤、需要拆解的任务，优先调用 builtin.todo.create_plan，items 参数为 JSON 数组字符串。");
        prompt.append("单步或低复杂度任务禁止调用 builtin.todo.create_plan，包括启动项目、停止项目、查看日志、查看 git 状态、运行单条命令、读取单个文件。");
        prompt.append("如果用户是在同一会话中说“继续”“继续完成”“接着做”，并且近期上下文已有未完成 Todo，禁止重新调用 builtin.todo.create_plan，必须继续更新现有 Todo。");
        prompt.append("Todo 执行规则：用户说执行第 N 步时，先用 builtin.todo.update_item 将 order=N 标记为 running，再调用完成该步骤所需工具，成功后再标记 completed，失败则标记 failed。");
        prompt.append("用户说执行全部 Todo 时，按 order 从小到大执行所有 pending/running Todo；每轮 Observation 后继续规划下一步，直到全部完成或遇到阻塞。");
        prompt.append("如果 Observation 提示工具被失败恢复策略阻断，必须换 API/路径/参数，或把当前 Todo 标记为 failed 并说明阻塞原因；禁止重复调用相同工具和相同参数。");
        return prompt.toString();
    }

    private String buildUserPrompt(AgentTask task, List<AgentStep> observations, int round) {
        StringBuilder prompt = new StringBuilder();
        String knowledge = LlmAgentPlanner.knowledgeContext(task);
        if (!knowledge.isBlank()) {
            prompt.append("知识库上下文：\n").append(knowledge).append("\n\n");
        }
        String attachments = LlmAgentPlanner.attachmentContext(task);
        if (!attachments.isBlank()) {
            prompt.append("附件理解上下文：\n").append(attachments).append("\n\n");
        }
        String memory = LlmAgentPlanner.memoryContext(task);
        if (!memory.isBlank()) {
            prompt.append("记忆上下文：\n").append(memory).append("\n\n");
        }
        String context = LlmAgentPlanner.sessionContext(task);
        if (!context.isBlank()) {
            prompt.append("近期会话上下文：\n").append(context).append("\n\n");
        }
        String resume = LlmAgentPlanner.resumeContext(task);
        if (!resume.isBlank()) {
            prompt.append("任务恢复点：\n").append(resume).append("\n\n");
        }
        prompt.append("用户请求：").append(LlmAgentPlanner.displayUserInput(task)).append("\n");
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
            JsonNode root = objectMapper.readTree(extractJsonObject(stripCodeFence(content)));
            if (root.path("finished").asBoolean(false)) {
                return AgentPlan.finalAnswer(root.path("answer").asText(""));
            }
            return AgentPlan.calls(parseToolCalls(root.path("calls")));
        } catch (Exception e) {
            throw new IllegalStateException("模型 ReAct 计划不是有效 JSON：" + content, e);
        }
    }

    private String extractJsonObject(String content) throws IOException {
        String text = content.trim();
        try {
            objectMapper.readTree(text);
            return text;
        } catch (IOException ignored) {
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
            if (node.has("finished") || node.has("calls")) {
                // DeepSeek 有时会先输出草稿 JSON 再自我修正，取最后一个计划对象作为最终决策。
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
            // 工具参数统一传给 ToolCall 的字符串 Map；数组/对象必须保留 JSON，否则 args=["status","--short"] 会被 asText() 读成空串。
            arguments.put(field.getKey(), field.getValue().isTextual()
                    ? field.getValue().asText()
                    : field.getValue().toString());
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
