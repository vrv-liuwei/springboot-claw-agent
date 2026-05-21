package com.github.clawagent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * STDIO MCP Client。
 * 通过命令行启动 MCP Server 子进程，并使用 JSON-RPC 2.0 newline framing 通信。
 */
public class StdioMcpClient implements McpClient {
    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    /** JSON 编解码器，用于 MCP JSON-RPC 请求和响应。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** JSON-RPC 请求 id 自增序列。 */
    private final AtomicLong ids = new AtomicLong(1);
    /** 等待响应的请求表，key 为 JSON-RPC id。 */
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** 当前 STDIO MCP Server 的启动和连接配置。 */
    private final McpServerConfig config;
    /** MCP Server 子进程。 */
    private Process process;
    /** 写入子进程 stdin 的 JSONL 输出流。 */
    private BufferedWriter writer;
    /** 读取子进程 stdout 的后台线程。 */
    private Thread readerThread;
    /** 读取子进程 stderr 的后台线程；MCP stdio 协议要求 stdout 只传 JSON-RPC，stderr 单独记录日志。 */
    private Thread errorThread;

    public StdioMcpClient(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void initialize() {
        if (process != null && process.isAlive()) {
            return;
        }
        startProcess();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2024-11-05");
        params.put("capabilities", Map.of("roots", Map.of("listChanged", false), "sampling", Map.of()));
        params.put("clientInfo", Map.of("name", "clawagent", "version", "0.1.0-SNAPSHOT"));
        request("initialize", params);
        notify("notifications/initialized", Map.of());
        log.info("mcp stdio initialized serverId={} command={}", config.id(), config.command());
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
    public synchronized void close() {
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("MCP client closed")));
        pending.clear();
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void startProcess() {
        if (config.command() == null || config.command().isBlank()) {
            throw new IllegalArgumentException("STDIO MCP 需要配置 command");
        }
        try {
            List<String> command = new ArrayList<>();
            command.add(config.command());
            command.addAll(config.args());
            ProcessBuilder builder = new ProcessBuilder(command);
            // 不能把 stderr 合并进 stdout。MCP stdio 的 stdout 是协议通道，stderr 噪音会污染 JSON-RPC。
            builder.redirectErrorStream(false);
            if (config.cwd() != null && !config.cwd().isBlank()) {
                builder.directory(new java.io.File(config.cwd()));
            }
            builder.environment().putAll(resolveEnv(config.env()));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + config.id());
            readerThread.setDaemon(true);
            readerThread.start();
            errorThread = new Thread(this::errorLoop, "mcp-stdio-stderr-" + config.id());
            errorThread.setDaemon(true);
            errorThread.start();
            log.info("mcp stdio process started serverId={} command={}", config.id(), command);
        } catch (Exception e) {
            throw new IllegalStateException("启动 STDIO MCP 失败：" + e.getMessage(), e);
        }
    }

    private JsonNode request(String method, Map<String, Object> params) {
        long id = ids.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        try {
            writeJson(request);
        } catch (RuntimeException e) {
            pending.remove(id);
            throw e;
        }
        try {
            return future.get(Duration.ofSeconds(config.timeoutSeconds()).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            String processState = process == null
                    ? "process=null"
                    : process.isAlive() ? "process=alive" : "process=exited exitCode=" + process.exitValue();
            throw new IllegalStateException("MCP 请求超时 method=" + method
                    + " serverId=" + config.id()
                    + " timeoutSeconds=" + config.timeoutSeconds()
                    + " " + processState, e);
        } catch (Exception e) {
            pending.remove(id);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IllegalStateException("MCP 请求失败 method=" + method
                    + " serverId=" + config.id()
                    + "：" + message, e);
        }
    }

    private void notify(String method, Map<String, Object> params) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.put("params", params);
        writeJson(notification);
    }

    private synchronized void writeJson(Map<String, Object> payload) {
        try {
            if (process == null || !process.isAlive()) {
                // 子进程已经退出时不要继续写 stdin，直接把失败返回给调用方。
                throw new IllegalStateException("MCP STDIO 进程已退出 serverId=" + config.id());
            }
            writer.write(objectMapper.writeValueAsString(payload));
            writer.newLine();
            writer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("写入 MCP STDIO 失败：" + e.getMessage(), e);
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
            log.warn("mcp stdio stdout closed serverId={} processAlive={}", config.id(), process != null && process.isAlive());
        } catch (Exception e) {
            log.warn("mcp stdio reader stopped serverId={} error={}", config.id(), e.getMessage());
        } finally {
            failPending("MCP STDIO stdout closed");
        }
    }

    private void errorLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // stderr 是 MCP Server 自身日志或底层运行时错误，保留原文方便定位 uvx/node/python 等问题。
                log.warn("mcp stdio stderr serverId={} line={}", config.id(), line);
            }
        } catch (Exception e) {
            log.debug("mcp stdio stderr reader stopped serverId={} error={}", config.id(), e.getMessage());
        }
    }

    private void failPending(String reason) {
        IllegalStateException error = new IllegalStateException(reason + " serverId=" + config.id());
        // stdout 关闭通常意味着子进程已经退出，必须唤醒正在等待响应的 initialize/listTools 调用。
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    private void handleLine(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            if (!node.has("id")) {
                return;
            }
            long id = node.path("id").asLong();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                return;
            }
            if (node.has("error")) {
                future.completeExceptionally(new IllegalStateException(node.path("error").toString()));
            } else {
                future.complete(node.path("result"));
            }
        } catch (Exception e) {
            log.debug("ignore non-json mcp line serverId={} line={}", config.id(), line);
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

    private Map<String, String> resolveEnv(Map<String, String> env) {
        Map<String, String> resolved = new LinkedHashMap<>();
        env.forEach((key, value) -> resolved.put(key, resolveEnvValue(value)));
        return resolved;
    }

    private String resolveEnvValue(String value) {
        if (value == null) {
            return "";
        }
        if (value.startsWith("${") && value.endsWith("}") && value.length() > 3) {
            String envName = value.substring(2, value.length() - 1);
            return System.getenv().getOrDefault(envName, "");
        }
        return value;
    }
}
