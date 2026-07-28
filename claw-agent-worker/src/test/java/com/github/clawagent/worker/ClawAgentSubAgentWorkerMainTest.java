package com.github.clawagent.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClawAgentSubAgentWorkerMainTest {
    @TempDir
    Path tempDir;

    @Test
    void wrapsPlainRuntimeOutputAsSubAgentResult() throws Exception {
        SubAgentWorkerRun result = runSubAgentWorker("subagent-plain");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("CLAW_SUBAGENT_WORKER_RESULT_V1"));
        assertTrue(result.stdout().contains("\"answer\":\"child runtime ok\""));
        assertTrue(result.stdout().contains("\"status\":\"COMPLETED\""));
        assertTrue(result.stdout().contains("\"worker.adapter\":\"claw-agent-worker\""));
    }

    @Test
    void delegatesMarkedRuntimeResultWithoutWrappingAgain() throws Exception {
        SubAgentWorkerRun result = runSubAgentWorker("subagent-marked");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("\"answer\":\"delegated ok\""));
        assertTrue(result.stdout().contains("\"worker.delegated\":\"yes\""));
    }

    @Test
    void wrapsRuntimeFailureAsFailedSubAgentResult() throws Exception {
        SubAgentWorkerRun result = runSubAgentWorker("subagent-fail");

        assertEquals(9, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"FAILED\""));
        assertTrue(result.stdout().contains("Runtime"));
        assertTrue(result.stdout().contains("9"));
        assertTrue(result.stdout().contains("child runtime failed"));
    }

    @Test
    void terminatesRuntimeWhenAdapterTimeoutExpires() throws Exception {
        SubAgentWorkerRun result = runSubAgentWorker("sleep", "150", "8192", 10);

        assertEquals(124, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"FAILED\""));
        assertTrue(result.stdout().contains("Runtime"));
        assertTrue(result.stdout().contains("\"worker.exitCode\":\"124\""));
    }

    @Test
    void rejectsMissingRuntimeCommand() throws Exception {
        SubAgentWorkerRun result = runSubAgentWorkerWithoutRuntimeCommand();

        assertEquals(97, result.exitCode());
        assertTrue(result.stdout().contains("\"status\":\"FAILED\""));
        assertTrue(result.stdout().contains("Runtime"));
    }

    private SubAgentWorkerRun runSubAgentWorker(String mode) throws Exception {
        return runSubAgentWorker(mode, "5000", "8192", 10);
    }

    private SubAgentWorkerRun runSubAgentWorker(String mode, String timeoutMs, String maxOutputBytes, int waitSeconds) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ClawAgentSubAgentWorkerMain.class.getName());
        command.add("--timeoutMs");
        command.add(timeoutMs);
        command.add("--maxOutputBytes");
        command.add(maxOutputBytes);
        command.add("--");
        command.add(javaCommand());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(WorkerTestProcess.class.getName());
        command.add(mode);

        return runProcess(command, waitSeconds);
    }

    private SubAgentWorkerRun runSubAgentWorkerWithoutRuntimeCommand() throws Exception {
        List<String> command = new ArrayList<>();
        command.add(javaCommand());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ClawAgentSubAgentWorkerMain.class.getName());
        command.add("--timeoutMs");
        command.add("5000");
        return runProcess(command, 10);
    }

    private SubAgentWorkerRun runProcess(List<String> command, int waitSeconds) throws Exception {
        Process worker = new ProcessBuilder(command).directory(tempDir.toFile()).start();
        // stdin 模拟 server dispatcher 下发的子 Agent 任务协议。
        worker.getOutputStream().write("{\"protocol\":\"CLAW_SUBAGENT_WORKER_V1\",\"taskId\":\"task-1\"}"
                .getBytes(StandardCharsets.UTF_8));
        worker.getOutputStream().close();
        boolean finished = worker.waitFor(waitSeconds, TimeUnit.SECONDS);
        if (!finished) {
            worker.destroyForcibly();
            worker.waitFor(2, TimeUnit.SECONDS);
        }
        assertTrue(finished, "子 Agent worker 应在测试超时前退出");
        String stdout = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(worker.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(stderr.isBlank(), stderr);
        return new SubAgentWorkerRun(worker.exitValue(), stdout);
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record SubAgentWorkerRun(int exitCode, String stdout) {
    }
}
