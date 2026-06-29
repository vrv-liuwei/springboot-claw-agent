package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProcessStatusTool extends ProcessToolSupport implements AgentTool {
    public ProcessStatusTool(ManagedProcessStore store, ExecuteToolkitProperties properties) {
        super(store, properties);
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("pid", ToolDefinition.integerProperty("可选进程 PID；不传则列出本次运行已托管的全部进程"));
        return ToolDefinition.low(
                "builtin.process.status",
                "Process Status",
                "查看后台进程是否仍在运行。",
                ToolDefinition.objectSchema(schema, false, List.of()));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        String rawPid = call.arguments().get("pid");
        if (rawPid != null && !rawPid.isBlank()) {
            long pid = Long.parseLong(rawPid.trim());
            return store.get(pid)
                    .map(this::format)
                    .map(ToolResult::success)
                    .orElseGet(() -> ToolResult.error("未找到托管进程：" + pid));
        }
        String content = store.list().stream()
                .map(this::format)
                .collect(Collectors.joining("\n---\n"));
        return ToolResult.success(content.isBlank() ? "没有托管中的后台进程" : content);
    }

    private String format(ManagedProcess process) {
        return "pid: " + process.pid() + "\n"
                + "status: " + (process.isAlive() ? "running" : "exited") + "\n"
                + "command: " + String.join(" ", process.command()) + "\n"
                + "cwd: " + process.cwd() + "\n"
                + "healthUrl: " + (process.healthUrl() == null || process.healthUrl().isBlank() ? "-" : process.healthUrl()) + "\n"
                + "logPath: " + process.logPath() + "\n"
                + "startedAt: " + process.startedAt();
    }
}
