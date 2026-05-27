package com.github.clawagent.toolkit.websearch;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.WebSearchProvider;
import com.github.clawagent.spi.WebSearchRequest;
import com.github.clawagent.spi.WebSearchResponse;
import com.github.clawagent.spi.WebSearchResult;
import com.github.clawagent.toolkit.content.ContentArtifact;
import com.github.clawagent.toolkit.content.ContentArtifactStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 搜索工具。
 * 它只固定 query 公共参数；Provider 专属参数由 WebSearchProvider 暴露并自行解析。
 */
public class WebSearchTool implements AgentTool {
    private final WebSearchProvider provider;
    private final ContentArtifactStore artifactStore;

    public WebSearchTool(WebSearchProvider provider) {
        this(provider, null);
    }

    public WebSearchTool(WebSearchProvider provider, ContentArtifactStore artifactStore) {
        this.provider = provider;
        this.artifactStore = artifactStore;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", ToolDefinition.stringProperty("搜索关键词或问题"));
        properties.putAll(provider.inputProperties());
        List<String> required = new java.util.ArrayList<>();
        required.add("query");
        required.addAll(provider.requiredArguments());
        return ToolDefinition.low(
                "builtin.web.search",
                "Web Search",
                "通过当前 WebSearchProvider(" + provider.id() + ") 搜索互联网。结果会写入本地 Content Artifact 缓存；需要搜索结果细节时用 builtin.content.read。",
                ToolDefinition.objectSchema(properties, false, required));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            WebSearchResponse response = provider.search(toRequest(call));
            String formatted = format(response);
            if (artifactStore == null) {
                return ToolResult.success(formatted);
            }
            // 搜索结果同样缓存为 artifact，避免后续 ReAct 重复搜索同一个 query。
            ContentArtifact artifact = artifactStore.save(
                    "search",
                    "search://" + provider.id() + "?query=" + response.query(),
                    "text/plain",
                    formatted,
                    formatted);
            return ToolResult.success(metadata(response, artifact) + artifact.summary()
                    + "\n\n后续如需搜索结果细节，请调用 builtin.content.read，参数 artifactId="
                    + artifact.artifactId() + "，可选 chunk 或 query。");
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private WebSearchRequest toRequest(ToolCall call) {
        String query = required(call, "query");
        Map<String, String> providerArguments = new LinkedHashMap<>(call.arguments());
        providerArguments.remove("query");
        return new WebSearchRequest(query, providerArguments);
    }

    private String format(WebSearchResponse response) {
        List<WebSearchResult> results = response.results() == null ? List.of() : response.results();
        StringBuilder builder = new StringBuilder();
        builder.append("provider: ").append(response.provider()).append('\n');
        builder.append("query: ").append(response.query()).append('\n');
        builder.append("resultCount: ").append(results.size()).append('\n');
        builder.append("elapsedMs: ").append(response.elapsedMs()).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            WebSearchResult result = results.get(i);
            builder.append(i + 1).append(". ").append(clean(result.title())).append('\n');
            builder.append("url: ").append(clean(result.url())).append('\n');
            if (!isBlank(result.source())) {
                builder.append("source: ").append(clean(result.source())).append('\n');
            }
            if (!isBlank(result.publishedAt())) {
                builder.append("publishedAt: ").append(clean(result.publishedAt())).append('\n');
            }
            String summary = !isBlank(result.summary()) ? result.summary() : result.snippet();
            if (!isBlank(summary)) {
                builder.append("summary: ").append(clean(summary)).append('\n');
            }
            builder.append('\n');
        }
        if (results.isEmpty()) {
            builder.append("没有搜索到结果。");
        }
        return builder.toString().trim();
    }

    private String metadata(WebSearchResponse response, ContentArtifact artifact) {
        return "provider: " + response.provider() + "\n"
                + "query: " + response.query() + "\n"
                + "resultCount: " + (response.results() == null ? 0 : response.results().size()) + "\n"
                + "elapsedMs: " + response.elapsedMs() + "\n"
                + "artifactId: " + artifact.artifactId() + "\n"
                + "cached: true\n"
                + "summaryChars: " + artifact.summaryChars() + "\n"
                + "chunkCount: " + artifact.chunkCount() + "\n\n";
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
