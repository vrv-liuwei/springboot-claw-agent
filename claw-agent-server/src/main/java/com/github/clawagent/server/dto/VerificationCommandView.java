package com.github.clawagent.server.dto;

public record VerificationCommandView(
        String command,
        String cwd,
        String source,
        String reason,
        boolean alreadyRun,
        String lastStatus,
        Integer lastExitCode,
        Long lastElapsedMs
) {
}
