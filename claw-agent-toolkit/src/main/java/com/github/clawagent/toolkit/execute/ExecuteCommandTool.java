package com.github.clawagent.toolkit.execute;

import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机命令执行工具。
 * 这是高危工具：即使配置启用，也必须由 ToolExecutionGuard 根据请求 metadata 审批。
 */
public class ExecuteCommandTool implements AgentTool {
    private final ExecuteToolkitProperties properties;

    public ExecuteCommandTool(ExecuteToolkitProperties properties) {
        this.properties = properties == null ? new ExecuteToolkitProperties() : properties;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("command", ToolDefinition.stringProperty("命令名，例如 cmd、powershell、git"));
        schema.put("args", ToolDefinition.stringProperty("JSON 数组字符串，例如 [\"/c\",\"dir\"]"));
        schema.put("cwd", ToolDefinition.stringProperty("工作目录，必须位于 allowed roots 内"));
        schema.put("timeoutMs", ToolDefinition.integerProperty("可选超时时间，毫秒"));
        return new ToolDefinition(
                "builtin.execute.command",
                "Execute Command",
                "在本机执行命令。高危工具，默认关闭且需要审批；参数必须使用 command + args，不执行整段 shell 字符串。",
                "high",
                ToolDefinition.objectSchema(schema, false, List.of("command")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            String command = required(call, "command");
            List<String> args = args(call);
            Path cwd = resolveCwd(call.arguments().get("cwd"));
            long timeoutMs = longArg(call, "timeoutMs", properties.getTimeoutMs());

            CommandLine commandLine = new CommandLine(command);
            for (String arg : args) {
                commandLine.addArgument(arg, false);
            }

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            DefaultExecutor executor = new DefaultExecutor();
            executor.setWorkingDirectory(cwd.toFile());
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            executor.setWatchdog(new ExecuteWatchdog(timeoutMs));
            executor.setExitValues(null);

            long started = System.nanoTime();
            int exitCode = execute(executor, commandLine);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return ToolResult.success(format(exitCode, stdout, stderr, elapsedMs));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private int execute(DefaultExecutor executor, CommandLine commandLine) throws Exception {
        try {
            return executor.execute(commandLine);
        } catch (ExecuteException e) {
            return e.getExitValue();
        }
    }

    private Path resolveCwd(String rawCwd) {
        Path cwd = rawCwd == null || rawCwd.isBlank() ? Path.of(".") : Path.of(rawCwd.trim());
        Path resolved = cwd.toAbsolutePath().normalize();
        // 工作目录必须限制在 allowed roots 内，避免模型在未知目录执行命令。
        boolean allowed = properties.allowedRootPaths().stream().anyMatch(resolved::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("cwd 不在 execute allowed roots 内：" + resolved);
        }
        return resolved;
    }

    private String format(int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, long elapsedMs) {
        String out = truncate(stdout.toString(StandardCharsets.UTF_8));
        String err = truncate(stderr.toString(StandardCharsets.UTF_8));
        return "exitCode: " + exitCode + "\n"
                + "elapsedMs: " + elapsedMs + "\n"
                + "stdout:\n" + out + "\n\n"
                + "stderr:\n" + err;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= properties.getMaxOutputChars()) {
            return value == null ? "" : value;
        }
        return value.substring(0, properties.getMaxOutputChars())
                + "\n[输出已按 MAX_OUTPUT_CHARS=" + properties.getMaxOutputChars() + " 截断]";
    }

    private List<String> args(ToolCall call) {
        String raw = call.arguments().get("args");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return JSONUtil.parseArray(raw).stream().map(Object::toString).toList();
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private long longArg(ToolCall call, String name, long defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }
}
