package com.github.clawagent.toolkit.webfetch;

import cn.hutool.http.HtmlUtil;
import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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
    private final WebFetchToolkitProperties properties;

    public WebFetchTool(WebFetchClient client) {
        this(client, new WebFetchToolkitProperties());
    }

    public WebFetchTool(WebFetchClient client, WebFetchToolkitProperties properties) {
        this.client = client;
        this.properties = properties == null ? new WebFetchToolkitProperties() : properties;
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
        properties.put("extractMode", Map.of(
                "type", "string",
                "description", "正文提取模式，默认 readable；raw 用于调试原始页面",
                "enum", List.of("readable", "raw")));
        properties.put("maxOutputChars", ToolDefinition.integerProperty("可选最大输出字符数，默认使用配置 MAX_OUTPUT_CHARS"));
        return ToolDefinition.low(
                "builtin.web.fetch",
                "Web Fetch",
                "打开指定 URL 并按 format 返回内容。默认 extractMode=readable，会提取正文并减少 HTML/JS/CSS token；format 可选 html/text/markdown/json。",
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
            String extractMode = call.arguments().getOrDefault("extractMode", "readable").trim().toLowerCase(Locale.ROOT);
            int maxOutputChars = intArg(call, "maxOutputChars", properties.getMaxOutputChars());
            Map<String, String> headers = jsonObjectArg(call, "headers");
            WebFetchResponse response = client.fetch(url, headers, timeoutMs, maxBytes);
            return ToolResult.success(format(response, format, extractMode, maxOutputChars));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String format(WebFetchResponse response, String format, String extractMode, int maxOutputChars) {
        String rawBody = response.body() == null ? "" : response.body();
        String extracted = "raw".equals(extractMode) ? rawBody : readableHtml(rawBody, response.url());
        String formatted = switch (format) {
            case "html" -> "raw".equals(extractMode) ? rawBody : extracted;
            case "text", "txt" -> "raw".equals(extractMode) ? toText(rawBody) : normalizeText(extracted);
            case "json" -> JSONUtil.formatJsonStr(extracted);
            case "markdown", "md" -> "raw".equals(extractMode) ? toMarkdown(rawBody) : toMarkdown(extracted);
            default -> throw new IllegalArgumentException("不支持的 format：" + format + "，可选 html/text/markdown/json");
        };
        TruncatedText truncated = truncate(formatted, maxOutputChars);
        return metadata(response, extractMode, rawBody.length(), formatted.length(), truncated.truncated()) + truncated.text();
    }

    private String metadata(WebFetchResponse response, String extractMode, int originalChars, int extractedChars, boolean truncated) {
        return "url: " + response.url() + "\n"
                + "status: " + response.status() + "\n"
                + "contentType: " + response.contentType() + "\n"
                + "extractMode: " + extractMode + "\n"
                + "originalChars: " + originalChars + "\n"
                + "extractedChars: " + extractedChars + "\n"
                + "truncated: " + truncated + "\n\n";
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

    private String readableHtml(String html, String url) {
        Document document = Jsoup.parse(html, url);
        // 先删除明显不会进入回答的结构，减少 GitHub/GitLab 页面中的导航、脚本、表单噪声。
        document.select("script,style,noscript,svg,canvas,nav,header,footer,form,template").remove();
        Element selected = first(document,
                "#readme",
                ".markdown-body",
                "main",
                ".wiki",
                ".file-holder",
                ".tree-holder",
                "article",
                "[role=main]",
                "body");
        return selected == null ? normalizeText(document.text()) : normalizeText(selected.text());
    }

    private Element first(Document document, String... selectors) {
        for (String selector : selectors) {
            Element element = document.selectFirst(selector);
            if (element != null && !element.text().isBlank()) {
                return element;
            }
        }
        return null;
    }

    private TruncatedText truncate(String text, int maxOutputChars) {
        int limit = maxOutputChars <= 0 ? properties.getMaxOutputChars() : maxOutputChars;
        if (text == null || text.length() <= limit) {
            return new TruncatedText(text == null ? "" : text, false);
        }
        return new TruncatedText(text.substring(0, limit) + "\n\n[内容已按 maxOutputChars=" + limit + " 截断]", true);
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

    private record TruncatedText(String text, boolean truncated) {}
}
