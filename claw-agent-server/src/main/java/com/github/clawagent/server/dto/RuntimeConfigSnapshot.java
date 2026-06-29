package com.github.clawagent.server.dto;

import java.util.Map;

public record RuntimeConfigSnapshot(
        String cwd,
        String configRoot,
        String configPath,
        boolean restartRequired,
        boolean applied,
        String message,
        ModelSettings model,
        ModelConfigView effectiveModel,
        EmbeddingConfigView embedding,
        MemoryExtractionConfigView memoryExtraction,
        MemoryGovernanceConfigView memoryGovernance,
        CostConfigView cost,
        LocalDevelopmentConfigView local,
        PolicySnapshotView policy,
        AuthConfigView auth,
        Map<String, ModelConfigView> models
) {
}
