package com.github.clawagent.server.dto;

public record EmbeddingConfigView(
        String provider,
        String baseUrl,
        String model,
        String apiKey,
        boolean apiKeyConfigured,
        int dimensions,
        int timeoutSeconds
) {
}
