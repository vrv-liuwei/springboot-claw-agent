package com.github.clawagent.toolkit.execute;

import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecuteCommandWorkerIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void highRiskCommandRunsThroughWorkerAndReturnsAuditFields() throws Exception {
        Files.writeString(tempDir.resolve("package.json"), "{}");
        ExecuteToolkitProperties properties = properties(FakeWorkerJarSupport.createJar(tempDir, FakeWorkerMain.class));
        properties.setMaxTimeoutMs(1500);
        ExecuteCommandTool tool = new ExecuteCommandTool(properties);

        ToolResult result = tool.execute(new ToolCall("builtin.execute.command", Map.of(
                "command", "powershell",
                "args", "[\"-NoProfile\",\"-Command\",\"Write-Output hi\"]",
                "cwd", tempDir.toString(),
                "timeoutMs", "2000"
        )), null);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("requestedTimeoutMs: 2000"));
        assertTrue(result.content().contains("timeoutMs: 1500"));
        assertTrue(result.content().contains("timeoutCapped: true"));
        assertTrue(result.content().contains("riskLevel: high"));
        assertTrue(result.content().contains("workerIsolated: true"));
        assertTrue(result.content().contains("workerPoolWaitMs: "));
        assertTrue(result.content().contains("workerTerminationGraceMs: 1500"));
        assertTrue(result.content().contains("timedOut: false"));
        assertTrue(result.content().contains("stdoutTruncatedByWorker: false"));
        assertTrue(result.content().contains("stderrTruncatedByWorker: false"));
        assertTrue(result.content().contains("workerResourceLimited: false"));
        assertTrue(result.content().contains("workerCpuTimeMs: 9"));
        assertTrue(result.content().contains("workerMemoryBytes: 1024"));
        assertTrue(result.content().contains("workerEnvBlockedCount: "));
        assertTrue(result.content().contains("workerSandboxPath: fake-sandbox"));
        assertTrue(result.content().contains("workerSandboxKept: false"));
        assertTrue(result.content().contains("exitCode: 0"));
        assertTrue(result.content().contains("fake worker ok"));
        assertTrue(result.content().contains("--timeoutMs 1500"));
        assertTrue(result.content().contains("--sandboxRoot"));
        assertTrue(result.content().contains("--keepSandbox false"));
        assertTrue(result.content().contains("--maxMemoryBytes 2048"));
        assertTrue(result.content().contains("--allowedEnvName PATH"));
        assertTrue(result.content().contains("--allowedRoot"));
        assertTrue(result.content().contains(tempDir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void rejectsExecutionWhenWorkerPoolIsFull() throws Exception {
        ExecuteToolkitProperties properties = properties(FakeWorkerJarSupport.createJar(tempDir, SlowFakeWorkerMain.class));
        properties.setWorkerMaxConcurrent(1);
        properties.setWorkerAcquireTimeoutMs(100);
        WorkerCommandExecutor executor = new WorkerCommandExecutor(properties);
        ExecuteCommandTool.CommandInvocation invocation =
                new ExecuteCommandTool.CommandInvocation("powershell", "powershell", List.of("-Command", "echo hi"));

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> firstExecution = pool.submit(() -> assertDoesNotThrow(
                () -> executor.execute(invocation, tempDir, 5_000)));
        try {
            Thread.sleep(250);
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> executor.execute(invocation, tempDir, 5_000));
            assertTrue(error.getMessage().contains("worker 池已满"));
            firstExecution.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void passesExplicitEnvironmentOverridesToWorker() throws Exception {
        ExecuteToolkitProperties properties = properties(FakeWorkerJarSupport.createJar(tempDir, FakeWorkerMain.class));
        WorkerCommandExecutor executor = new WorkerCommandExecutor(properties);

        WorkerCommandExecutor.WorkerExecutionResult result = executor.execute(
                List.of("powershell", "-NoProfile", "-Command", "Write-Output hi"),
                tempDir,
                1_000,
                Map.of("CLAW_TEST_VISIBLE", "explicit-value"));

        assertTrue(result.stdout().contains("--env CLAW_TEST_VISIBLE=explicit-value"));
    }

    @Test
    void relativeWorkerJarSearchesParentDirectories() throws Exception {
        Path moduleDir = Path.of("").toAbsolutePath().normalize();
        Path projectRoot = moduleDir.getParent();
        Path workerTarget = Files.createDirectories(projectRoot.resolve("claw-agent-worker").resolve("target"));
        Path workerJar = FakeWorkerJarSupport.createJar(workerTarget, FakeWorkerMain.class);
        ExecuteToolkitProperties properties = properties(workerTarget.resolve("unused.jar"));
        properties.setWorkerJar("claw-agent-worker/target/FakeWorkerMain.jar");
        WorkerCommandExecutor executor = new WorkerCommandExecutor(properties);
        try {
            // 服务从 claw-agent-server 模块或 IDE 工作目录启动时，相对 WORKER_JAR 需要继续向父目录找项目根。
            WorkerCommandExecutor.WorkerExecutionResult result = executor.execute(
                    List.of("powershell", "-NoProfile", "-Command", "Write-Output hi"),
                    tempDir,
                    1_000,
                    Map.of());

            assertTrue(result.stdout().contains("fake worker ok"));
        } finally {
            Files.deleteIfExists(workerJar);
        }
    }

    @Test
    void missingWorkerJarReportsCheckedPaths() throws Exception {
        ExecuteToolkitProperties properties = properties(tempDir.resolve("missing-worker.jar"));
        WorkerCommandExecutor executor = new WorkerCommandExecutor(properties);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> executor.execute(
                List.of("powershell", "-NoProfile", "-Command", "Write-Output hi"),
                tempDir,
                1_000,
                Map.of()));

        assertTrue(error.getMessage().contains("WORKER_JAR="));
        assertTrue(error.getMessage().contains("user.dir="));
        assertTrue(error.getMessage().contains("已检查路径="));
    }

    @Test
    void rebuildsWorkerEnvironmentFromAllowList() throws Exception {
        ExecuteToolkitProperties properties = properties(FakeWorkerJarSupport.createJar(tempDir, FakeWorkerMain.class));
        properties.setWorkerAllowedEnvNames(List.of("PATH", "ANYSEARCH_API_KEY"));
        WorkerCommandExecutor executor = new WorkerCommandExecutor(properties);
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "tool-path");
        environment.put("JAVA_HOME", "jdk");
        environment.put("ANYSEARCH_API_KEY", "secret");
        environment.put("DDIO_TOKEN", "secret");
        environment.put("NORMAL_FLAG", "keep");

        int blocked = executor.prepareWorkerEnvironment(environment);

        assertTrue(blocked >= 3);
        assertTrue(environment.containsKey("PATH"));
        assertTrue(environment.containsKey("ANYSEARCH_API_KEY"));
        assertTrue(!environment.containsKey("JAVA_HOME"));
        assertTrue(!environment.containsKey("NORMAL_FLAG"));
        assertTrue(!environment.containsKey("DDIO_TOKEN"));
    }

    private ExecuteToolkitProperties properties(Path workerJar) {
        ExecuteToolkitProperties properties = new ExecuteToolkitProperties();
        properties.setAllowedRoots(List.of(tempDir.toString()));
        properties.setDefaultCwd(tempDir.toString());
        properties.setWorkerEnabled(true);
        properties.setWorkerJar(workerJar.toString());
        properties.setWorkerMaxMemoryBytes(2048);
        return properties;
    }
}
