package com.github.clawagent.toolkit.websearch.bocha;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;
import com.github.clawagent.spi.WebSearchProvider;
import com.github.clawagent.spi.WebSearchRequest;
import com.github.clawagent.spi.WebSearchResponse;
import com.github.clawagent.spi.WebSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博查 Web Search Provider。
 * 它负责把 ClawAgent 的搜索意图映射为博查 API 请求，并把博查响应转换为统一搜索结果。
 */
public class BochaWebSearchProvider implements WebSearchProvider {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BochaWebSearchProperties properties;

    public BochaWebSearchProvider(BochaWebSearchProperties properties) {
        this.properties = properties == null ? new BochaWebSearchProperties() : properties;
    }

    @Override
    public String id() {
        return "bocha";
    }

    @Override
    public Map<String, Object> inputProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("count", Map.of("type", "integer", "description", "博查 count，返回结果数量，默认读取 BOCHA_COUNT"));
        properties.put("freshness", Map.of("type", "string", "description", "博查 freshness，例如 noLimit、oneDay、oneWeek、oneMonth"));
        properties.put("summary", Map.of("type", "boolean", "description", "博查 summary，是否请求返回摘要"));
        properties.put("timeoutMs", Map.of("type", "integer", "description", "博查请求超时时间，毫秒，默认读取 BOCHA_TIMEOUT_MS"));
        return properties;
    }

    @Override
    public WebSearchResponse search(WebSearchRequest request) {
        if (properties.getApiKey().isBlank() || properties.getApiKey().startsWith("${")) {
            throw new IllegalStateException("缺少 BOCHA_API_KEY，请在 clawagent.toolkit.tools.web-search.env.BOCHA_API_KEY 或环境变量中配置");
        }
        try {
            long start = System.currentTimeMillis();
            String payload = objectMapper.writeValueAsString(requestBody(request));
            Map<String, String> headers = Map.of(
                    "Authorization", "Bearer " + properties.getApiKey(),
                    "Accept", "application/json");
            AgentHttpResponse response = AgentHttpClient.postJson(
                    properties.getEndpoint(),
                    payload,
                    headers,
                    intArg(request, "timeoutMs", properties.getTimeoutMs()));
            long elapsedMs = System.currentTimeMillis() - start;
            if (!response.is2xx()) {
                throw new IllegalStateException("博查搜索请求失败 status=" + response.statusCode() + " body=" + response.body());
            }
            return new WebSearchResponse(id(), request.query(), elapsedMs, parseResults(response.body()));
        } catch (Exception e) {
            throw new IllegalStateException("博查搜索失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> requestBody(WebSearchRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        // 博查当前 API 使用 query/count/freshness/summary；后续参数变化只影响本 Provider。
        body.put("query", request.query());
        body.put("count", intArg(request, "count", properties.getCount()));
        body.put("freshness", stringArg(request, "freshness", properties.getFreshness()));
        body.put("summary", booleanArg(request, "summary", properties.isSummary()));
        return body;
    }

    private int intArg(WebSearchRequest request, String key, int fallback) {
        try {
            String value = request.providerArguments().get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String stringArg(WebSearchRequest request, String key, String fallback) {
        String value = request.providerArguments().get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean booleanArg(WebSearchRequest request, String key, boolean fallback) {
        String value = request.providerArguments().get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value.trim());
    }

    private List<WebSearchResult> parseResults(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode value = root.path("data").path("webPages").path("value");
        if (!value.isArray()) {
            // 兼容部分代理或未来响应直接把结果放到 data/value/results。
            value = firstArray(root.path("data").path("value"), root.path("data").path("results"), root.path("results"));
        }
        List<WebSearchResult> results = new ArrayList<>();
        int remainingChars = properties.getMaxOutputChars();
        if (value.isArray()) {
            for (JsonNode item : value) {
                WebSearchResult result = toResult(item, remainingChars);
                results.add(result);
                remainingChars -= length(result);
                if (remainingChars <= 0) {
                    break;
                }
            }
        }
        return results;
    }

    private JsonNode firstArray(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return objectMapper.createArrayNode();
    }

    private WebSearchResult toResult(JsonNode item, int remainingChars) {
        String title = text(item, "name", "title");
        String url = text(item, "url", "link");
        String snippet = limit(text(item, "snippet", "description"), Math.max(0, remainingChars / 2));
        String summary = limit(text(item, "summary", "content"), Math.max(0, remainingChars / 2));
        String source = text(item, "siteName", "source");
        String publishedAt = text(item, "dateLastCrawled", "publishedAt", "date");
        Double score = item.has("score") && item.path("score").isNumber() ? item.path("score").asDouble() : null;
        return new WebSearchResult(title, url, snippet, summary, source, publishedAt, score);
    }

    private String text(JsonNode item, String... keys) {
        for (String key : keys) {
            JsonNode node = item.path(key);
            if (!node.isMissingNode() && !node.isNull() && !node.asText().isBlank()) {
                return node.asText();
            }
        }
        return "";
    }

    private String limit(String value, int limit) {
        if (value == null || value.length() <= limit || limit <= 0) {
            return value == null ? "" : value;
        }
        return value.substring(0, limit) + "...";
    }

    private int length(WebSearchResult result) {
        return safeLength(result.title())
                + safeLength(result.url())
                + safeLength(result.snippet())
                + safeLength(result.summary())
                + safeLength(result.source())
                + safeLength(result.publishedAt());
    }

    private int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
