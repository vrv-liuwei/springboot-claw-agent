package com.github.clawagent.toolkit.webfetch;

import cn.hutool.http.HtmlUtil;
import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单入口网页抓取工具。
 * 使用 format 参数选择 html/text/markdown/json，避免把同一类能力拆成多个工具入口。
 */
public class WebFetchTool implements AgentTool {
    private final WebFetchClient client;

    public WebFetchTool(WebFetchClient client) {
        this.client = client;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", ToolDefinition.stringProperty("要访问的 http/https URL"));
        properties.put("format", Map.of(
                "type", "string",
                "description", "返回格式，默认 markdown",
                "enum", List.of("html", "text", "markdown", "json")));
        properties.put("headers", ToolDefinition.stringProperty("可选 JSON 对象字符串，请求头"));
        properties.put("timeoutMs", ToolDefinition.integerProperty("可选超时时间，毫秒"));
        properties.put("maxBytes", ToolDefinition.integerProperty("可选最大下载字节数"));
        return ToolDefinition.low(
                "builtin.web.fetch",
                "Web Fetch",
                "打开指定 URL 并按 format 返回内容。参数：url；format 可选 html/text/markdown/json，默认 markdown；headers(JSON)、timeoutMs、maxBytes 可选。",
                ToolDefinition.objectSchema(properties, false, List.of("url")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            // 工具参数全部来自模型计划，必须做边界处理，避免任意超大下载或内网访问。
            String url = required(call, "url");
            String format = call.arguments().getOrDefault("format", "markdown").trim().toLowerCase(Locale.ROOT);
            int timeoutMs = intArg(call, "timeoutMs", 20_000);
            int maxBytes = intArg(call, "maxBytes", 1024 * 1024);
            Map<String, String> headers = jsonObjectArg(call, "headers");
            WebFetchResponse response = client.fetch(url, headers, timeoutMs, maxBytes);
            return ToolResult.success(format(response, format));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String format(WebFetchResponse response, String format) {
        return switch (format) {
            case "html" -> metadata(response) + response.body();
            case "text", "txt" -> metadata(response) + toText(response.body());
            case "json" -> metadata(response) + JSONUtil.formatJsonStr(response.body());
            case "markdown", "md" -> metadata(response) + toMarkdown(response.body());
            default -> throw new IllegalArgumentException("不支持的 format：" + format + "，可选 html/text/markdown/json");
        };
    }

    private String metadata(WebFetchResponse response) {
        return "url: " + response.url() + "\n"
                + "status: " + response.status() + "\n"
                + "contentType: " + response.contentType() + "\n\n";
    }

    private String toText(String html) {
        return normalizeText(HtmlUtil.cleanHtmlTag(html));
    }

    private String toMarkdown(String html) {
        String markdown = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")
                .replaceAll("(?is)<h1[^>]*>(.*?)</h1>", "\n# $1\n")
                .replaceAll("(?is)<h2[^>]*>(.*?)</h2>", "\n## $1\n")
                .replaceAll("(?is)<h3[^>]*>(.*?)</h3>", "\n### $1\n")
                .replaceAll("(?is)<li[^>]*>(.*?)</li>", "\n- $1")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</p>", "\n\n")
                .replaceAll("(?is)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "$2 ($1)");
        return normalizeText(HtmlUtil.cleanHtmlTag(markdown));
    }

    private String normalizeText(String text) {
        return HtmlUtil.unescape(text)
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private Map<String, String> jsonObjectArg(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = JSONUtil.parseObj(value);
        Map<String, String> headers = new LinkedHashMap<>();
        raw.forEach((key, item) -> headers.put(key, item == null ? "" : item.toString()));
        return headers;
    }
}
