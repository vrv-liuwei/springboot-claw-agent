package com.github.clawagent.toolkit.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedProcessStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsHealthUrlWithManagedProcess() {
        Path storePath = tempDir.resolve("processes.tsv");
        ManagedProcessStore store = new ManagedProcessStore(storePath);
        ManagedProcess process = new ManagedProcess(
                12345L,
                null,
                List.of("mvn", "spring-boot:run"),
                tempDir,
                tempDir.resolve("service.log"),
                Instant.parse("2026-06-12T00:00:00Z"),
                "task-1",
                "session-1",
                tempDir.toString(),
                "http://127.0.0.1:8080/actuator/health");

        store.put(process);

        ManagedProcessStore reloaded = new ManagedProcessStore(storePath);
        ManagedProcess restored = reloaded.get(12345L).orElseThrow();
        assertEquals(process.command(), restored.command());
        assertEquals(process.cwd(), restored.cwd());
        assertEquals(process.logPath(), restored.logPath());
        assertEquals("task-1", restored.taskId());
        assertEquals("session-1", restored.sessionId());
        assertEquals(tempDir.toString(), restored.projectPath());
        assertEquals("http://127.0.0.1:8080/actuator/health", restored.healthUrl());
        assertFalse(restored.isAlive());
    }

    @Test
    void loadsLegacyRowsWithoutHealthUrl() throws Exception {
        Path storePath = tempDir.resolve("legacy-processes.tsv");
        String encodedCommand = Base64.getEncoder().encodeToString("cmd\u001f/c\u001fnpm run dev".getBytes(StandardCharsets.UTF_8));
        // 旧版本只有 8 列，不能因为新增 healthUrl 导致历史进程表无法加载。
        String legacyRow = "23456\t"
                + tempDir + "\t"
                + tempDir.resolve("legacy.log") + "\t"
                + "2026-06-12T00:00:00Z\t"
                + encodedCommand + "\t"
                + "task-2\t"
                + "session-2\t"
                + tempDir;
        Files.writeString(storePath, legacyRow, StandardCharsets.UTF_8);

        ManagedProcessStore store = new ManagedProcessStore(storePath);

        assertTrue(store.get(23456L).isPresent());
        ManagedProcess restored = store.get(23456L).orElseThrow();
        assertEquals(List.of("cmd", "/c", "npm run dev"), restored.command());
        assertEquals("task-2", restored.taskId());
        assertEquals("session-2", restored.sessionId());
        assertEquals(tempDir.toString(), restored.projectPath());
        assertNull(restored.healthUrl());
    }
}
