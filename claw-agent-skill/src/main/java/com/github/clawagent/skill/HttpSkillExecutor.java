package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpRequest;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.net.URI;
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
            AgentHttpRequest request = AgentHttpRequest.get(url)
                    .method(method)
                    .timeoutMs(timeoutSeconds * 1000)
                    .headers(renderHeaders(config, call))
                    .ignoreSsl(booleanValue(config, "ignoreSsl", false));
            if (!"GET".equals(method)) {
                // 非 GET 请求统一带 body，调用方可通过 metadata.executor.body 配置 JSON 模板。
                request.body(body);
            }
            AgentHttpResponse response = AgentHttpClient.execute(request);
            String result = "status=" + response.statusCode() + "\n" + response.body();
            return response.is2xx()
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

    private Map<String, String> renderHeaders(Map<String, Object> config, ToolCall call) {
        Map<String, String> headers = new LinkedHashMap<>();
        readHeaders(config).forEach((key, value) -> headers.put(key, render(value, call)));
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

    private boolean booleanValue(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
