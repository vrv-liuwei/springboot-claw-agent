package com.github.clawagent.toolkit.process;

import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStopToolTest {
    @TempDir
    Path tempDir;

    @Test
    void stopsManagedProcessAndRemovesStoreRecord() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName())
                .redirectErrorStream(true)
                .start();
        try {
            ManagedProcessStore store = new ManagedProcessStore(tempDir.resolve("processes.tsv"));
            ManagedProcess process = new ManagedProcess(
                    child.pid(),
                    child,
                    List.of(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName()),
                    tempDir,
                    tempDir.resolve("process.log"),
                    Instant.now(),
                    "task-stop",
                    "session-stop",
                    tempDir.toString(),
                    null);
            store.put(process);

            ProcessStopTool tool = new ProcessStopTool(store, new ExecuteToolkitProperties());
            ToolResult result = tool.execute(new ToolCall("builtin.process.stop", Map.of("pid", String.valueOf(child.pid()))), null);

            assertTrue(result.success(), result.content());
            assertTrue(result.content().contains("status: stopped"), result.content());
            assertFalse(child.isAlive(), "子进程应已停止");
            assertTrue(store.get(child.pid()).isEmpty(), "停止成功后应移除托管记录");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }
}
