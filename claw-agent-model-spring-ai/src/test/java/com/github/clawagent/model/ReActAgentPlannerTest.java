package com.github.clawagent.model;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentPlan;
import com.github.clawagent.spi.AgentReActPlanner;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReActAgentPlannerTest {
    @Test
    void preservesArrayArgumentsAsJsonString() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(new ExecuteToolStub()));
        ModelClient modelClient = (messages, options) -> """
                {"thought":"执行 git status","finished":false,"calls":[{"toolId":"builtin.execute.command","arguments":{"command":"git","args":["status","--short"],"cwd":"D:/workspace/codex/springboot-claw-agent"}}]}
                """;
        AgentReActPlanner planner = new ReActAgentPlanner(modelClient, new ChatOptions("test", 0.2, 30), registry);

        AgentPlan plan = planner.planNext(new AgentTask("task-1", new AgentRequest("git status", "session-1", "webui", "console", Map.of())), List.of(), 1);

        ToolCall call = plan.calls().get(0);
        // 数组参数必须保留为 JSON 字符串，execute 工具才能解析成真实命令参数。
        assertEquals("[\"status\",\"--short\"]", call.arguments().get("args"));
    }

    private static class ExecuteToolStub implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return ToolDefinition.high("builtin.execute.command", "Execute Command", "test", Map.of());
        }

        @Override
        public ToolResult execute(ToolCall call, AgentContext context) {
            return ToolResult.success("ok");
        }
    }
}
