package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.toolkit.execute.CommandOutputDecoder;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;
import com.github.clawagent.toolkit.execute.WorkerCommandExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProcessStartTool extends ProcessToolSupport implements AgentTool {
    private final WorkerCommandExecutor workerExecutor;

    public ProcessStartTool(ManagedProcessStore store, ExecuteToolkitProperties properties) {
        super(store, properties);
        this.workerExecutor = new WorkerCommandExecutor(this.properties);
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("command", ToolDefinition.stringProperty("命令名，例如 mvn、npm、cmd、bash"));
        schema.put("args", ToolDefinition.stringProperty("JSON 数组字符串，例如 [\"spring-boot:run\"]"));
        schema.put("cwd", ToolDefinition.stringProperty("工作目录，必须位于 allowed roots 内"));
        schema.put("logPath", ToolDefinition.stringProperty("可选日志文件路径，默认写入 cwd/.clawagent-process-<timestamp>.log"));
        schema.put("processWaitMs", ToolDefinition.integerProperty("启动确认等待时间，毫秒；进程仍存活即返回成功"));
        schema.put("healthUrl", ToolDefinition.stringProperty("可选健康检查 URL，例如 http://127.0.0.1:8080/actuator/health"));
        return new ToolDefinition(
                "builtin.process.start",
                "Start Background Process",
                "启动后台进程并返回 pid、日志路径和存活状态。用于运行本地服务，避免同步 execute 阻塞任务线程。",
                "high",
                ToolDefinition.objectSchema(schema, false, List.of("command")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            CommandInvocation invocation = commandInvocation(call);
            Path cwd = resolveCwd(call.arguments().get("cwd"), invocation, context);
            long processWaitMs = longArg(call, "processWaitMs", properties.getProcessWaitMs());
            Path logPath = resolveLogPath(call.arguments().get("logPath"), cwd);
            String healthUrl = healthUrl(call);
            if (properties.isWorkerEnabled()) {
                return startWithWorker(invocation, cwd, logPath, processWaitMs, healthUrl, context);
            }
            return startDirect(invocation, cwd, logPath, processWaitMs, healthUrl, context);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private ToolResult startWithWorker(CommandInvocation invocation, Path cwd, Path logPath, long processWaitMs,
                                       String healthUrl, AgentContext context) throws Exception {
        Files.createDirectories(logPath.getParent());
        WorkerCommandExecutor.BackgroundStartResult result = workerExecutor.startBackground(
                invocation.processCommand(), cwd, logPath, processWaitMs);
        if (!result.alive() || result.pid() <= 0) {
            return ToolResult.error("process exited during startup\n"
                    + "pid: " + result.pid() + "\n"
                    + "exitCode: " + (result.backgroundExitCode() == Integer.MIN_VALUE ? result.exitCode() : result.backgroundExitCode()) + "\n"
                    + "command: " + invocation.commandLineText() + "\n"
                    + "cwd: " + cwd + "\n"
                    + "healthUrl: " + nullToDash(healthUrl) + "\n"
                    + "logPath: " + logPath + "\n"
                    + "workerIsolated: true\n"
                    + "workerPoolWaitMs: " + result.workerPoolWaitMs() + "\n"
                    + "workerEnvBlockedCount: " + result.workerEnvBlockedCount() + "\n"
                    + "workerSandboxPath: " + result.workerSandboxPath() + "\n"
                    + "workerSandboxKept: " + result.workerSandboxKept() + "\n"
                    + "workerStderr: " + result.stderr() + "\n"
                    + "logs:\n" + tail(logPath, properties.getMaxOutputChars()));
        }

        AgentTask task = context == null ? null : context.task();
        // worker 启动后只返回后台进程 pid，后续 stop/status 通过 ProcessHandle 管理跨进程记录。
        ManagedProcess managed = new ManagedProcess(result.pid(), null, invocation.processCommand(), cwd, logPath, Instant.now(),
                task == null ? null : task.id(),
                task == null ? null : task.sessionId(),
                projectPath(task, cwd),
                healthUrl);
        store.put(managed);
        return ToolResult.success("pid: " + result.pid() + "\n"
                + "status: running\n"
                + "command: " + invocation.commandLineText() + "\n"
                + "cwd: " + cwd + "\n"
                + "healthUrl: " + nullToDash(healthUrl) + "\n"
                + "logPath: " + logPath + "\n"
                + "processWaitMs: " + processWaitMs + "\n"
                + "workerIsolated: true\n"
                + "workerElapsedMs: " + result.elapsedMs() + "\n"
                + "workerPoolWaitMs: " + result.workerPoolWaitMs() + "\n"
                + "workerEnvBlockedCount: " + result.workerEnvBlockedCount() + "\n"
                + "workerSandboxPath: " + result.workerSandboxPath() + "\n"
                + "workerSandboxKept: " + result.workerSandboxKept() + "\n"
                + "logs:\n" + tail(logPath, properties.getMaxOutputChars()));
    }

    private ToolResult startDirect(CommandInvocation invocation, Path cwd, Path logPath, long processWaitMs,
                                   String healthUrl, AgentContext context) throws Exception {
            Files.createDirectories(logPath.getParent());

            ProcessBuilder builder = new ProcessBuilder(invocation.processCommand());
            builder.directory(cwd.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));

            Process process = builder.start();
            long pid = process.pid();
            AgentTask task = context == null ? null : context.task();
            // 后台进程必须能回溯到发起任务，后续任务详情和桌面端都依赖这条关联。
            ManagedProcess managed = new ManagedProcess(pid, process, invocation.processCommand(), cwd, logPath, Instant.now(),
                    task == null ? null : task.id(),
                    task == null ? null : task.sessionId(),
                    projectPath(task, cwd),
                    healthUrl);
            store.put(managed);

            Thread.sleep(processWaitMs);
            if (!process.isAlive()) {
                store.remove(pid);
                return ToolResult.error("process exited during startup\n"
                        + "pid: " + pid + "\n"
                        + "exitCode: " + process.exitValue() + "\n"
                        + "command: " + invocation.commandLineText() + "\n"
                        + "cwd: " + cwd + "\n"
                        + "healthUrl: " + nullToDash(healthUrl) + "\n"
                        + "logPath: " + logPath + "\n"
                        + "logs:\n" + tail(logPath, properties.getMaxOutputChars()));
            }
            return ToolResult.success("pid: " + pid + "\n"
                    + "status: running\n"
                    + "command: " + invocation.commandLineText() + "\n"
                    + "cwd: " + cwd + "\n"
                    + "healthUrl: " + nullToDash(healthUrl) + "\n"
                    + "logPath: " + logPath + "\n"
                    + "processWaitMs: " + processWaitMs + "\n"
                    + "workerIsolated: false\n"
                    + "logs:\n" + tail(logPath, properties.getMaxOutputChars()));
    }

    private Path resolveLogPath(String rawLogPath, Path cwd) {
        if (rawLogPath != null && !rawLogPath.isBlank()) {
            Path path = Path.of(rawLogPath.trim());
            Path resolved = path.isAbsolute() ? path.normalize() : cwd.resolve(path).normalize();
            return ensureAllowedPath(resolved, "logPath");
        }
        return ensureAllowedPath(cwd.resolve(".clawagent-process-" + System.currentTimeMillis() + ".log"), "logPath");
    }

    private String tail(Path logPath, int maxChars) {
        if (!Files.isRegularFile(logPath)) {
            return "";
        }
        try {
            String content = CommandOutputDecoder.decode(Files.readAllBytes(logPath), "stdout");
            return content.length() <= maxChars ? content : content.substring(content.length() - maxChars);
        } catch (Exception e) {
            return "读取日志失败：" + e.getMessage();
        }
    }

    private String projectPath(AgentTask task, Path cwd) {
        if (task == null || task.metadata() == null) {
            return cwd.toString();
        }
        for (String key : List.of("activeProjectPath", "projectPath", "workspace.projectPath", "cwd")) {
            String value = task.metadata().get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return cwd.toString();
    }

    private String healthUrl(ToolCall call) {
        for (String key : List.of("healthUrl", "healthCheckUrl")) {
            String value = call.arguments().get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
