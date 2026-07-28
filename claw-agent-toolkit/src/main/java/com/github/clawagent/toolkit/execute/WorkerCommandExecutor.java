package com.github.clawagent.toolkit.execute;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/**
 * WorkerCommandExecutor 只负责和独立 worker JVM 通信。
 * 它不做审批判断，审批仍由 Runtime/ToolExecutionGuard 统一处理。
 */
public class WorkerCommandExecutor {
    private static final String RESULT_MARKER = "CLAW_WORKER_RESULT_V1";

    private final ExecuteToolkitProperties properties;
    private final Semaphore workerSlots;

    public WorkerCommandExecutor(ExecuteToolkitProperties properties) {
        this.properties = properties;
        this.workerSlots = new Semaphore(Math.max(1, properties.getWorkerMaxConcurrent()));
    }

    public boolean shouldUseWorker(CommandRiskAssessment assessment) {
        return properties.isWorkerEnabled() && "high".equalsIgnoreCase(assessment.riskLevel());
    }

    public WorkerExecutionResult execute(ExecuteCommandTool.CommandInvocation invocation, Path cwd, long timeoutMs) throws Exception {
        List<String> processCommand = new ArrayList<>();
        processCommand.add(invocation.executable());
        processCommand.addAll(invocation.args());
        return execute(processCommand, cwd, timeoutMs, Map.of());
    }

    public WorkerExecutionResult execute(List<String> processCommand, Path cwd, long timeoutMs,
                                         Map<String, String> env) throws Exception {
        if (processCommand == null || processCommand.isEmpty()) {
            throw new IllegalArgumentException("缺少 worker 命令");
        }
        List<String> command = baseWorkerCommand(cwd, timeoutMs, properties.isWorkerKeepSandbox());
        command.add("--command");
        command.add(processCommand.get(0));
        processCommand.stream().skip(1).forEach(arg -> {
            command.add("--arg");
            command.add(arg);
        });
        appendEnvArgs(command, env);
        long workerWaitMs = timeoutMs + Math.max(1000, properties.getWorkerTerminationGraceMs() * 2);
        WorkerRawResult raw = runWorker(command, cwd, workerWaitMs);
        return parseWorkerResult(raw.stdout(), raw.stderr(), raw.finished(), raw.elapsedMs(), raw.workerPoolWaitMs(),
                raw.launcherEnvBlockedCount());
    }

    public BackgroundStartResult startBackground(List<String> processCommand, Path cwd, Path logPath,
                                                 long processWaitMs) throws Exception {
        if (processCommand == null || processCommand.isEmpty()) {
            throw new IllegalArgumentException("缺少后台进程命令");
        }
        List<String> command = baseWorkerCommand(cwd, Math.max(1, processWaitMs), true);
        command.add("--backgroundLogPath");
        command.add(logPath.toString());
        command.add("--backgroundWaitMs");
        command.add(String.valueOf(Math.max(0, processWaitMs)));
        command.add("--command");
        command.add(processCommand.get(0));
        processCommand.stream().skip(1).forEach(arg -> {
            command.add("--arg");
            command.add(arg);
        });
        long workerWaitMs = Math.max(1000, processWaitMs + properties.getWorkerTerminationGraceMs() * 2);
        WorkerRawResult raw = runWorker(command, cwd, workerWaitMs);
        WorkerExecutionResult workerResult = parseWorkerResult(raw.stdout(), raw.stderr(), raw.finished(),
                raw.elapsedMs(), raw.workerPoolWaitMs(), raw.launcherEnvBlockedCount());
        Map<String, String> fields = parseFields(workerResult.stdout());
        return new BackgroundStartResult(
                workerResult.exitCode(),
                workerResult.elapsedMs(),
                parseLong(fields.get("backgroundPid"), -1),
                Boolean.parseBoolean(fields.getOrDefault("backgroundAlive", "false")),
                parseInt(fields.get("backgroundExitCode"), Integer.MIN_VALUE),
                fields.getOrDefault("backgroundLogPath", logPath.toString()),
                workerResult.stderr(),
                workerResult.workerPoolWaitMs(),
                workerResult.workerEnvBlockedCount(),
                workerResult.workerSandboxPath(),
                workerResult.workerSandboxKept());
    }

    private void appendEnvArgs(List<String> command, Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return;
        }
        env.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                command.add("--env");
                command.add(name + "=" + value);
            }
        });
    }

    private List<String> baseWorkerCommand(Path cwd, long timeoutMs, boolean keepSandbox) {
        Path workerJar = resolveWorkerJar();
        String javaCommand = resolveJavaCommand();
        List<String> command = new ArrayList<>();
        command.add(javaCommand);
        workerHeapArg().ifPresent(command::add);
        command.add("-jar");
        command.add(workerJar.toString());
        command.add("--cwd");
        command.add(cwd.toString());
        command.add("--timeoutMs");
        command.add(String.valueOf(timeoutMs));
        command.add("--maxOutputBytes");
        command.add(String.valueOf(properties.getWorkerMaxOutputBytes()));
        command.add("--maxCpuTimeMs");
        command.add(String.valueOf(properties.getWorkerMaxCpuTimeMs()));
        command.add("--maxMemoryBytes");
        command.add(String.valueOf(properties.getWorkerMaxMemoryBytes()));
        command.add("--terminationGraceMs");
        command.add(String.valueOf(properties.getWorkerTerminationGraceMs()));
        if (properties.isWorkerSandboxEnabled()) {
            command.add("--sandboxRoot");
            command.add(resolveWorkerSandboxRoot(cwd).toString());
            command.add("--keepSandbox");
            command.add(String.valueOf(keepSandbox));
        }
        for (String blockedFragment : properties.getWorkerBlockedEnvNameFragments()) {
            command.add("--blockedEnvNameFragment");
            command.add(blockedFragment);
        }
        for (String allowedEnvName : properties.getWorkerAllowedEnvNames()) {
            command.add("--allowedEnvName");
            command.add(allowedEnvName);
        }
        for (Path allowedRoot : properties.allowedRootPaths()) {
            command.add("--allowedRoot");
            command.add(allowedRoot.toString());
        }
        return command;
    }

    private Path resolveWorkerSandboxRoot(Path cwd) {
        Path configured = Path.of(properties.getWorkerSandboxRoot());
        Path resolved = configured.isAbsolute()
                ? configured.normalize()
                : cwd.resolve(configured).toAbsolutePath().normalize();
        List<Path> allowedRoots = properties.allowedRootPaths();
        if (!allowedRoots.isEmpty() && allowedRoots.stream().noneMatch(resolved::startsWith)) {
            throw new IllegalArgumentException("worker sandboxRoot 不在 execute allowed roots 内：" + resolved);
        }
        return resolved;
    }

    private WorkerRawResult runWorker(List<String> command, Path cwd, long workerWaitMs) throws Exception {
        long waitStarted = System.nanoTime();
        // 高危命令先进入本机 worker 池，避免多个隔离进程同时启动导致主机资源被打满。
        boolean acquired = workerSlots.tryAcquire(properties.getWorkerAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        long workerPoolWaitMs = Duration.ofNanos(System.nanoTime() - waitStarted).toMillis();
        if (!acquired) {
            throw new IllegalStateException("worker 池已满，等待 " + workerPoolWaitMs
                    + "ms 后仍未获得执行槽位，请稍后重试或调大 WORKER_MAX_CONCURRENT");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        int launcherEnvBlockedCount = prepareWorkerEnvironment(builder.environment());
        long started = System.nanoTime();
        try {
            Process worker = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread stdoutReader = streamReader(worker.getInputStream(), stdout);
            Thread stderrReader = streamReader(worker.getErrorStream(), stderr);
            boolean finished = worker.waitFor(workerWaitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // worker 自身失控时强杀 worker 进程树，避免主服务线程永久等待。
                stopProcessTree(worker.toHandle());
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new WorkerRawResult(stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8), finished, elapsedMs, workerPoolWaitMs,
                    launcherEnvBlockedCount);
        } finally {
            workerSlots.release();
        }
    }

    private Path resolveWorkerJar() {
        Path configured = Path.of(properties.getWorkerJar());
        List<Path> checkedPaths = new ArrayList<>();
        Path direct = configured.toAbsolutePath().normalize();
        checkedPaths.add(direct);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        if (!configured.isAbsolute()) {
            Path current = Path.of("").toAbsolutePath().normalize();
            while (current != null) {
                Path candidate = current.resolve(configured).normalize();
                if (!checkedPaths.contains(candidate)) {
                    checkedPaths.add(candidate);
                }
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
                // 开发环境可能从 claw-agent-server、target 或 IDE 工作目录启动，向父目录找项目根。
                current = current.getParent();
            }
        }
        throw new IllegalStateException("worker jar 不存在，无法隔离执行高危命令。WORKER_JAR="
                + properties.getWorkerJar() + "；user.dir=" + Path.of("").toAbsolutePath().normalize()
                + "；已检查路径=" + checkedPaths
                + "。请执行 mvn -pl claw-agent-worker -DskipTests package，或把 WORKER_JAR 配成可访问的绝对路径。");
    }

    private String resolveJavaCommand() {
        if (properties.getWorkerJava() != null && !properties.getWorkerJava().isBlank()) {
            return properties.getWorkerJava();
        }
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java").toAbsolutePath().normalize();
            if (Files.isRegularFile(java)) {
                return java.toString();
            }
        }
        return "java";
    }

    private Optional<String> workerHeapArg() {
        String value = properties.getWorkerJvmMaxHeap();
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String trimmed = value.trim();
        return Optional.of(trimmed.startsWith("-Xmx") ? trimmed : "-Xmx" + trimmed);
    }

    private WorkerExecutionResult parseWorkerResult(String workerStdout, String workerStderr, boolean workerFinished,
                                                    long fallbackElapsedMs, long workerPoolWaitMs,
                                                    int launcherEnvBlockedCount) {
        Optional<String> resultBlock = extractResultBlock(workerStdout);
        if (resultBlock.isEmpty()) {
            return new WorkerExecutionResult(workerFinished ? 97 : 124, !workerFinished, fallbackElapsedMs,
                    "", workerStderr.isBlank() ? workerStdout : workerStderr + "\n" + workerStdout,
                    false, false, false, "", 0,
                    0, workerPoolWaitMs, properties.getWorkerTerminationGraceMs(), launcherEnvBlockedCount,
                    "", false);
        }
        Map<String, String> fields = parseFields(resultBlock.get());
        int exitCode = parseInt(fields.get("exitCode"), workerFinished ? 0 : 124);
        boolean timedOut = Boolean.parseBoolean(fields.getOrDefault("timedOut", "false")) || !workerFinished;
        long elapsedMs = parseLong(fields.get("elapsedMs"), fallbackElapsedMs);
        String stdout = decode(fields.get("stdoutBase64"));
        String stderr = decode(fields.get("stderrBase64"));
        boolean stdoutTruncated = Boolean.parseBoolean(fields.getOrDefault("stdoutTruncated", "false"));
        boolean stderrTruncated = Boolean.parseBoolean(fields.getOrDefault("stderrTruncated", "false"));
        boolean resourceLimited = Boolean.parseBoolean(fields.getOrDefault("resourceLimited", "false"));
        String resourceLimitReason = fields.getOrDefault("resourceLimitReason", "");
        long cpuTimeMs = parseLong(fields.get("cpuTimeMs"), 0);
        long memoryBytes = parseLong(fields.get("memoryBytes"), 0);
        int workerEnvBlockedCount = parseInt(fields.get("workerEnvBlockedCount"), 0);
        String workerSandboxPath = fields.getOrDefault("workerSandboxPath", "");
        boolean workerSandboxKept = Boolean.parseBoolean(fields.getOrDefault("workerSandboxKept", "false"));
        if (!workerStderr.isBlank()) {
            stderr = stderr.isBlank() ? workerStderr : stderr + "\n" + workerStderr;
        }
        return new WorkerExecutionResult(exitCode, timedOut, elapsedMs, stdout, stderr,
                stdoutTruncated, stderrTruncated, resourceLimited, resourceLimitReason, cpuTimeMs, memoryBytes,
                workerPoolWaitMs, properties.getWorkerTerminationGraceMs(),
                launcherEnvBlockedCount + workerEnvBlockedCount, workerSandboxPath, workerSandboxKept);
    }

    private Optional<String> extractResultBlock(String output) {
        int marker = output.indexOf(RESULT_MARKER);
        if (marker < 0) {
            return Optional.empty();
        }
        return Optional.of(output.substring(marker));
    }

    private Map<String, String> parseFields(String block) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : block.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                fields.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        return fields;
    }

    private String decode(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private Thread streamReader(java.io.InputStream input, ByteArrayOutputStream output) {
        Thread thread = new Thread(() -> {
            try (input; output) {
                input.transferTo(output);
            } catch (Exception ignored) {
                // worker 输出读取失败时按已读取内容返回，不覆盖 worker 本身的结果。
            }
        }, "claw-agent-worker-client-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stopProcessTree(ProcessHandle root) {
        List<ProcessHandle> descendants = root.descendants().toList();
        descendants.forEach(ProcessHandle::destroyForcibly);
        root.destroyForcibly();
    }

    int prepareWorkerEnvironment(Map<String, String> environment) {
        if (environment == null || environment.isEmpty()) {
            return 0;
        }
        Map<String, String> inherited = new LinkedHashMap<>(environment);
        List<String> allowedNames = normalizedNames(properties.getWorkerAllowedEnvNames());
        List<String> blockedFragments = properties.getWorkerBlockedEnvNameFragments().stream()
                .filter(fragment -> fragment != null && !fragment.isBlank())
                .map(fragment -> fragment.trim().toUpperCase(java.util.Locale.ROOT))
                .toList();
        environment.clear();
        // 高危命令的 worker 环境先按白名单重建；用户显式允许的变量名优先于默认敏感片段。
        inherited.forEach((name, value) -> {
            if (isAllowedEnvName(name, allowedNames)
                    && !isBlockedEnvName(name, blockedFragments, allowedNames)) {
                environment.put(name, value);
            }
        });
        return inherited.size() - environment.size();
    }

    private List<String> normalizedNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    private boolean isAllowedEnvName(String name, List<String> allowedNames) {
        if (name == null || allowedNames.isEmpty()) {
            return false;
        }
        return allowedNames.contains(name.trim().toUpperCase(java.util.Locale.ROOT));
    }

    private boolean isBlockedEnvName(String name, List<String> blockedFragments, List<String> allowedNames) {
        if (name == null || blockedFragments.isEmpty()) {
            return false;
        }
        String normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        if (allowedNames.contains(normalized)) {
            return false;
        }
        return blockedFragments.stream().anyMatch(normalized::contains);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public record WorkerExecutionResult(int exitCode, boolean timedOut, long elapsedMs, String stdout, String stderr,
                                        boolean stdoutTruncated, boolean stderrTruncated, boolean resourceLimited,
                                        String resourceLimitReason, long cpuTimeMs, long memoryBytes,
                                        long workerPoolWaitMs, long workerTerminationGraceMs,
                                        int workerEnvBlockedCount, String workerSandboxPath,
                                        boolean workerSandboxKept) {
    }

    public record BackgroundStartResult(int exitCode, long elapsedMs, long pid, boolean alive, int backgroundExitCode,
                                        String logPath, String stderr, long workerPoolWaitMs,
                                        int workerEnvBlockedCount, String workerSandboxPath,
                                        boolean workerSandboxKept) {
    }

    private record WorkerRawResult(String stdout, String stderr, boolean finished, long elapsedMs,
                                   long workerPoolWaitMs, int launcherEnvBlockedCount) {
    }
}
