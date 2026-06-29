package com.github.clawagent.server.dto;

public record GitReviewView(
        String cwd,
        String statusCommand,
        String diffCommand,
        boolean statusAlreadyRun,
        boolean diffAlreadyRun,
        Integer statusExitCode,
        Integer diffExitCode,
        String statusOutputPreview,
        String diffOutputPreview,
        String nextAction
) {
}
