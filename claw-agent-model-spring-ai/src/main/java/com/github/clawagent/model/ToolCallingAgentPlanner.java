package com.github.clawagent.model;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.ToolCallingModelClient;
import com.github.clawagent.spi.ToolCallingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 原生 Tool Calling Planner。
 * 优先使用模型的 tools/tool_calls 协议；如果模型客户端不支持，则回退到 JSON Planner。
 */
public class ToolCallingAgentPlanner implements AgentPlanner {
    private static final Logger log = LoggerFactory.getLogger(ToolCallingAgentPlanner.class);

    private final ModelClient modelClient;
    private final ChatOptions options;
    private final AgentToolRegistry toolRegistry;
    private final LlmAgentPlanner fallbackPlanner;

    public ToolCallingAgentPlanner(ModelClient modelClient, ChatOptions options, AgentToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.options = options;
        this.toolRegistry = toolRegistry;
        this.fallbackPlanner = new LlmAgentPlanner(modelClient, options, toolRegistry);
    }

    @Override
    public List<ToolCall> plan(AgentTask task) {
        if (!(modelClient instanceof ToolCallingModelClient toolCallingModelClient)) {
            log.warn("tool calling planner fallback because model client does not implement ToolCallingModelClient");
            return fallbackPlanner.plan(task);
        }
        log.info("tool calling planner started taskId={} model={}", task.id(), options.model());
        ToolCallingResult result = toolCallingModelClient.chatWithTools(List.of(
                ChatMessage.system("你是 ClawAgent 的工具规划器。需要工具时请通过 tool_calls 调用工具；不需要工具时不要调用工具。实时信息、最新版本、新闻、资料检索、事实核验优先调用 builtin.web.search；拿到具体 URL 后，如需阅读全文再调用 builtin.web.fetch。web.search/web.fetch 会返回 artifactId 和摘要；已有 artifactId 时优先调用 builtin.content.read 按 query/chunk 读取缓存，避免重复请求 URL 或重复搜索。web-fetch 默认使用 extractMode=readable 减少网页噪声；复杂多步骤任务优先创建 todo plan。启动、构建、测试项目时必须先确认真实项目目录，已知项目路径就把 cwd 传到 execute/process 工具；只知道 workspace 根目录时先检查一级子目录，多个候选项目时先询问用户确认，不要直接执行。用户说执行第 N 步时，用 builtin.todo.update_item 的 order=N 更新状态；用户说执行全部 Todo 时，按 order 顺序推进 pending/running Todo。"),
                ChatMessage.user(LlmAgentPlanner.userPromptWithSessionContext(task))
        ), options, toolRegistry.definitions());
        List<ToolCall> calls = result.toolCalls().stream()
                .filter(call -> toolRegistry.find(call.toolId()).isPresent())
                .toList();
        log.info("tool calling planner finished taskId={} toolCallCount={}", task.id(), calls.size());
        log.debug("tool calling planner content taskId={} content={} calls={}", task.id(), result.content(), calls);
        return calls;
    }
}
