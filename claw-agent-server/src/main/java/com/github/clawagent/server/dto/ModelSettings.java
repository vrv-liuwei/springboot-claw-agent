package com.github.clawagent.server.dto;

public record ModelSettings(
        String mode,
        String client,
        String defaultModel,
        String memoryModel,
        String visionModel,
        String planner
) {
}
