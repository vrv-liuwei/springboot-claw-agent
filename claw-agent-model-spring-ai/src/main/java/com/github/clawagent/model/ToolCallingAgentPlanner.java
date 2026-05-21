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
                ChatMessage.system("你是 ClawAgent 的工具规划器。需要工具时请通过 tool_calls 调用工具；不需要工具时不要调用工具。"),
                ChatMessage.user(task.input())
        ), options, toolRegistry.definitions());
        List<ToolCall> calls = result.toolCalls().stream()
                .filter(call -> toolRegistry.find(call.toolId()).isPresent())
                .toList();
        log.info("tool calling planner finished taskId={} toolCallCount={}", task.id(), calls.size());
        log.debug("tool calling planner content taskId={} content={} calls={}", task.id(), result.content(), calls);
        return calls;
    }
}
