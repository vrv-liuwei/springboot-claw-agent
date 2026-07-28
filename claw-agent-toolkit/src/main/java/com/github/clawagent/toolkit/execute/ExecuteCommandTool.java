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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 本机命令执行工具。
 * 这是高危工具：即使配置启用，也必须由 ToolExecutionGuard 根据请求 metadata 审批。
 */
public class ExecuteCommandTool implements AgentTool {
    private final ExecuteToolkitProperties properties;
    private final ExecuteCommandRiskClassifier riskClassifier = new ExecuteCommandRiskClassifier();
    private final ProjectWorkingDirectoryResolver cwdResolver;
    private final WorkerCommandExecutor workerExecutor;

    public ExecuteCommandTool(ExecuteToolkitProperties properties) {
        this.properties = properties == null ? new ExecuteToolkitProperties() : properties;
        this.cwdResolver = new ProjectWorkingDirectoryResolver(this.properties);
        this.workerExecutor = new WorkerCommandExecutor(this.properties);
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
                "在本机执行命令。会按 command + args 动态评估风险；查询类命令默认允许，写入/删除/脚本/安装等高危命令需要审批。",
                "high",
                ToolDefinition.objectSchema(schema, false, List.of("command")));
    }

    public String riskLevel(ToolCall call) {
        return assessRisk(call).riskLevel();
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            CommandInvocation invocation = commandInvocation(call);
            CommandRiskAssessment assessment = riskClassifier.classify(
                    invocation.command(),
                    invocation.args(),
                    properties.getSensitivePathPatterns());
            Path cwd = resolveCwd(call.arguments().get("cwd"), invocation, context);
            TimeoutPolicy timeout = timeoutPolicy(longArg(call, "timeoutMs", properties.getTimeoutMs()));
            if (workerExecutor.shouldUseWorker(assessment)) {
                WorkerCommandExecutor.WorkerExecutionResult result = workerExecutor.execute(invocation, cwd, timeout.effectiveMs());
                return ToolResult.success(format(invocation.command(), invocation.args(), cwd, assessment,
                        result.exitCode(), result.stdout(), result.stderr(), result.elapsedMs(), true, result.timedOut(),
                        result.stdoutTruncated(), result.stderrTruncated(), result.resourceLimited(),
                        result.resourceLimitReason(), result.cpuTimeMs(), result.workerPoolWaitMs(),
                        result.memoryBytes(), result.workerTerminationGraceMs(), result.workerEnvBlockedCount(),
                        result.workerSandboxPath(), result.workerSandboxKept(), timeout));
            }

            CommandLine commandLine = new CommandLine(invocation.executable());
            for (String arg : invocation.args()) {
                commandLine.addArgument(arg, false);
            }

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            DefaultExecutor executor = new DefaultExecutor();
            executor.setWorkingDirectory(cwd.toFile());
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            executor.setWatchdog(new ExecuteWatchdog(timeout.effectiveMs()));
            executor.setExitValues(null);

            long started = System.nanoTime();
            int exitCode = execute(executor, commandLine);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return ToolResult.success(format(invocation.command(), invocation.args(), cwd, assessment,
                    exitCode, stdout, stderr, elapsedMs, timeout));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    public CommandRiskAssessment assessRisk(ToolCall call) {
        try {
            CommandInvocation invocation = commandInvocation(call);
            return riskClassifier.classify(
                    invocation.command(),
                    invocation.args(),
                    properties.getSensitivePathPatterns());
        } catch (RuntimeException e) {
            return CommandRiskAssessment.high("invalid", e.getMessage());
        }
    }

    private int execute(DefaultExecutor executor, CommandLine commandLine) throws Exception {
        try {
            return executor.execute(commandLine);
        } catch (ExecuteException e) {
            return e.getExitValue();
        }
    }

    Path resolveCwd(String rawCwd) {
        return resolveCwd(rawCwd, null, null);
    }

    Path resolveCwd(String rawCwd, CommandInvocation invocation, AgentContext context) {
        if (invocation != null) {
            return cwdResolver.resolve(rawCwd, invocation.command(), invocation.args(), context);
        }
        boolean useDefaultCwd = rawCwd == null || rawCwd.isBlank();
        Path cwd = useDefaultCwd ? Path.of(properties.getDefaultCwd()) : Path.of(rawCwd.trim());
        Path resolved = cwd.toAbsolutePath().normalize();
        // 工作目录必须限制在 allowed roots 内，避免模型在未知目录执行命令。
        boolean allowed = properties.allowedRootPaths().stream().anyMatch(resolved::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("cwd 不在 execute allowed roots 内：" + resolved);
        }
        if (useDefaultCwd) {
            try {
                Files.createDirectories(resolved);
            } catch (Exception e) {
                throw new IllegalStateException("创建 execute 默认工作目录失败：" + resolved, e);
            }
        }
        return resolved;
    }

    private String format(String command, List<String> args, Path cwd, CommandRiskAssessment assessment,
                          int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, long elapsedMs) {
        return format(command, args, cwd, assessment, exitCode, stdout, stderr, elapsedMs,
                new TimeoutPolicy(properties.getTimeoutMs(), properties.getTimeoutMs(), false));
    }

    private String format(String command, List<String> args, Path cwd, CommandRiskAssessment assessment,
                          int exitCode, ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, long elapsedMs,
                          TimeoutPolicy timeout) {
        return format(command, args, cwd, assessment, exitCode,
                CommandOutputDecoder.decode(stdout.toByteArray(), "stdout"),
                CommandOutputDecoder.decode(stderr.toByteArray(), "stderr"),
                elapsedMs, false, false, false, false, false, "", 0, 0, 0, 0, 0,
                "", false, timeout);
    }

    private String format(String command, List<String> args, Path cwd, CommandRiskAssessment assessment,
                          int exitCode, String stdout, String stderr, long elapsedMs, boolean workerIsolated,
                          boolean timedOut, boolean stdoutTruncated, boolean stderrTruncated,
                          boolean workerResourceLimited, String workerResourceLimitReason, long workerCpuTimeMs,
                          long workerPoolWaitMs, long workerTerminationGraceMs) {
        return format(command, args, cwd, assessment, exitCode, stdout, stderr, elapsedMs, workerIsolated,
                timedOut, stdoutTruncated, stderrTruncated, workerResourceLimited, workerResourceLimitReason,
                workerCpuTimeMs, workerPoolWaitMs, 0, workerTerminationGraceMs, 0,
                "", false, new TimeoutPolicy(properties.getTimeoutMs(), properties.getTimeoutMs(), false));
    }

    private String format(String command, List<String> args, Path cwd, CommandRiskAssessment assessment,
                          int exitCode, String stdout, String stderr, long elapsedMs, boolean workerIsolated,
                          boolean timedOut, boolean stdoutTruncated, boolean stderrTruncated,
                          boolean workerResourceLimited, String workerResourceLimitReason, long workerCpuTimeMs,
                          long workerPoolWaitMs, long workerMemoryBytes, long workerTerminationGraceMs,
                          int workerEnvBlockedCount, String workerSandboxPath, boolean workerSandboxKept,
                          TimeoutPolicy timeout) {
        String out = truncate(stdout);
        String err = truncate(stderr);
        return "command: " + command + (args.isEmpty() ? "" : " " + String.join(" ", args)) + "\n"
                + "cwd: " + cwd + "\n"
                + "requestedTimeoutMs: " + timeout.requestedMs() + "\n"
                + "timeoutMs: " + timeout.effectiveMs() + "\n"
                + "timeoutCapped: " + timeout.capped() + "\n"
                + "riskLevel: " + assessment.riskLevel() + "\n"
                + "riskCategory: " + assessment.category() + "\n"
                + "approvalRequired: " + assessment.approvalRequired() + "\n"
                + "riskReason: " + assessment.reason() + "\n"
                + "workerIsolated: " + workerIsolated + "\n"
                + "workerPoolWaitMs: " + workerPoolWaitMs + "\n"
                + "workerTerminationGraceMs: " + workerTerminationGraceMs + "\n"
                + "timedOut: " + timedOut + "\n"
                + "stdoutTruncatedByWorker: " + stdoutTruncated + "\n"
                + "stderrTruncatedByWorker: " + stderrTruncated + "\n"
                + "workerResourceLimited: " + workerResourceLimited + "\n"
                + "workerResourceLimitReason: " + (workerResourceLimitReason == null ? "" : workerResourceLimitReason) + "\n"
                + "workerCpuTimeMs: " + workerCpuTimeMs + "\n"
                + "workerMemoryBytes: " + workerMemoryBytes + "\n"
                + "workerEnvBlockedCount: " + workerEnvBlockedCount + "\n"
                + "workerSandboxPath: " + (workerSandboxPath == null ? "" : workerSandboxPath) + "\n"
                + "workerSandboxKept: " + workerSandboxKept + "\n"
                + "exitCode: " + exitCode + "\n"
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
        try {
            return JSONUtil.parseArray(raw).stream().map(Object::toString).toList();
        } catch (RuntimeException e) {
            return parseCommandLine("placeholder " + raw).stream().skip(1).toList();
        }
    }

    CommandInvocation commandInvocation(ToolCall call) {
        String rawCommand = required(call, "command");
        List<String> explicitArgs = args(call);
        List<String> commandParts = parseCommandLine(rawCommand);
        String command = commandParts.isEmpty() ? rawCommand : commandParts.get(0);
        List<String> mergedArgs = new ArrayList<>();
        if (commandParts.size() > 1) {
            // 兼容模型把整条命令放进 command 字段的情况，避免把 "npm install" 当作可执行文件名。
            mergedArgs.addAll(commandParts.subList(1, commandParts.size()));
        }
        mergedArgs.addAll(explicitArgs);
        mergedArgs = normalizeShellArgs(command, mergedArgs);
        String executable = resolveExecutable(command).orElse(command);
        return new CommandInvocation(command, executable, List.copyOf(mergedArgs));
    }

    private List<String> parseCommandLine(String commandLine) {
        try {
            CommandLine parsed = CommandLine.parse(commandLine);
            List<String> parts = new ArrayList<>();
            parts.add(parsed.getExecutable());
            parts.addAll(Arrays.asList(parsed.getArguments()));
            return parts.stream().filter(part -> part != null && !part.isBlank()).toList();
        } catch (RuntimeException e) {
            return List.of(commandLine.trim());
        }
    }

    private List<String> normalizeShellArgs(String command, List<String> args) {
        if (!"cmd".equalsIgnoreCase(command) || args.isEmpty()) {
            return args;
        }
        List<String> normalized = new ArrayList<>(args);
        if ("\\c".equalsIgnoreCase(normalized.get(0)) || "-c".equalsIgnoreCase(normalized.get(0)) || "c".equalsIgnoreCase(normalized.get(0))) {
            // Windows cmd 的执行开关是 /c；这里修正常见的反斜杠误写，减少模型格式波动。
            normalized.set(0, "/c");
        }
        return normalized;
    }

    private Optional<String> resolveExecutable(String command) {
        if (!isWindows() || command.contains("\\") || command.contains("/") || command.contains(".")) {
            return Optional.empty();
        }
        if ("cmd".equalsIgnoreCase(command)) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && !comspec.isBlank() && Path.of(comspec).toFile().isFile()) {
                return Optional.of(Path.of(comspec).toAbsolutePath().normalize().toString());
            }
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                Path candidate = Path.of(systemRoot, "System32", "cmd.exe").toAbsolutePath().normalize();
                if (candidate.toFile().isFile()) {
                    return Optional.of(candidate.toString());
                }
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(";")) {
            for (String extension : List.of(".exe", ".cmd", ".bat")) {
                File candidate = Path.of(dir, command + extension).toFile();
                if (candidate.isFile()) {
                    return Optional.of(candidate.getAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
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

    private TimeoutPolicy timeoutPolicy(long requestedTimeoutMs) {
        long requested = requestedTimeoutMs <= 0 ? properties.getTimeoutMs() : requestedTimeoutMs;
        long max = properties.getMaxTimeoutMs();
        long effective = Math.min(requested, max);
        // 超时上限是强终止边界，避免模型传入极大 timeout 长时间占住 worker 或主服务执行线程。
        return new TimeoutPolicy(requested, effective, effective != requested);
    }

    record TimeoutPolicy(long requestedMs, long effectiveMs, boolean capped) {
    }

    record CommandInvocation(String command, String executable, List<String> args) {
        String commandLine() {
            return command + (args.isEmpty() ? "" : " " + String.join(" ", args));
        }
    }
}
