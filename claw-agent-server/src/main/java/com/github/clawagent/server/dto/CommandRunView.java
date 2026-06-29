package com.github.clawagent.server.dto;

public record CommandRunView(
        String stepId,
        String toolId,
        String status,
        String command,
        String cwd,
        Integer exitCode,
        String riskLevel,
        Long elapsedMs,
        String outputPreview
) {
}
