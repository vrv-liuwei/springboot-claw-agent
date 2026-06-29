package com.github.clawagent.server.dto;

import com.github.clawagent.server.service.ProcessManagementService.ProcessView;

import java.util.List;

public record DevelopmentTaskSummary(
        String taskId,
        String status,
        List<FileChangeView> fileChanges,
        List<CommandRunView> commands,
        List<CommandRunView> tests,
        List<String> failures,
        List<String> risks,
        List<String> testCommandSuggestions,
        List<VerificationCommandView> verificationPlan,
        List<FailureAnalysisView> failureAnalyses,
        FinalResultView finalResult,
        GitReviewView gitReview,
        List<ProcessView> processes,
        String commitMessage
) {
}
