package com.github.clawagent.server.dto;

import java.util.List;

public record FinalResultView(
        String outcome,
        String summary,
        String verificationStatus,
        boolean readyForCommit,
        int changedFiles,
        int commandsRun,
        int testsRun,
        int failedCommands,
        int riskCount,
        List<String> remainingRisks,
        List<String> nextActions
) {
}
