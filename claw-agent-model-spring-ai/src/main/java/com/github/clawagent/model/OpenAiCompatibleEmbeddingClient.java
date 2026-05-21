package com.github.clawagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.EmbeddingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Embeddings 客户端。
 * 只记录请求摘要和 usage，不记录 Authorization header，避免 API Key 进入日志。
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatibleEmbeddingClient(String baseUrl, String apiKey) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
    }

    @Override
    public EmbeddingResult embed(String text, EmbeddingOptions options) {
        return embedAll(List.of(text), options).get(0);
    }

    @Override
    public List<EmbeddingResult> embedAll(List<String> texts, EmbeddingOptions options) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Embedding API Key 未配置，请设置 clawagent.memory.vector.embedding.api-key 或 api-key-env。");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("input", texts);
            if (options.dimensions() > 0) {
                payload.put("dimensions", options.dimensions());
            }
            String requestJson = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .timeout(Duration.ofSeconds(options.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            log.info("embedding request model={} baseUrl={} inputCount={}", options.model(), baseUrl, texts.size());
            log.debug("embedding payload model={} payload={}", options.model(), requestJson);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("embedding response model={} statusCode={}", options.model(), response.statusCode());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.debug("embedding error body model={} response={}", options.model(), response.body());
                throw new IllegalStateException("Embedding 调用失败，HTTP " + response.statusCode() + "：" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            List<EmbeddingResult> results = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                List<Double> vector = new ArrayList<>();
                for (JsonNode value : item.path("embedding")) {
                    vector.add(value.asDouble());
                }
                results.add(new EmbeddingResult(options.model(), vector, promptTokens, totalTokens));
            }
            log.info("embedding usage model={} promptTokens={} totalTokens={} resultCount={}",
                    options.model(), promptTokens, totalTokens, results.size());
            log.debug("embedding response body model={} response={}", options.model(), response.body());
            return results;
        } catch (IOException e) {
            throw new IllegalStateException("Embedding 请求序列化或响应解析失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 调用被中断", e);
        }
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
