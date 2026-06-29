package com.github.clawagent.server.dto;

public record FailureAnalysisView(
        String category,
        String summary,
        String command,
        String cwd,
        boolean retryable,
        int retryLimit,
        String nextAction,
        String evidence
) {
}
