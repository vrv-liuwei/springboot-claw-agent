package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 型 Skill 执行器。
 * 用于把 Skill tool 映射到内部 HTTP 服务、网关或轻量 REST 能力。
 */
class HttpSkillExecutor implements SkillExecutor {
    @Override
    public ToolResult execute(SkillExecutionContext context, ToolCall call, AgentContext agentContext) {
        requirePermission(context, "network");
        Map<String, Object> config = context.executorConfig();
        String url = render(required(config, "url"), call);
        String method = stringValue(config, "method", "GET").trim().toUpperCase();
        int timeoutSeconds = intValue(config, "timeoutSeconds", 30);
        String body = render(stringValue(config, "body", ""), call);

        try {
            URI uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("HTTP Skill 只支持 http/https url：" + url);
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            readHeaders(config).forEach((key, value) -> builder.header(key, render(value, call)));
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                // 非 GET 请求统一带 body，调用方可通过 metadata.executor.body 配置 JSON 模板。
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String result = "status=" + response.statusCode() + "\n" + response.body();
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? ToolResult.success(result)
                    : ToolResult.error(result);
        } catch (Exception e) {
            return ToolResult.error("HTTP Skill 执行失败：" + e.getMessage());
        }
    }

    private Map<String, String> readHeaders(Map<String, Object> config) {
        Map<String, String> headers = new LinkedHashMap<>();
        Object value = config.get("headers");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> headers.put(String.valueOf(key), String.valueOf(item)));
        }
        return headers;
    }

    private void requirePermission(SkillExecutionContext context, String permission) {
        boolean allowed = context.manifest().permissions().stream()
                .map(item -> item == null ? "" : item.toLowerCase())
                .anyMatch(item -> item.contains(permission) || item.contains("http"));
        if (!allowed) {
            throw new IllegalStateException("Skill 未声明 network/http 权限，不能执行 HTTP executor");
        }
    }

    private String render(String template, ToolCall call) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : call.arguments().entrySet()) {
            // 支持 ${arg.xxx} 和 ${xxx} 两种占位，方便手写 manifest。
            result = result.replace("${arg." + entry.getKey() + "}", entry.getValue());
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String required(Map<String, Object> config, String key) {
        String value = stringValue(config, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("HTTP Skill 缺少 executor." + key);
        }
        return value;
    }

    private String stringValue(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int intValue(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
