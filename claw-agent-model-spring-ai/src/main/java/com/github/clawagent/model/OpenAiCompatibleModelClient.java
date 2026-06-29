package com.github.clawagent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;
import com.github.clawagent.spi.ChatMessage;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ChatStreamCallback;
import com.github.clawagent.spi.LlmCallTrace;
import com.github.clawagent.spi.LlmTraceContext;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.ModelImageInput;
import com.github.clawagent.spi.MultimodalModelClient;
import com.github.clawagent.spi.StreamingModelClient;
import com.github.clawagent.spi.ToolCallingModelClient;
import com.github.clawagent.spi.ToolCallingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * OpenAI 兼容 Chat Completions 客户端。
 * DeepSeek、OpenAI 兼容网关、私有化模型网关都可以通过 baseUrl + apiKey 接入。
 */
public class OpenAiCompatibleModelClient implements ModelClient, ToolCallingModelClient, StreamingModelClient, MultimodalModelClient {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleModelClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 流式响应需要逐行解析 SSE，暂保留 JDK HttpClient 的 InputStream/String BodyHandler 能力。 */
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatibleModelClient(String baseUrl, String apiKey) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
    }

    @Override
    public String chatStream(List<ChatMessage> messages, ChatOptions options, ChatStreamCallback callback) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置，请设置 clawagent.models.<id>.api-key 或 api-key-env 指向的环境变量。");
        }
        try {
            long startNanos = System.nanoTime();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("temperature", options.temperature());
            payload.put("stream", true);
            // 支持 OpenAI 兼容流式 usage 的服务会在最后一个 chunk 返回 token 统计。
            payload.put("stream_options", Map.of("include_usage", true));
            payload.put("messages", messages.stream().map(message -> Map.of(
                    "role", message.role(),
                    "content", message.content()
            )).toList());

            String requestJson = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(options.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            log.info("model chat stream request model={} baseUrl={} messageCount={}", options.model(), baseUrl, messages.size());
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response = retryStreamWithoutUsageIfUnsupported(payload, options, response);
                elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                requestJson = objectMapper.writeValueAsString(payload);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    addTrace(options, response.statusCode(), elapsedMs, requestJson, response.body(), "");
                    throw new IllegalStateException("模型流式调用失败，HTTP " + response.statusCode() + "：" + response.body());
                }
            }
            StreamParseResult parsed = parseStreamResponse(response.body(), callback);
            String content = parsed.content();
            callback.onComplete(content);
            addTrace(options, response.statusCode(), elapsedMs, requestJson, response.body(), content, parsed.promptTokens(), parsed.completionTokens(), parsed.totalTokens());
            log.info("model chat stream finished model={} statusCode={} elapsedMs={} answerLength={}",
                    options.model(), response.statusCode(), elapsedMs, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("模型流式响应解析失败：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型流式调用被中断", e);
        }
    }

    @Override
    public ToolCallingResult chatWithTools(List<ChatMessage> messages, ChatOptions options, List<ToolDefinition> tools) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置，请设置 clawagent.models.<id>.api-key 或 api-key-env 指向的环境变量。");
        }
        try {
            long startNanos = System.nanoTime();
            Map<String, String> functionNameToToolId = new LinkedHashMap<>();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("temperature", options.temperature());
            payload.put("messages", messages.stream().map(message -> Map.of(
                    "role", message.role(),
                    "content", message.content()
            )).toList());
            payload.put("tools", tools.stream()
                    .map(tool -> toOpenAiTool(tool, functionNameToToolId))
                    .toList());
            payload.put("tool_choice", "auto");

            String requestJson = objectMapper.writeValueAsString(payload);

            log.info("model tool calling request model={} baseUrl={} messageCount={} toolCount={}",
                    options.model(), baseUrl, messages.size(), tools.size());
            log.debug("model tool calling payload model={} payload={}", options.model(), requestJson);
            // 非流式 Tool Calling 是标准 JSON POST，统一走 AgentHttpClient，Authorization 只进 header，不进入日志。
            AgentHttpResponse response = AgentHttpClient.postJson(
                    baseUrl + "/chat/completions",
                    requestJson,
                    authHeaders(),
                    options.timeoutSeconds() * 1000);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            log.info("model tool calling response model={} statusCode={} elapsedMs={}", options.model(), response.statusCode(), elapsedMs);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                addTrace(options, response.statusCode(), elapsedMs, requestJson, response.body(), "");
                throw new IllegalStateException("模型 Tool Calling 调用失败，HTTP " + response.statusCode() + "：" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            List<ToolCall> calls = parseToolCalls(message.path("tool_calls"), functionNameToToolId);
            addTrace(options, response.statusCode(), elapsedMs, requestJson, response.body(), content);
            log.info("model tool calling parsed model={} toolCallCount={}", options.model(), calls.size());
            log.debug("model tool calling response body model={} response={}", options.model(), response.body());
            return new ToolCallingResult(content, calls);
        } catch (IOException e) {
            throw new IllegalStateException("模型 Tool Calling 请求序列化或响应解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public String chat(List<ChatMessage> messages, ChatOptions options) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置，请设置 clawagent.models.<id>.api-key 或 api-key-env 指向的环境变量。");
        }
        try {
            long startNanos = System.nanoTime();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("temperature", options.temperature());
            payload.put("messages", messages.stream().map(message -> Map.of(
                    "role", message.role(),
                    "content", message.content()
            )).toList());

            String requestJson = objectMapper.writeValueAsString(payload);

            log.info("model chat request model={} baseUrl={} messageCount={}", options.model(), baseUrl, messages.size());
            log.debug("model chat payload model={} payload={}", options.model(), requestJson);
            // 非流式 Chat 是标准 JSON POST，统一走 AgentHttpClient，减少各模块重复 HTTP 实现。
            AgentHttpResponse response = AgentHttpClient.postJson(
                    baseUrl + "/chat/completions",
                    requestJson,
                    authHeaders(),
                    options.timeoutSeconds() * 1000);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            log.info("model chat response model={} statusCode={} elapsedMs={}", options.model(), response.statusCode(), elapsedMs);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LlmTraceContext.add(new LlmCallTrace(
                        options.model(),
                        baseUrl,
                        response.statusCode(),
                        elapsedMs,
                        requestJson,
                        response.body(),
                        "",
                        0,
                        0,
                        0));
                log.debug("model chat error body model={} response={}", options.model(), response.body());
                throw new IllegalStateException("模型调用失败，HTTP " + response.statusCode() + "：" + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new IllegalStateException("模型返回为空：" + response.body());
            }
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);
            LlmTraceContext.add(new LlmCallTrace(
                    options.model(),
                    baseUrl,
                    response.statusCode(),
                    elapsedMs,
                    requestJson,
                    response.body(),
                    content.asText(),
                    promptTokens,
                    completionTokens,
                    totalTokens));
            log.info("model chat usage model={} promptTokens={} completionTokens={} totalTokens={}",
                    options.model(), promptTokens, completionTokens, totalTokens);
            log.debug("model chat response body model={} response={}", options.model(), response.body());
            log.debug("model chat content model={} content={}", options.model(), preview(content.asText()));
            return content.asText();
        } catch (IOException e) {
            throw new IllegalStateException("模型请求序列化或响应解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 使用 OpenAI 兼容多模态格式做单图理解。
     * 该方法只服务 vision-model 前置识别，不改变主对话模型的纯文本接口。
     */
    public String chatWithImage(String prompt, Path imagePath, String mimeType, ChatOptions options) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置，请设置 clawagent.models.<id>.api-key 或 api-key-env 指向的环境变量。");
        }
        if (imagePath == null) {
            throw new IllegalArgumentException("图片路径不能为空");
        }
        try {
            long startNanos = System.nanoTime();
            String contentType = mimeType == null || mimeType.isBlank() ? "image/png" : mimeType.trim();
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            String dataUrl = "data:" + contentType + ";base64," + base64;

            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt == null || prompt.isBlank()
                    ? "请描述这张图片的主要内容。"
                    : prompt));
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("temperature", options.temperature());
            payload.put("messages", List.of(Map.of("role", "user", "content", content)));

            String requestJson = objectMapper.writeValueAsString(payload);
            String traceRequestJson = requestJson.replace(dataUrl, "data:" + contentType + ";base64,[omitted]");
            log.info("model vision request model={} baseUrl={} imagePath={} mimeType={}",
                    options.model(), baseUrl, imagePath, contentType);
            AgentHttpResponse response = AgentHttpClient.postJson(
                    baseUrl + "/chat/completions",
                    requestJson,
                    authHeaders(),
                    options.timeoutSeconds() * 1000);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                addTrace(options, response.statusCode(), elapsedMs, traceRequestJson, response.body(), "");
                throw new IllegalStateException("图片理解模型调用失败，HTTP " + response.statusCode() + "：" + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) {
                throw new IllegalStateException("图片理解模型返回为空：" + response.body());
            }
            addTrace(options, response.statusCode(), elapsedMs, traceRequestJson, response.body(), answer);
            log.info("model vision response model={} statusCode={} elapsedMs={} answerLength={}",
                    options.model(), response.statusCode(), elapsedMs, answer.length());
            return answer;
        } catch (IOException e) {
            throw new IllegalStateException("图片理解请求序列化或文件读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * OpenAI-compatible 多模态请求。
     * 图片统一编码为 base64 data URL，兼容 OpenAI Chat Completions、DashScope compatible、Kimi、GLM 等接口。
     */
    @Override
    public String chatWithImages(List<ChatMessage> messages, List<ModelImageInput> images, ChatOptions options) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("模型 API Key 未配置，请设置 clawagent.models.<id>.api-key 或 api-key-env 指向的环境变量。");
        }
        if (images == null || images.isEmpty()) {
            return chat(messages, options);
        }
        try {
            long startNanos = System.nanoTime();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", options.model());
            payload.put("temperature", options.temperature());
            payload.put("messages", toOpenAiMultimodalMessages(messages, images));

            String requestJson = objectMapper.writeValueAsString(payload);
            String traceRequestJson = traceSafeRequestJson(requestJson);
            log.info("model multimodal chat request model={} baseUrl={} imageCount={}",
                    options.model(), baseUrl, images.size());
            AgentHttpResponse response = AgentHttpClient.postJson(
                    baseUrl + "/chat/completions",
                    requestJson,
                    authHeaders(),
                    options.timeoutSeconds() * 1000);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                addTrace(options, response.statusCode(), elapsedMs, traceRequestJson, response.body(), "");
                throw new IllegalStateException("多模态模型调用失败，HTTP " + response.statusCode() + "：" + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.path("choices").path(0).path("message").path("content").asText("");
            if (answer.isBlank()) {
                throw new IllegalStateException("多模态模型返回为空：" + response.body());
            }
            addTrace(options, response.statusCode(), elapsedMs, traceRequestJson, response.body(), answer);
            log.info("model multimodal chat response model={} statusCode={} elapsedMs={} answerLength={}",
                    options.model(), response.statusCode(), elapsedMs, answer.length());
            return answer;
        } catch (IOException e) {
            throw new IllegalStateException("多模态模型请求序列化或图片读取失败：" + e.getMessage(), e);
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

    private List<Map<String, Object>> toOpenAiMultimodalMessages(List<ChatMessage> messages, List<ModelImageInput> images) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            result.add(Map.of("role", "user", "content", multimodalContent("", images)));
            return result;
        }
        int lastUserIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).role())) {
                lastUserIndex = i;
            }
        }
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (i == lastUserIndex) {
                result.add(Map.of("role", message.role(), "content", multimodalContent(message.content(), images)));
            } else {
                result.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        return result;
    }

    private List<Map<String, Object>> multimodalContent(String text, List<ModelImageInput> images) throws IOException {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", text == null || text.isBlank() ? "请根据图片回答。" : text));
        for (ModelImageInput image : images) {
            String path = image == null ? "" : image.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            Path imagePath = Path.of(path).toAbsolutePath().normalize();
            String mimeType = image.mimeType() == null || image.mimeType().isBlank() ? "image/png" : image.mimeType().trim();
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            String dataUrl = "data:" + mimeType + ";base64," + base64;
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        }
        return content;
    }

    private String traceSafeRequestJson(String requestJson) {
        if (requestJson == null || requestJson.isBlank()) {
            return "";
        }
        return requestJson.replaceAll("data:([^;\\\"]+);base64,[^\\\"]+", "data:$1;base64,[omitted]");
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new HashMap<>();
        // 只把 API Key 放进请求头，不参与 requestJson 记录，避免调试日志泄露密钥。
        headers.put("Authorization", "Bearer " + apiKey);
        return headers;
    }

    private Map<String, Object> toOpenAiTool(ToolDefinition definition, Map<String, String> functionNameToToolId) {
        String functionName = functionName(definition.id(), functionNameToToolId.size());
        functionNameToToolId.put(functionName, definition.id());
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", functionName,
                        "description", definition.description(),
                        "parameters", definition.inputSchema()
                )
        );
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode, Map<String, String> functionNameToToolId) {
        List<ToolCall> calls = new ArrayList<>();
        if (!toolCallsNode.isArray()) {
            return calls;
        }
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode functionNode = toolCallNode.path("function");
            String functionName = functionNode.path("name").asText();
            String toolId = functionNameToToolId.get(functionName);
            if (toolId == null) {
                continue;
            }
            calls.add(new ToolCall(toolId, readStringMap(functionNode.path("arguments").asText("{}"))));
        }
        return calls;
    }

    private Map<String, String> readStringMap(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode node = objectMapper.readTree(json == null || json.isBlank() ? "{}" : json);
            if (!node.isObject()) {
                return result;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.put(field.getKey(), field.getValue().isTextual()
                        ? field.getValue().asText()
                        : field.getValue().toString());
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("模型 tool_calls arguments 不是有效 JSON：" + json, e);
        }
    }

    private String functionName(String toolId, int index) {
        String normalized = toolId == null ? "tool" : toolId.replaceAll("[^a-zA-Z0-9_-]+", "_");
        if (normalized.isBlank() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "tool_" + index + "_" + normalized;
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 48) + "_" + index;
    }

    private void addTrace(ChatOptions options, int statusCode, long elapsedMs, String requestJson, String responseJson, String content) {
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            totalTokens = root.path("usage").path("total_tokens").asInt(0);
        } catch (Exception ignored) {
            // 错误响应可能不是标准 JSON usage，trace 仍保留请求和响应原文。
        }
        addTrace(options, statusCode, elapsedMs, requestJson, responseJson, content, promptTokens, completionTokens, totalTokens);
    }

    private void addTrace(ChatOptions options,
                          int statusCode,
                          long elapsedMs,
                          String requestJson,
                          String responseJson,
                          String content,
                          int promptTokens,
                          int completionTokens,
                          int totalTokens) {
        LlmTraceContext.add(new LlmCallTrace(
                options.model(),
                baseUrl,
                statusCode,
                elapsedMs,
                requestJson,
                responseJson,
                content,
                promptTokens,
                completionTokens,
                totalTokens));
    }

    private String preview(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "...";
    }

    private HttpResponse<String> retryStreamWithoutUsageIfUnsupported(Map<String, Object> payload,
                                                                      ChatOptions options,
                                                                      HttpResponse<String> firstResponse) throws IOException, InterruptedException {
        String body = firstResponse.body() == null ? "" : firstResponse.body();
        if (!body.toLowerCase().contains("stream_options")) {
            return firstResponse;
        }
        payload.remove("stream_options");
        String requestJson = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(options.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();
        log.warn("model chat stream retry without stream_options model={} baseUrl={}", options.model(), baseUrl);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private StreamParseResult parseStreamResponse(String body, ChatStreamCallback callback) throws IOException {
        StringBuilder answer = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("data:")) {
                    continue;
                }
                String data = trimmed.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    break;
                }
                if (data.isBlank()) {
                    continue;
                }
                JsonNode root = objectMapper.readTree(data);
                JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                    // 部分 OpenAI 兼容模型会在流式片段中返回 content:null，不能把它当成正文输出。
                    String chunk = delta.asText();
                    if ("null".equalsIgnoreCase(chunk.trim())) {
                        continue;
                    }
                    answer.append(chunk);
                    callback.onDelta(chunk);
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode() && !usage.isNull()) {
                    promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                    completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    totalTokens = usage.path("total_tokens").asInt(totalTokens);
                }
            }
        }
        return new StreamParseResult(answer.toString(), promptTokens, completionTokens, totalTokens);
    }

    /**
     * 流式响应解析结果。
     *
     * @param content 完整回复正文。
     * @param promptTokens prompt token 数。
     * @param completionTokens completion token 数。
     * @param totalTokens 总 token 数。
     */
    private record StreamParseResult(String content, int promptTokens, int completionTokens, int totalTokens) {
    }
}
