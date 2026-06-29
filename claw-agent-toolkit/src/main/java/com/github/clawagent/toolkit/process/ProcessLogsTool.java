package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.toolkit.execute.CommandOutputDecoder;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProcessLogsTool extends ProcessToolSupport implements AgentTool {
    public ProcessLogsTool(ManagedProcessStore store, ExecuteToolkitProperties properties) {
        super(store, properties);
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("pid", ToolDefinition.integerProperty("后台进程 PID"));
        schema.put("maxChars", ToolDefinition.integerProperty("返回日志尾部最大字符数"));
        return ToolDefinition.low(
                "builtin.process.logs",
                "Process Logs",
                "查看后台进程日志尾部。",
                ToolDefinition.objectSchema(schema, false, List.of("pid")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            long pid = Long.parseLong(required(call, "pid"));
            int maxChars = intArg(call, "maxChars", properties.getMaxOutputChars());
            return store.get(pid)
                    .map(process -> ToolResult.success(tail(process, maxChars)))
                    .orElseGet(() -> ToolResult.error("未找到托管进程：" + pid));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String tail(ManagedProcess process, int maxChars) {
        if (!Files.isRegularFile(process.logPath())) {
            return "日志文件不存在：" + process.logPath();
        }
        try {
            String content = CommandOutputDecoder.decode(Files.readAllBytes(process.logPath()), "stdout");
            String output = content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
            return "pid: " + process.pid() + "\n"
                    + "status: " + (process.isAlive() ? "running" : "exited") + "\n"
                    + "logPath: " + process.logPath() + "\n"
                    + "logs:\n" + output;
        } catch (Exception e) {
            return "读取日志失败：" + e.getMessage();
        }
    }
}
