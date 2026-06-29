package com.github.clawagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE MCP Client。
 * 兼容老版 MCP SSE：GET 建立事件流，服务端返回 endpoint 后，JSON-RPC 请求通过 POST endpoint 发送。
 */
public class SseMcpClient implements McpClient {
    private static final Logger log = LoggerFactory.getLogger(SseMcpClient.class);

    /** JSON 编解码器，用于 JSON-RPC 请求和 SSE data 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** JSON-RPC 请求 id 自增序列。 */
    private final AtomicLong ids = new AtomicLong(1);
    /** 等待响应的 JSON-RPC 请求，key 为请求 id。 */
    private final Map<String, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    /** 当前 SSE MCP Server 配置。 */
    private final McpServerConfig config;
    /** 已解析环境变量占位符的 SSE 入口地址。 */
    private final String endpoint;
    /** 已解析环境变量占位符的请求头。 */
    private final Map<String, String> headers;
    /** JDK HTTP Client，避免引入额外 Web 依赖。 */
    private final HttpClient httpClient;
    /** SSE 服务端下发的消息 POST 地址。 */
    private final CompletableFuture<URI> messageEndpoint = new CompletableFuture<>();
    /** SSE 输入流，close 时用于中断后台 reader。 */
    private volatile InputStream eventStream;
    /** 后台 reader 运行标记。 */
    private volatile boolean running;
    /** 后台 reader 线程。 */
    private Thread readerThread;

    public SseMcpClient(McpServerConfig config) {
        this.config = config;
        this.endpoint = McpValueResolver.resolve(config.endpoint(), config.env());
        this.headers = McpValueResolver.resolveMap(config.headers(), config.env());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                .build();
    }

    @Override
    public void initialize() {
        requireEndpoint();
        openEventStream();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of("roots", Map.of("listChanged", false), "sampling", Map.of()));
        params.put("clientInfo", Map.of("name", "clawagent", "version", "0.1.0-SNAPSHOT"));
        request("initialize", params);
        notify("notifications/initialized", Map.of());
        log.info("mcp sse initialized serverId={} endpoint={}", config.id(), config.endpoint());
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        JsonNode result = request("tools/list", Map.of());
        List<McpToolDescriptor> tools = new ArrayList<>();
        JsonNode toolNodes = result.path("tools");
        if (toolNodes.isArray()) {
            for (JsonNode toolNode : toolNodes) {
                Map<String, Object> schema = objectMapper.convertValue(toolNode.path("inputSchema"), new TypeReference<>() {});
                tools.add(new McpToolDescriptor(config.id(), toolNode.path("name").asText(), toolNode.path("description").asText(""), schema));
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
                Map<String, Object> arguments = objectMapper.convertValue(promptNode.path("arguments"), new TypeReference<>() {});
                prompts.add(new McpPromptDescriptor(config.id(), promptNode.path("name").asText(), promptNode.path("description").asText(""), arguments));
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
        running = false;
        try {
            if (eventStream != null) {
                eventStream.close();
            }
        } catch (Exception ignored) {
            // 关闭 SSE 流失败不影响主流程，reader 线程会自然退出或超时结束。
        }
        pendingResponses.values().forEach(future -> future.completeExceptionally(new IllegalStateException("SSE MCP client closed")));
        pendingResponses.clear();
    }

    private JsonNode request(String method, Map<String, Object> params) {
        long id = ids.getAndIncrement();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("id", id);
        payload.put("method", method);
        payload.put("params", params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingResponses.put(String.valueOf(id), future);
        post(payload);
        try {
            JsonNode response = future.get(config.timeoutSeconds(), TimeUnit.SECONDS);
            if (response.has("error")) {
                throw new IllegalStateException("MCP SSE 请求失败 method=" + method + " serverId=" + config.id()
                        + " error=" + response.path("error"));
            }
            return response.path("result");
        } catch (Exception e) {
            pendingResponses.remove(String.valueOf(id));
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("MCP SSE 请求失败 serverId=" + config.id() + "：" + message, e);
        }
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        post(payload);
    }

    private void openEventStream() {
        running = true;
        readerThread = new Thread(this::readEventStream, "mcp-sse-reader-" + config.id());
        readerThread.setDaemon(true);
        readerThread.start();
        try {
            // 必须等到服务端通过 endpoint 事件告知 POST 地址，否则无法发送 initialize。
            messageEndpoint.get(config.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            close();
            throw new IllegalStateException("MCP SSE 未收到 endpoint 事件 serverId=" + config.id(), e);
        }
    }

    private void readEventStream() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Accept", "text/event-stream")
                    .GET();
            headers.forEach(builder::header);
            HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            eventStream = response.body();
            parseSse(eventStream);
        } catch (Exception e) {
            if (running) {
                log.warn("mcp sse reader stopped serverId={} message={}", config.id(), e.getMessage());
                pendingResponses.values().forEach(future -> future.completeExceptionally(e));
                pendingResponses.clear();
            }
        }
    }

    private void parseSse(InputStream stream) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    handleSseEvent(event, data.toString().trim());
                    event = "message";
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim()).append('\n');
                }
            }
        }
    }

    private void handleSseEvent(String event, String data) throws Exception {
        if (data.isBlank()) {
            return;
        }
        if ("endpoint".equals(event)) {
            // endpoint 可能是绝对地址，也可能是相对路径；统一解析成完整 URI。
            messageEndpoint.complete(URI.create(endpoint).resolve(data));
            return;
        }
        JsonNode json = objectMapper.readTree(data);
        JsonNode idNode = json.get("id");
        if (idNode == null) {
            log.debug("mcp sse notification serverId={} event={} data={}", config.id(), event, data);
            return;
        }
        CompletableFuture<JsonNode> future = pendingResponses.remove(idNode.asText());
        if (future != null) {
            future.complete(json);
        }
    }

    private void post(Map<String, Object> payload) {
        try {
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(messageEndpoint.getNow(URI.create(endpoint)))
                    .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " body=" + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MCP SSE POST 失败 serverId=" + config.id() + "：" + e.getMessage(), e);
        }
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
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("SSE MCP 需要配置 url/endpoint");
        }
        String scheme = URI.create(endpoint).getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("SSE MCP url 只支持 http/https：" + endpoint);
        }
    }
}
