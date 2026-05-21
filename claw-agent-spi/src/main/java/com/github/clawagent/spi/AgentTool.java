package com.github.clawagent.spi;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;

/**
 * AgentTool 是 ClawAgent 的标准工具接口。
 * 所有本地工具、Skill 工具和 MCP 工具最终都会适配为这个接口，方便统一治理。
 */
public interface AgentTool {
    ToolDefinition definition();

    ToolResult execute(ToolCall call, AgentContext context);
}
