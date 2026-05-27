package com.github.clawagent.toolkit.content;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 读取本地 Content Artifact 缓存。
 */
public class ContentReadTool implements AgentTool {
    private final ContentArtifactStore store;
    private final ContentArtifactProperties properties;

    public ContentReadTool(ContentArtifactStore store, ContentArtifactProperties properties) {
        this.store = store;
        this.properties = properties == null ? new ContentArtifactProperties() : properties;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("artifactId", ToolDefinition.stringProperty("web.fetch/web.search 返回的 artifactId"));
        properties.put("chunk", ToolDefinition.integerProperty("可选 chunk 序号，从 1 开始"));
        properties.put("query", ToolDefinition.stringProperty("可选关键词，用于在 artifact chunks 中搜索"));
        properties.put("maxChars", ToolDefinition.integerProperty("可选最大返回字符数，默认读取 READ_MAX_CHARS"));
        return ToolDefinition.low(
                "builtin.content.read",
                "Read Cached Content",
                "从本地 Content Artifact 缓存读取网页、搜索结果或长文档内容；已有 artifactId 时优先使用它，避免重复请求 URL。",
                ToolDefinition.objectSchema(properties, false, List.of("artifactId")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            String artifactId = required(call, "artifactId");
            Integer chunk = intArg(call, "chunk");
            String query = call.arguments().get("query");
            int maxChars = intArg(call, "maxChars", properties.getReadMaxChars());
            return ToolResult.success(store.read(artifactId, chunk, query, maxChars));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private Integer intArg(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Integer.parseInt(value.trim());
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }
}
