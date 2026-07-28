package com.github.clawagent.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * claw-agent-worker 是高危本机命令的隔离执行入口。
 * 主进程只负责审批和调度，真实命令在该子 JVM 中启动，超时后可整棵进程树强制终止。
 */
public final class ClawAgentWorkerMain {
    private static final int WORKER_ERROR_EXIT = 97;
    private static final int RESOURCE_LIMIT_EXIT = 125;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private ClawAgentWorkerMain() {
    }

    public static void main(String[] args) {
        try {
            WorkerRequest request = parse(args);
            WorkerResult result = execute(request);
            printResult(result);
            System.exit(result.exitCode());
        } catch (Exception e) {
            printResult(new WorkerResult(WORKER_ERROR_EXIT, false, 0,
                    "", "worker error: " + e.getMessage(), false, false, false, "", 0, 0, 0,
                    "", false));
            System.exit(WORKER_ERROR_EXIT);
        }
    }

    private static WorkerRequest parse(String[] args) {
        Path cwd = Path.of(".");
        long timeoutMs = 30000;
        long terminationGraceMs = 1500;
        long maxCpuTimeMs = 0;
        long maxMemoryBytes = 0;
        int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        Path backgroundLogPath = null;
        long backgroundWaitMs = 0;
        Path sandboxRoot = null;
        boolean keepSandbox = false;
        List<String> command = new ArrayList<>();
        Map<String, String> envOverrides = new LinkedHashMap<>();
        List<Path> allowedRoots = new ArrayList<>();
        List<String> blockedEnvNameFragments = new ArrayList<>();
        List<String> allowedEnvNames = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--cwd".equals(arg)) {
                cwd = Path.of(required(args, ++index, "--cwd"));
            } else if ("--timeoutMs".equals(arg)) {
                timeoutMs = Long.parseLong(required(args, ++index, "--timeoutMs"));
            } else if ("--maxOutputBytes".equals(arg)) {
                maxOutputBytes = positiveInt(required(args, ++index, "--maxOutputBytes"), DEFAULT_MAX_OUTPUT_BYTES);
            } else if ("--maxCpuTimeMs".equals(arg)) {
                maxCpuTimeMs = nonNegativeLong(required(args, ++index, "--maxCpuTimeMs"));
            } else if ("--maxMemoryBytes".equals(arg)) {
                maxMemoryBytes = nonNegativeLong(required(args, ++index, "--maxMemoryBytes"));
            } else if ("--terminationGraceMs".equals(arg)) {
                terminationGraceMs = positiveLong(required(args, ++index, "--terminationGraceMs"), 1500);
            } else if ("--backgroundLogPath".equals(arg)) {
                backgroundLogPath = Path.of(required(args, ++index, "--backgroundLogPath"));
            } else if ("--backgroundWaitMs".equals(arg)) {
                backgroundWaitMs = nonNegativeLong(required(args, ++index, "--backgroundWaitMs"));
            } else if ("--sandboxRoot".equals(arg)) {
                sandboxRoot = Path.of(required(args, ++index, "--sandboxRoot"));
            } else if ("--keepSandbox".equals(arg)) {
                keepSandbox = Boolean.parseBoolean(required(args, ++index, "--keepSandbox"));
            } else if ("--allowedRoot".equals(arg)) {
                allowedRoots.add(Path.of(required(args, ++index, "--allowedRoot")).toAbsolutePath().normalize());
            } else if ("--blockedEnvNameFragment".equals(arg)) {
                blockedEnvNameFragments.add(required(args, ++index, "--blockedEnvNameFragment"));
            } else if ("--allowedEnvName".equals(arg)) {
                allowedEnvNames.add(required(args, ++index, "--allowedEnvName"));
            } else if ("--command".equals(arg)) {
                command.add(required(args, ++index, "--command"));
            } else if ("--arg".equals(arg)) {
                command.add(required(args, ++index, "--arg"));
            } else if ("--env".equals(arg)) {
                parseEnvOverride(required(args, ++index, "--env"), envOverrides);
            } else {
                throw new IllegalArgumentException("未知 worker 参数：" + arg);
            }
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("缺少 --command");
        }
        return new WorkerRequest(cwd.toAbsolutePath().normalize(), allowedRoots, blockedEnvNameFragments, allowedEnvNames,
                timeoutMs, terminationGraceMs, maxOutputBytes, maxCpuTimeMs, maxMemoryBytes,
                backgroundLogPath == null ? null : backgroundLogPath.toAbsolutePath().normalize(),
                backgroundWaitMs, sandboxRoot == null ? null : sandboxRoot.toAbsolutePath().normalize(),
                keepSandbox, command, envOverrides);
    }

    private static void parseEnvOverride(String value, Map<String, String> envOverrides) {
        int separator = value.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException("--env 必须使用 name=value 格式");
        }
        envOverrides.put(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String required(String[] args, int index, String option) {
        if (index >= args.length || args[index] == null || args[index].isBlank()) {
            throw new IllegalArgumentException("缺少参数值：" + option);
        }
        return args[index];
    }

    private static int positiveInt(String value, int fallback) {
        int parsed = Integer.parseInt(value.trim());
        return parsed <= 0 ? fallback : parsed;
    }

    private static long positiveLong(String value, long fallback) {
        long parsed = Long.parseLong(value.trim());
        return parsed <= 0 ? fallback : parsed;
    }

    private static long nonNegativeLong(String value) {
        return Math.max(0, Long.parseLong(value.trim()));
    }

    private static WorkerResult execute(WorkerRequest request) throws Exception {
        validateCwdWithinAllowedRoots(request);
        if (request.backgroundLogPath() != null) {
            return startBackground(request);
        }
        Path sandboxPath = prepareSandbox(request);
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.cwd().toFile());
            int workerEnvBlockedCount = filterBlockedEnvironment(builder.environment(), request.blockedEnvNameFragments(),
                    request.allowedEnvNames());
            workerEnvBlockedCount += applyEnvOverrides(builder.environment(), request.envOverrides(),
                    request.blockedEnvNameFragments(), request.allowedEnvNames());
            applySandboxEnvironment(builder.environment(), sandboxPath);
            long started = System.nanoTime();
            Process process = builder.start();
            WindowsJobObjectSupport.JobHandle windowsJob = null;
            try {
                // Windows 上通过 Job Object 做硬限制；Linux/Unix 保留进程树 RSS 软采样。
                windowsJob = WindowsJobObjectSupport.attach(process, request.maxMemoryBytes()).orElse(null);
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                AtomicBoolean stdoutTruncated = new AtomicBoolean(false);
                AtomicBoolean stderrTruncated = new AtomicBoolean(false);
                Thread stdoutReader = streamReader(process.getInputStream(), stdout, request.maxOutputBytes(), stdoutTruncated);
                Thread stderrReader = streamReader(process.getErrorStream(), stderr, request.maxOutputBytes(), stderrTruncated);
                boolean finished = false;
                boolean timedOut = false;
                boolean resourceLimited = false;
                String resourceLimitReason = "";
                long observedCpuTimeMs = 0;
                long observedMemoryBytes = 0;
                long deadline = started + Duration.ofMillis(request.timeoutMs()).toNanos();
                long nextMemoryCheck = started;
                while (true) {
                    if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        finished = true;
                        break;
                    }
                    long cpuTimeMs = processTreeCpuTimeMs(process.toHandle());
                    observedCpuTimeMs = Math.max(observedCpuTimeMs, cpuTimeMs);
                    if (request.maxCpuTimeMs() > 0 && cpuTimeMs > request.maxCpuTimeMs()) {
                        // CPU 限制按命令进程树累计值计算，避免子进程绕开单进程监控。
                        resourceLimited = true;
                        resourceLimitReason = "cpu-time";
                        stopProcessTree(process.toHandle(), request.terminationGraceMs());
                        break;
                    }
                    long now = System.nanoTime();
                    if (request.maxMemoryBytes() > 0 && now >= nextMemoryCheck) {
                        long memoryBytes = processTreeMemoryBytes(windowsJob, process.toHandle());
                        observedMemoryBytes = Math.max(observedMemoryBytes, memoryBytes);
                        if (memoryBytes > request.maxMemoryBytes()) {
                            // Linux/Unix 采样超限时主动强杀；Windows 通常由 Job Object 先完成硬终止。
                            resourceLimited = true;
                            resourceLimitReason = "memory";
                            stopProcessTree(process.toHandle(), request.terminationGraceMs());
                            break;
                        }
                        nextMemoryCheck = now + Duration.ofMillis(500).toNanos();
                    }
                    if (System.nanoTime() >= deadline) {
                        timedOut = true;
                        stopProcessTree(process.toHandle(), request.terminationGraceMs());
                        break;
                    }
                }
                if (finished && windowsJob != null && windowsJob.limitLikelyExceeded() && process.exitValue() != 0) {
                    // Job Object 可能先于轮询杀掉命令进程，这里把系统退出码归一成资源限制结果。
                    resourceLimited = true;
                    resourceLimitReason = "memory";
                }
                if (!finished && !timedOut && !resourceLimited) {
                    stopProcessTree(process.toHandle(), request.terminationGraceMs());
                }
                stdoutReader.join(1000);
                stderrReader.join(1000);
                long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
                long cpuTimeMs = Math.max(observedCpuTimeMs, processTreeCpuTimeMs(process.toHandle()));
                long memoryBytes = Math.max(observedMemoryBytes, processTreeMemoryBytes(windowsJob, process.toHandle()));
                int exitCode = finished && !resourceLimited ? process.exitValue() : (resourceLimited ? RESOURCE_LIMIT_EXIT : 124);
                return new WorkerResult(exitCode, timedOut, elapsedMs,
                        stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
                        stdoutTruncated.get(), stderrTruncated.get(), resourceLimited, resourceLimitReason, cpuTimeMs,
                        memoryBytes, workerEnvBlockedCount, pathText(sandboxPath), request.keepSandbox());
            } catch (RuntimeException e) {
                if (request.maxMemoryBytes() > 0 && windowsJob == null) {
                    stopProcessTree(process.toHandle(), request.terminationGraceMs());
                }
                throw e;
            } finally {
                if (windowsJob != null) {
                    windowsJob.close();
                }
            }
        } finally {
            cleanupSandbox(sandboxPath, request.keepSandbox());
        }
    }

    private static WorkerResult startBackground(WorkerRequest request) throws Exception {
        validatePathWithinAllowedRoots(request.backgroundLogPath(), "worker background logPath", request.allowedRoots());
        Path sandboxPath = prepareSandbox(request);
        boolean processStarted = false;
        try {
            Files.createDirectories(request.backgroundLogPath().getParent());
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.cwd().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(request.backgroundLogPath().toFile()));
            int workerEnvBlockedCount = filterBlockedEnvironment(builder.environment(), request.blockedEnvNameFragments(),
                    request.allowedEnvNames());
            workerEnvBlockedCount += applyEnvOverrides(builder.environment(), request.envOverrides(),
                    request.blockedEnvNameFragments(), request.allowedEnvNames());
            applySandboxEnvironment(builder.environment(), sandboxPath);
            long started = System.nanoTime();
            Process process = builder.start();
            processStarted = true;
            // 后台模式只负责隔离启动和启动期确认，真实进程生命周期交给主服务 ManagedProcessStore 继续管理。
            boolean exited = process.waitFor(request.backgroundWaitMs(), TimeUnit.MILLISECONDS);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean alive = process.isAlive();
            int exitCode = exited ? process.exitValue() : 0;
            String stdout = "backgroundPid: " + process.pid() + "\n"
                    + "backgroundAlive: " + alive + "\n"
                    + "backgroundExitCode: " + (exited ? process.exitValue() : "") + "\n"
                    + "backgroundLogPath: " + request.backgroundLogPath() + "\n"
                    + "backgroundWaitMs: " + request.backgroundWaitMs() + "\n"
                    + "workerSandboxPath: " + pathText(sandboxPath) + "\n"
                    + "workerSandboxKept: " + (sandboxPath != null);
            return new WorkerResult(exitCode, false, elapsedMs, stdout, "",
                    false, false, false, "", 0, 0, workerEnvBlockedCount,
                    pathText(sandboxPath), sandboxPath != null);
        } finally {
            if (!processStarted) {
                cleanupSandbox(sandboxPath, false);
            }
        }
    }

    private static Path prepareSandbox(WorkerRequest request) throws IOException {
        if (request.sandboxRoot() == null) {
            return null;
        }
        validatePathWithinAllowedRoots(request.sandboxRoot(), "worker sandboxRoot", request.allowedRoots());
        Files.createDirectories(request.sandboxRoot());
        Path sandboxPath = request.sandboxRoot().resolve("worker-" + UUID.randomUUID()).normalize();
        if (!sandboxPath.startsWith(request.sandboxRoot())) {
            throw new IllegalArgumentException("worker sandboxPath 不在 sandboxRoot 内：" + sandboxPath);
        }
        Files.createDirectories(sandboxPath);
        return sandboxPath;
    }

    private static void applySandboxEnvironment(Map<String, String> environment, Path sandboxPath) {
        if (environment == null || sandboxPath == null) {
            return;
        }
        String value = sandboxPath.toString();
        // 临时目录变量统一指向本次 worker 的独立目录，兼容 Windows、Linux 和 macOS 常见工具。
        environment.put("CLAW_WORKER_SANDBOX_DIR", value);
        environment.put("TMP", value);
        environment.put("TEMP", value);
        environment.put("TMPDIR", value);
    }

    private static void cleanupSandbox(Path sandboxPath, boolean keepSandbox) {
        if (sandboxPath == null || keepSandbox || !Files.exists(sandboxPath)) {
            return;
        }
        String fileName = sandboxPath.getFileName() == null ? "" : sandboxPath.getFileName().toString();
        if (!fileName.startsWith("worker-")) {
            return;
        }
        try (var stream = Files.walk(sandboxPath)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不覆盖命令结果；路径会通过审计字段暴露，后续可人工处理。
                }
            });
        } catch (IOException ignored) {
            // 清理失败不影响 worker 主结果。
        }
    }

    private static String pathText(Path path) {
        return path == null ? "" : path.toString();
    }

    private static void validateCwdWithinAllowedRoots(WorkerRequest request) {
        if (request.allowedRoots().isEmpty()) {
            return;
        }
        boolean allowed = request.allowedRoots().stream().anyMatch(root -> request.cwd().startsWith(root));
        if (!allowed) {
            // allowed roots 在 worker 内再次校验，避免主进程参数构造错误时隔离进程越界执行。
            throw new IllegalArgumentException("worker cwd 不在 allowed roots 内：" + request.cwd());
        }
    }

    private static void validatePathWithinAllowedRoots(Path path, String label, List<Path> allowedRoots) {
        if (path == null || allowedRoots.isEmpty()) {
            return;
        }
        boolean allowed = allowedRoots.stream().anyMatch(root -> path.startsWith(root));
        if (!allowed) {
            throw new IllegalArgumentException(label + " 不在 allowed roots 内：" + path);
        }
    }

    private static Thread streamReader(InputStream input, ByteArrayOutputStream output,
                                       int maxOutputBytes, AtomicBoolean truncated) {
        Thread thread = new Thread(() -> {
            try (input; output) {
                truncated.set(copyLimited(input, output, maxOutputBytes));
            } catch (Exception ignored) {
                // 输出读取失败不应掩盖真实命令退出状态，stderr/stdout 会按已读取内容返回。
            }
        }, "claw-agent-worker-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean copyLimited(InputStream input, ByteArrayOutputStream output, int maxOutputBytes) throws IOException {
        int limit = maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes;
        byte[] buffer = new byte[8192];
        int remaining = limit;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (remaining > 0) {
                int writable = Math.min(read, remaining);
                output.write(buffer, 0, writable);
                remaining -= writable;
                if (writable < read && !truncated) {
                    writeTruncatedMarker(output, limit);
                    truncated = true;
                }
            } else if (!truncated) {
                writeTruncatedMarker(output, limit);
                truncated = true;
            }
            // 超出上限后仍继续 drain 管道，避免子进程 stdout/stderr 写满后卡死。
        }
        return truncated;
    }

    private static void writeTruncatedMarker(ByteArrayOutputStream output, int limit) throws IOException {
        output.write(("\n[worker 输出已按 WORKER_MAX_OUTPUT_BYTES=" + limit + " 截断，后续字节已丢弃]\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void stopProcessTree(ProcessHandle root, long terminationGraceMs) {
        List<ProcessHandle> descendants = root.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        root.destroy();
        // 先给子进程一次正常退出窗口，避免能处理 SIGTERM/CTRL-BREAK 的命令丢失清理机会。
        waitForExit(root, descendants, terminationGraceMs);
        if (root.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (root.isAlive()) {
                root.destroyForcibly();
            }
            waitForExit(root, descendants, terminationGraceMs);
        }
    }

    private static void waitForExit(ProcessHandle root, List<ProcessHandle> descendants, long timeoutMs) {
        List<ProcessHandle> handles = new ArrayList<>(descendants);
        handles.add(root);
        handles.stream().filter(ProcessHandle::isAlive).forEach(handle -> {
            try {
                handle.onExit().get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 超时后交给后续强杀分支处理。
            }
        });
    }

    private static long processTreeCpuTimeMs(ProcessHandle root) {
        List<ProcessHandle> handles = new ArrayList<>(root.descendants().toList());
        handles.add(root);
        return handles.stream()
                .map(ProcessHandle::info)
                .map(ProcessHandle.Info::totalCpuDuration)
                .flatMap(java.util.Optional::stream)
                .mapToLong(Duration::toMillis)
                .sum();
    }

    private static long processTreeMemoryBytes(WindowsJobObjectSupport.JobHandle windowsJob, ProcessHandle root) {
        if (windowsJob != null) {
            return windowsJob.memoryBytes();
        }
        List<Long> pids = new ArrayList<>(root.descendants().map(ProcessHandle::pid).toList());
        pids.add(root.pid());
        if (pids.isEmpty()) {
            return 0;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return 0;
        }
        if (Files.isDirectory(Path.of("/proc"))) {
            return linuxRssBytes(pids);
        }
        return unixRssBytes(pids);
    }

    private static long linuxRssBytes(List<Long> pids) {
        long total = 0;
        for (Long pid : pids) {
            Path status = Path.of("/proc", String.valueOf(pid), "status");
            try {
                for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                    if (line.startsWith("VmRSS:")) {
                        total += parseKilobytes(line) * 1024;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // 进程可能刚退出；内存采样失败不应影响正常命令结果。
            }
        }
        return total;
    }

    private static long unixRssBytes(List<Long> pids) {
        long total = 0;
        for (Long pid : pids) {
            try {
                Process process = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    continue;
                }
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                String digits = output.replaceAll("[^0-9]", "");
                if (!digits.isBlank()) {
                    total += Long.parseLong(digits) * 1024;
                }
            } catch (Exception ignored) {
                // macOS/Unix 采样失败时按 0 处理，避免误杀。
            }
        }
        return total;
    }

    private static long parseKilobytes(String line) {
        String digits = line == null ? "" : line.replaceAll("[^0-9]", "");
        return digits.isBlank() ? 0 : Long.parseLong(digits);
    }

    private static int filterBlockedEnvironment(Map<String, String> environment, List<String> blockedFragments,
                                                List<String> allowedEnvNames) {
        if (environment == null || environment.isEmpty() || blockedFragments == null || blockedFragments.isEmpty()) {
            return 0;
        }
        List<String> normalizedAllowedNames = allowedEnvNames == null ? List.of() : allowedEnvNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toUpperCase(java.util.Locale.ROOT))
                .toList();
        List<String> normalizedFragments = blockedFragments.stream()
                .filter(fragment -> fragment != null && !fragment.isBlank())
                .map(fragment -> fragment.trim().toUpperCase(java.util.Locale.ROOT))
                .toList();
        if (normalizedFragments.isEmpty()) {
            return 0;
        }
        int before = environment.size();
        // worker 内再次过滤真实命令环境；显式允许的变量名可用于高危命令必要凭证传递。
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(java.util.Locale.ROOT);
            return !normalizedAllowedNames.contains(normalized)
                    && normalizedFragments.stream().anyMatch(normalized::contains);
        });
        return before - environment.size();
    }

    private static int applyEnvOverrides(Map<String, String> environment, Map<String, String> envOverrides,
                                         List<String> blockedFragments, List<String> allowedEnvNames) {
        if (environment == null || envOverrides == null || envOverrides.isEmpty()) {
            return 0;
        }
        List<String> normalizedAllowedNames = allowedEnvNames == null ? List.of() : allowedEnvNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.trim().toUpperCase(Locale.ROOT))
                .toList();
        List<String> normalizedFragments = blockedFragments == null ? List.of() : blockedFragments.stream()
                .filter(fragment -> fragment != null && !fragment.isBlank())
                .map(fragment -> fragment.trim().toUpperCase(Locale.ROOT))
                .toList();
        int blocked = 0;
        for (Map.Entry<String, String> entry : envOverrides.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                blocked++;
                continue;
            }
            // 显式 env 是脚本/工具作者声明的运行依赖，但仍不能绕过敏感变量片段拦截。
            String normalized = name.trim().toUpperCase(Locale.ROOT);
            boolean sensitive = !normalizedAllowedNames.contains(normalized)
                    && normalizedFragments.stream().anyMatch(normalized::contains);
            if (sensitive) {
                blocked++;
                continue;
            }
            environment.put(name.trim(), entry.getValue() == null ? "" : entry.getValue());
        }
        return blocked;
    }

    private static void printResult(WorkerResult result) {
        System.out.println("CLAW_WORKER_RESULT_V1");
        System.out.println("exitCode: " + result.exitCode());
        System.out.println("timedOut: " + result.timedOut());
        System.out.println("elapsedMs: " + result.elapsedMs());
        System.out.println("stdoutTruncated: " + result.stdoutTruncated());
        System.out.println("stderrTruncated: " + result.stderrTruncated());
        System.out.println("resourceLimited: " + result.resourceLimited());
        System.out.println("resourceLimitReason: " + result.resourceLimitReason());
        System.out.println("cpuTimeMs: " + result.cpuTimeMs());
        System.out.println("memoryBytes: " + result.memoryBytes());
        System.out.println("workerEnvBlockedCount: " + result.workerEnvBlockedCount());
        System.out.println("workerSandboxPath: " + result.workerSandboxPath());
        System.out.println("workerSandboxKept: " + result.workerSandboxKept());
        System.out.println("stdoutBase64: " + encode(result.stdout()));
        System.out.println("stderrBase64: " + encode(result.stderr()));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private record WorkerRequest(Path cwd, List<Path> allowedRoots, List<String> blockedEnvNameFragments,
                                 List<String> allowedEnvNames,
                                 long timeoutMs, long terminationGraceMs,
                                 int maxOutputBytes, long maxCpuTimeMs, long maxMemoryBytes,
                                 Path backgroundLogPath, long backgroundWaitMs, Path sandboxRoot,
                                 boolean keepSandbox, List<String> command, Map<String, String> envOverrides) {
    }

    private record WorkerResult(int exitCode, boolean timedOut, long elapsedMs, String stdout, String stderr,
                                 boolean stdoutTruncated, boolean stderrTruncated, boolean resourceLimited,
                                 String resourceLimitReason, long cpuTimeMs, long memoryBytes, int workerEnvBlockedCount,
                                 String workerSandboxPath, boolean workerSandboxKept) {
    }
}
