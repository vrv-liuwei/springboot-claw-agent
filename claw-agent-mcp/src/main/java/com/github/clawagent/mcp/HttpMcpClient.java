package com.github.clawagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP/streamableHttp MCP Client。
 * 通过单一 HTTP 端点发送 JSON-RPC 请求，兼容 application/json 和 text/event-stream 响应。
 */
public class HttpMcpClient implements McpClient {
    private static final Logger log = LoggerFactory.getLogger(HttpMcpClient.class);

    /** JSON 编解码器，用于构造和解析 JSON-RPC 报文。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** JSON-RPC 请求 id 自增序列。 */
    private final AtomicLong ids = new AtomicLong(1);
    /** 当前远程 MCP Server 配置。 */
    private final McpServerConfig config;
    /** JDK 原生 HTTP Client，避免 mcp 模块引入 Spring Web 依赖。 */
    private final HttpClient httpClient;
    /** streamableHttp 服务端返回的会话 id，后续请求需要原样带回。 */
    private String sessionId;

    public HttpMcpClient(McpServerConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    @Override
    public void initialize() {
        requireEndpoint();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of("roots", Map.of("listChanged", false), "sampling", Map.of()));
        params.put("clientInfo", Map.of("name", "clawagent", "version", "0.1.0-SNAPSHOT"));
        request("initialize", params);
        notify("notifications/initialized", Map.of());
        log.info("mcp http initialized serverId={} endpoint={}", config.id(), config.endpoint());
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        JsonNode result = request("tools/list", Map.of());
        List<McpToolDescriptor> tools = new ArrayList<>();
        JsonNode toolNodes = result.path("tools");
        if (toolNodes.isArray()) {
            for (JsonNode toolNode : toolNodes) {
                Map<String, Object> schema = objectMapper.convertValue(
                        toolNode.path("inputSchema"),
                        new TypeReference<>() {});
                tools.add(new McpToolDescriptor(
                        config.id(),
                        toolNode.path("name").asText(),
                        toolNode.path("description").asText(""),
                        schema));
            }
        }
        return tools;
    }

    @Override
    public List<McpResourceDescriptor> listResources() {
        JsonNode result = request("resources/list", Map.of());
        List<McpResourceDescriptor> resources = new ArrayList<>();
        JsonNode resourceNodes = result.path("resources");
        if (resourceNodes.isArray()) {
            for (JsonNode resourceNode : resourceNodes) {
                resources.add(new McpResourceDescriptor(
                        config.id(),
                        resourceNode.path("uri").asText(),
                        resourceNode.path("name").asText(""),
                        resourceNode.path("description").asText(""),
                        resourceNode.path("mimeType").asText("")));
            }
        }
        return resources;
    }

    @Override
    public List<McpPromptDescriptor> listPrompts() {
        JsonNode result = request("prompts/list", Map.of());
        List<McpPromptDescriptor> prompts = new ArrayList<>();
        JsonNode promptNodes = result.path("prompts");
        if (promptNodes.isArray()) {
            for (JsonNode promptNode : promptNodes) {
                Map<String, Object> arguments = objectMapper.convertValue(
                        promptNode.path("arguments"),
                        new TypeReference<>() {});
                prompts.add(new McpPromptDescriptor(
                        config.id(),
                        promptNode.path("name").asText(),
                        promptNode.path("description").asText(""),
                        arguments));
            }
        }
        return prompts;
    }

    @Override
    public McpResourceContent readResource(String uri) {
        JsonNode result = request("resources/read", Map.of("uri", uri));
        Map<String, Object> content = objectMapper.convertValue(result, new TypeReference<>() {});
        return new McpResourceContent(config.id(), uri, content);
    }

    @Override
    public McpPromptContent getPrompt(String name, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", new LinkedHashMap<>(arguments));
        JsonNode result = request("prompts/get", params);
        Map<String, Object> content = objectMapper.convertValue(result, new TypeReference<>() {});
        return new McpPromptContent(config.id(), name, content);
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", new LinkedHashMap<>(arguments));
        JsonNode result = request("tools/call", params);
        return extractToolContent(result);
    }

    @Override
    public void close() {
        // JDK HttpClient 没有显式关闭动作；远程 MCP 会话后续再接入 DELETE session。
    }

    private JsonNode request(String method, Map<String, Object> params) {
        long id = ids.getAndIncrement();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        JsonNode response = exchange(payload, true);
        if (response.has("error")) {
            throw new IllegalStateException("MCP HTTP 请求失败 method=" + method + " serverId=" + config.id()
                    + " error=" + response.path("error"));
        }
        return response.path("result");
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        exchange(payload, false);
    }

    private JsonNode exchange(Map<String, Object> payload, boolean expectResponse) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.endpoint()))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            config.headers().forEach(builder::header);
            if (sessionId != null && !sessionId.isBlank()) {
                // streamableHttp 会通过 MCP-Session-Id 绑定远程会话。
                builder.header("MCP-Session-Id", sessionId);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id")
                    .or(() -> response.headers().firstValue("MCP-Session-Id"))
                    .ifPresent(value -> sessionId = value);
            if (!expectResponse && (response.statusCode() == 200 || response.statusCode() == 202 || response.statusCode() == 204)) {
                return objectMapper.createObjectNode();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " body=" + response.body());
            }
            String responseBody = normalizeResponseBody(response);
            if (responseBody.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("MCP HTTP 请求失败 serverId=" + config.id() + "：" + message, e);
        }
    }

    private String normalizeResponseBody(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (!contentType.toLowerCase().contains("text/event-stream")) {
            return response.body();
        }
        List<String> dataLines = new ArrayList<>();
        for (String line : response.body().split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                dataLines.add(trimmed.substring("data:".length()).trim());
            }
        }
        // 简化处理：取第一条 JSON data。多数 streamableHttp 响应会用单条 SSE data 包住 JSON-RPC 响应。
        return dataLines.stream()
                .filter(line -> line.startsWith("{") || line.startsWith("["))
                .findFirst()
                .orElse("");
    }

    private String extractToolContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return result.toString();
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText())) {
                parts.add(item.path("text").asText());
            } else {
                parts.add(item.toString());
            }
        }
        return String.join("\n", parts);
    }

    private void requireEndpoint() {
        if (config.endpoint() == null || config.endpoint().isBlank()) {
            throw new IllegalArgumentException("HTTP MCP 需要配置 url/endpoint");
        }
        String scheme = URI.create(config.endpoint()).getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("HTTP MCP url 只支持 http/https：" + config.endpoint());
        }
    }
}
