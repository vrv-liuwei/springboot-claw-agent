package com.github.clawagent.server.dto;

public record ModelConfigView(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        boolean apiKeyConfigured,
        double temperature,
        int timeoutSeconds,
        boolean vision
) {
}
