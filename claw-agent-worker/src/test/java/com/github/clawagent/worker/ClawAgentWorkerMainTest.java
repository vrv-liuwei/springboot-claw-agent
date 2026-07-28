package com.github.clawagent.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClawAgentWorkerMainTest {
    @TempDir
    Path tempDir;

    @Test
    void truncatesOutputButKeepsDrainingCommandStreams() throws Exception {
        WorkerRun result = runWorker("2000", "24", javaCommand(),
                "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "emit-large");

        assertEquals(0, result.exitCode());
        assertEquals("false", result.fields().get("timedOut"));
        assertEquals("true", result.fields().get("stdoutTruncated"));
        assertTrue(result.stdout().contains("worker 输出已按 WORKER_MAX_OUTPUT_BYTES=24 截断"));
    }

    @Test
    void timeoutTerminatesWholeProcessTree() throws Exception {
        Path grandchildPidFile = tempDir.resolve("grandchild.pid");

        WorkerRun result = runWorker("3000", "8192", javaCommand(),
                "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "spawn-grandchild", grandchildPidFile.toString());

        assertEquals(124, result.exitCode());
        assertEquals("true", result.fields().get("timedOut"));
        assertTrue(Files.isRegularFile(grandchildPidFile), "测试子进程应先写出孙进程 PID");
        long grandchildPid = Long.parseLong(Files.readString(grandchildPidFile).trim());
        assertProcessStops(grandchildPid);
    }

    @Test
    void cpuLimitTerminatesBusyProcess() throws Exception {
        assumeTrue(ProcessHandle.current().info().totalCpuDuration().isPresent(),
                "当前 JDK/OS 不暴露进程 CPU 时间，跳过 CPU 限制集成测试");

        WorkerRun result = runWorkerWithCpuLimit("10000", "8192", "80", javaCommand(),
                "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "busy-loop");

        assertEquals(125, result.exitCode());
        assertEquals("false", result.fields().get("timedOut"));
        assertEquals("true", result.fields().get("resourceLimited"));
        assertEquals("cpu-time", result.fields().get("resourceLimitReason"));
        assertTrue(Long.parseLong(result.fields().get("cpuTimeMs")) >= 80);
    }

    @Test
    void memoryLimitTerminatesCommandProcess() throws Exception {
        WorkerRun result;
        if (isWindows()) {
            result = runWorkerWithMemoryLimit("10000", "8192", String.valueOf(64L * 1024 * 1024), javaCommand(),
                    "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                    WorkerTestProcess.class.getName(), "allocate-memory", "160");
        } else {
            result = runWorkerWithMemoryLimit("10000", "8192", "1", javaCommand(),
                    "-cp", System.getProperty("java.class.path"),
                    WorkerTestProcess.class.getName(), "sleep");
        }

        System.out.println("Worker stdout:\n" + result);
        assertEquals(125, result.exitCode());
        assertEquals("false", result.fields().get("timedOut"));
        assertEquals("true", result.fields().get("resourceLimited"));
        assertEquals("memory", result.fields().get("resourceLimitReason"));
        assertTrue(Long.parseLong(result.fields().get("memoryBytes")) > 0);
    }

    @Test
    void rejectsCwdOutsideAllowedRootsInsideWorker() throws Exception {
        Path allowedRoot = tempDir.resolve("allowed-root");
        Files.createDirectories(allowedRoot);

        WorkerRun result = runWorkerWithOptions(tempDir, List.of(allowedRoot), "2000", "8192", "0", "0",
                javaCommand(), "-version");

        assertEquals(97, result.exitCode());
        assertTrue(result.stderr().contains("worker cwd 不在 allowed roots 内"));
    }

    @Test
    void filtersSensitiveEnvironmentBeforeStartingCommandProcess() throws Exception {
        WorkerRun result = runWorkerWithOptions(tempDir, List.of(tempDir), List.of("API_KEY"),
                List.of(), Map.of("CLAW_TEST_API_KEY", "should-not-leak"), "2000", "8192", "0",
                "0", javaCommand(), "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "print-env", "CLAW_TEST_API_KEY");

        assertEquals(0, result.exitCode());
        assertTrue(Integer.parseInt(result.fields().get("workerEnvBlockedCount")) >= 1);
        assertEquals("MISSING", result.stdout());
    }

    @Test
    void explicitAllowedEnvironmentSurvivesSensitiveNameFilter() throws Exception {
        WorkerRun result = runWorkerWithOptions(tempDir, List.of(tempDir), List.of("API_KEY"),
                List.of("CLAW_TEST_API_KEY"), Map.of("CLAW_TEST_API_KEY", "required-secret"), "2000", "8192", "0",
                "0", javaCommand(), "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "print-env", "CLAW_TEST_API_KEY");

        assertEquals(0, result.exitCode());
        assertTrue(Integer.parseInt(result.fields().get("workerEnvBlockedCount")) >= 0);
        assertEquals("required-secret", result.stdout());
    }

    @Test
    void foregroundSandboxSetsTempEnvironmentAndCleansDirectory() throws Exception {
        Path sandboxRoot = tempDir.resolve("worker-sandbox");

        WorkerRun result = runWorkerWithOptions(tempDir, List.of(tempDir), List.of(), List.of(), Map.of(),
                "2000", "8192", "0", "0", null, "0", sandboxRoot, false,
                javaCommand(), "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "print-env", "CLAW_WORKER_SANDBOX_DIR");

        Path sandboxPath = Path.of(result.stdout()).toAbsolutePath().normalize();
        assertEquals(0, result.exitCode());
        assertEquals(sandboxPath.toString(), result.fields().get("workerSandboxPath"));
        assertEquals("false", result.fields().get("workerSandboxKept"));
        assertTrue(sandboxPath.startsWith(sandboxRoot.toAbsolutePath().normalize()));
        assertFalse(Files.exists(sandboxPath), "前台 worker 完成后应清理本次隔离临时目录");
    }

    @Test
    void backgroundModeStartsProcessAndReturnsManagedPid() throws Exception {
        Path logPath = tempDir.resolve("background.log");
        Path sandboxRoot = tempDir.resolve("worker-sandbox");

        WorkerRun result = runBackgroundWorker("500", logPath, sandboxRoot, javaCommand(),
                "-cp", System.getProperty("java.class.path"),
                WorkerTestProcess.class.getName(), "sleep");

        assertEquals(0, result.exitCode());
        assertEquals("false", result.fields().get("timedOut"));
        assertTrue(result.stdout().contains("backgroundAlive: true"));
        assertTrue(result.stdout().contains("backgroundLogPath: " + logPath.toAbsolutePath().normalize()));
        assertTrue(Files.isRegularFile(logPath), "后台模式应先创建受控日志文件");
        Path sandboxPath = Path.of(textField(result.stdout(), "workerSandboxPath"));
        assertTrue(Files.isDirectory(sandboxPath), "后台进程启动后应保留隔离临时目录");
        assertEquals("true", textField(result.stdout(), "workerSandboxKept"));
        long pid = Long.parseLong(textField(result.stdout(), "backgroundPid"));
        try {
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "worker 返回的后台 pid 应仍存活");
        } finally {
            ProcessHandle.of(pid).ifPresent(handle -> {
                handle.destroyForcibly();
                try {
                    handle.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // 测试清理兜底；如果进程已退出或等待失败，后续临时目录清理会暴露问题。
                }
            });
        }
    }

    private WorkerRun runWorker(String timeoutMs, String maxOutputBytes, String command, String... args) throws Exception {
        return runWorkerWithCpuLimit(timeoutMs, maxOutputBytes, "0", command, args);
    }

    private WorkerRun runBackgroundWorker(String waitMs, Path logPath, Path sandboxRoot, String command, String... args) throws Exception {
        return runWorkerWithOptions(tempDir, List.of(tempDir), List.of(), List.of(), Map.of(), waitMs,
                "8192", "0", "0", logPath, waitMs, sandboxRoot, true, command, args);
    }

    private WorkerRun runWorkerWithCpuLimit(String timeoutMs, String maxOutputBytes, String maxCpuTimeMs,
                                            String command, String... args) throws Exception {
        return runWorkerWithOptions(tempDir, List.of(tempDir), timeoutMs, maxOutputBytes, maxCpuTimeMs, "0", command, args);
    }

    private WorkerRun runWorkerWithMemoryLimit(String timeoutMs, String maxOutputBytes, String maxMemoryBytes,
                                               String command, String... args) throws Exception {
        return runWorkerWithOptions(tempDir, List.of(tempDir), timeoutMs, maxOutputBytes, "0", maxMemoryBytes, command, args);
    }

    private WorkerRun runWorkerWithOptions(Path cwd, List<Path> allowedRoots, String timeoutMs, String maxOutputBytes,
                                           String maxCpuTimeMs, String maxMemoryBytes, String command,
                                           String... args) throws Exception {
        return runWorkerWithOptions(cwd, allowedRoots, List.of(), Map.of(), timeoutMs, maxOutputBytes,
                maxCpuTimeMs, maxMemoryBytes, command, args);
    }

    private WorkerRun runWorkerWithOptions(Path cwd, List<Path> allowedRoots, List<String> blockedEnvNameFragments,
                                           Map<String, String> workerEnv, String timeoutMs, String maxOutputBytes,
                                           String maxCpuTimeMs, String maxMemoryBytes, String command, String... args) throws Exception {
        return runWorkerWithOptions(cwd, allowedRoots, blockedEnvNameFragments, List.of(), workerEnv, timeoutMs,
                maxOutputBytes, maxCpuTimeMs, maxMemoryBytes, null, "0", null, false, command, args);
    }

    private WorkerRun runWorkerWithOptions(Path cwd, List<Path> allowedRoots, List<String> blockedEnvNameFragments,
                                           List<String> allowedEnvNames, Map<String, String> workerEnv,
                                           String timeoutMs, String maxOutputBytes,
                                           String maxCpuTimeMs, String maxMemoryBytes, String command, String... args) throws Exception {
        return runWorkerWithOptions(cwd, allowedRoots, blockedEnvNameFragments, allowedEnvNames, workerEnv,
                timeoutMs, maxOutputBytes, maxCpuTimeMs, maxMemoryBytes, null, "0", null, false, command, args);
    }

    private WorkerRun runWorkerWithOptions(Path cwd, List<Path> allowedRoots, List<String> blockedEnvNameFragments,
                                           List<String> allowedEnvNames, Map<String, String> workerEnv,
                                           String timeoutMs, String maxOutputBytes,
                                           String maxCpuTimeMs, String maxMemoryBytes, Path backgroundLogPath,
                                           String backgroundWaitMs, Path sandboxRoot, boolean keepSandbox,
                                           String command, String... args) throws Exception {
        List<String> workerCommand = new ArrayList<>();
        workerCommand.add(javaCommand());
        workerCommand.add("-cp");
        workerCommand.add(System.getProperty("java.class.path"));
        workerCommand.add(ClawAgentWorkerMain.class.getName());
        workerCommand.add("--cwd");
        workerCommand.add(cwd.toString());
        workerCommand.add("--timeoutMs");
        workerCommand.add(timeoutMs);
        workerCommand.add("--maxOutputBytes");
        workerCommand.add(maxOutputBytes);
        workerCommand.add("--maxCpuTimeMs");
        workerCommand.add(maxCpuTimeMs);
        workerCommand.add("--maxMemoryBytes");
        workerCommand.add(maxMemoryBytes);
        if (backgroundLogPath != null) {
            workerCommand.add("--backgroundLogPath");
            workerCommand.add(backgroundLogPath.toString());
            workerCommand.add("--backgroundWaitMs");
            workerCommand.add(backgroundWaitMs);
        }
        if (sandboxRoot != null) {
            workerCommand.add("--sandboxRoot");
            workerCommand.add(sandboxRoot.toString());
            workerCommand.add("--keepSandbox");
            workerCommand.add(String.valueOf(keepSandbox));
        }
        for (Path allowedRoot : allowedRoots) {
            workerCommand.add("--allowedRoot");
            workerCommand.add(allowedRoot.toString());
        }
        for (String fragment : blockedEnvNameFragments) {
            workerCommand.add("--blockedEnvNameFragment");
            workerCommand.add(fragment);
        }
        for (String allowedEnvName : allowedEnvNames) {
            workerCommand.add("--allowedEnvName");
            workerCommand.add(allowedEnvName);
        }
        for (Map.Entry<String, String> entry : workerEnv.entrySet()) {
            workerCommand.add("--env");
            workerCommand.add(entry.getKey() + "=" + entry.getValue());
        }
        workerCommand.add("--command");
        workerCommand.add(command);
        for (String arg : args) {
            workerCommand.add("--arg");
            workerCommand.add(arg);
        }

        // 通过子 JVM 调用 main，避免 System.exit 影响当前测试进程。
        ProcessBuilder workerBuilder = new ProcessBuilder(workerCommand).directory(tempDir.toFile());
        workerBuilder.environment().putAll(workerEnv);
        Process worker = workerBuilder.start();
        boolean finished = worker.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            worker.destroyForcibly();
            worker.waitFor(2, TimeUnit.SECONDS);
        }
        assertTrue(finished, "worker 进程应在测试超时前退出");
        String stdout = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(worker.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stderr.isBlank(), stderr);
        return WorkerRun.parse(worker.exitValue(), stdout);
    }

    private void assertProcessStops(long pid) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return;
            }
            Thread.sleep(100);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "worker 超时后应强制终止整棵进程树");
    }

    private static String textField(String text, String name) {
        for (String line : text.split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0 && name.equals(line.substring(0, separator).trim())) {
                return line.substring(separator + 1).trim();
            }
        }
        return "";
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record WorkerRun(int exitCode, Map<String, String> fields, String stdout, String stderr) {
        static WorkerRun parse(int exitCode, String workerStdout) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : workerStdout.substring(workerStdout.indexOf("CLAW_WORKER_RESULT_V1")).split("\\R")) {
                int separator = line.indexOf(':');
                if (separator > 0) {
                    fields.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
                }
            }
            return new WorkerRun(exitCode, fields, decode(fields.get("stdoutBase64")), decode(fields.get("stderrBase64")));
        }

        private static String decode(String value) {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }
    }
}
