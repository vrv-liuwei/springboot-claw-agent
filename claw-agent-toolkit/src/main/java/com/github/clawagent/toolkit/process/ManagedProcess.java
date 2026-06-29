package com.github.clawagent.toolkit.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record ManagedProcess(
        long pid,
        Process process,
        List<String> command,
        Path cwd,
        Path logPath,
        Instant startedAt,
        String taskId,
        String sessionId,
        String projectPath,
        String healthUrl
) {
    public boolean isAlive() {
        if (process != null) {
            return process.isAlive();
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
