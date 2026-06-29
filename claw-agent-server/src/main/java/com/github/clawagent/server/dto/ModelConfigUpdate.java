package com.github.clawagent.server.dto;

import java.util.List;
import java.util.Map;

public record ModelConfigUpdate(
        String mode,
        String client,
        String defaultModel,
        String memoryModel,
        String visionModel,
        String planner,
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        Double temperature,
        Integer timeoutSeconds,
        Boolean vision,
        String embeddingProvider,
        String embeddingBaseUrl,
        String embeddingModel,
        String embeddingApiKey,
        Integer embeddingDimensions,
        Integer embeddingTimeoutSeconds,
        Boolean memoryExtractionEnabled,
        String memoryExtractionMode,
        Long memoryExtractionIntervalSeconds,
        Integer memoryExtractionBatchSize,
        Integer memoryGovernanceStaleAfterDays,
        Integer memoryGovernanceVeryStaleAfterDays,
        Boolean memoryGovernanceAutoArchiveEnabled,
        Integer memoryGovernanceArchiveAfterDays,
        Double memoryGovernanceArchiveBelowQuality,
        String costCurrency,
        Map<String, CostRuleView> costRules,
        String localWorkspaceRoot,
        String localDefaultShell,
        String localPermissionMode,
        List<String> localApprovedToolIds,
        List<String> localAllowedRoots,
        List<String> localRecentProjects,
        List<String> localTestCommands,
        Map<String, List<String>> localProjectTestCommands,
        List<String> localIgnorePatterns,
        List<String> localSensitivePathPatterns
) {
}
