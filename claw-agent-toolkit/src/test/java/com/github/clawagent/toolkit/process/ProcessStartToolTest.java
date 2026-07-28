package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;
import com.github.clawagent.toolkit.execute.FakeWorkerJarSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStartToolTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsLogPathOutsideAllowedRoots() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        ProcessStartTool tool = new ProcessStartTool(new ManagedProcessStore(tempDir.resolve("processes.tsv")),
                properties(workspace));

        ToolResult result = tool.execute(new ToolCall("builtin.process.start", Map.of(
                "command", javaCommand(),
                "args", "[\"-version\"]",
                "cwd", workspace.toString(),
                "logPath", outside.resolve("process.log").toString()
        )), null);

        assertFalse(result.success(), result.content());
        assertTrue(result.content().contains("logPath 不在 execute allowed roots 内"), result.content());
    }

    @Test
    void relativeLogPathStaysInsideWorkingDirectory() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        ProcessStartTool tool = new ProcessStartTool(new ManagedProcessStore(tempDir.resolve("processes.tsv")),
                properties(workspace));

        ToolResult result = tool.execute(new ToolCall("builtin.process.start", Map.of(
                "command", quickExitCommand(),
                "args", quickExitArgs(),
                "cwd", workspace.toString(),
                "logPath", "logs/process.log",
                "processWaitMs", "300"
        )), null);

        assertFalse(result.success(), result.content());
        assertTrue(result.content().contains("logPath: " + workspace.resolve("logs/process.log").normalize()),
                result.content());
        assertTrue(Files.isRegularFile(workspace.resolve("logs/process.log")));
    }

    private ExecuteToolkitProperties properties(Path workspace) {
        ExecuteToolkitProperties properties = new ExecuteToolkitProperties();
        properties.setAllowedRoots(List.of(workspace.toString()));
        properties.setDefaultCwd(workspace.toString());
        properties.setProcessWaitMs(50);
        properties.setWorkerEnabled(false);
        return properties;
    }

    @Test
    void workerEnabledStartDelegatesBackgroundProcessToWorker() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        ExecuteToolkitProperties properties = properties(workspace);
        properties.setWorkerEnabled(true);
        properties.setWorkerJar(FakeWorkerJarSupport.createJar(tempDir, FakeBackgroundWorkerMain.class).toString());
        ManagedProcessStore store = new ManagedProcessStore(tempDir.resolve("processes.tsv"));
        ProcessStartTool tool = new ProcessStartTool(store, properties);

        ToolResult result = tool.execute(new ToolCall("builtin.process.start", Map.of(
                "command", quickExitCommand(),
                "args", quickExitArgs(),
                "cwd", workspace.toString(),
                "logPath", "logs/process.log",
                "processWaitMs", "50"
        )), null);

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("workerIsolated: true"), result.content());
        assertTrue(result.content().contains("workerEnvBlockedCount: "), result.content());
        assertTrue(store.list().stream().anyMatch(ManagedProcess::isAlive), "worker 返回的后台 pid 应写入进程表");
    }

    private static String javaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static String quickExitCommand() {
        return isWindows() ? "cmd" : "sh";
    }

    private static String quickExitArgs() {
        // 这里用稳定退出的 shell 命令，只验证 logPath 解析和 allowed roots，不把测试结果绑到 Java 启动耗时上。
        return isWindows() ? "[\"/c\",\"exit 0\"]" : "[\"-c\",\"exit 0\"]";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
