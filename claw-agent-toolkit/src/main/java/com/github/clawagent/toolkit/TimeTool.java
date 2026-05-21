package com.github.clawagent.toolkit;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.time.ZonedDateTime;

public class TimeTool implements AgentTool {
    @Override
    public ToolDefinition definition() {
        return ToolDefinition.low("builtin.time", "Current Server Time", "返回当前服务器时间");
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        return ToolResult.success(ZonedDateTime.now().toString());
    }
}
