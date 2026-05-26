package com.github.clawagent.toolkit.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;
import com.github.clawagent.spi.AgentTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub 仓库文件树工具。
 */
public class GithubRepoTreeTool implements AgentTool {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repoUrl", ToolDefinition.stringProperty("GitHub 仓库 URL 或 owner/repo，例如 https://github.com/owner/repo.git"));
        properties.put("branch", ToolDefinition.stringProperty("可选分支，默认 main"));
        properties.put("prefix", ToolDefinition.stringProperty("可选路径前缀，只返回该目录下文件"));
        properties.put("timeoutMs", ToolDefinition.integerProperty("可选超时时间，毫秒，默认 60000"));
        properties.put("limit", ToolDefinition.integerProperty("可选最大返回条数，默认 300"));
        return ToolDefinition.low(
                "builtin.github.repo_tree",
                "GitHub Repo Tree",
                "列出 GitHub 仓库文件树。用于分析 GitHub Skill 仓库结构、查找 SKILL.md、scripts、references、assets 等资源，再配合 skill.skills-install.install 安装。",
                ToolDefinition.objectSchema(properties, false, List.of("repoUrl")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            GithubRepo repo = GithubRepo.parse(call.arguments().get("repoUrl"), call.arguments().get("branch"));
            int timeoutMs = intArg(call, "timeoutMs", 60_000);
            int limit = intArg(call, "limit", 300);
            String prefix = call.arguments().getOrDefault("prefix", "").replace('\\', '/').replaceAll("^/+", "");
            AgentHttpResponse response = GithubClient.tree(repo, timeoutMs);
            if (response.statusCode() >= 300) {
                return ToolResult.error("GitHub tree 请求失败 status=" + response.statusCode() + " body=" + response.body());
            }
            JsonNode tree = objectMapper.readTree(response.body()).path("tree");
            StringBuilder builder = new StringBuilder();
            builder.append("repo: ").append(repo.owner()).append('/').append(repo.name()).append('\n');
            builder.append("branch: ").append(repo.branch()).append('\n');
            builder.append("api: https://api.github.com/repos/").append(repo.owner()).append('/').append(repo.name())
                    .append("/git/trees/").append(repo.branch()).append("?recursive=1\n\n");
            int count = 0;
            for (JsonNode item : tree) {
                String path = item.path("path").asText();
                if (!prefix.isBlank() && !path.startsWith(prefix)) {
                    continue;
                }
                // 只返回路径摘要，不把整棵树的原始 JSON 塞给模型，避免 token 膨胀。
                builder.append(item.path("type").asText()).append('\t').append(path);
                if (item.has("size")) {
                    builder.append('\t').append(item.path("size").asLong()).append(" bytes");
                }
                builder.append('\n');
                count++;
                if (count >= limit) {
                    builder.append("truncated: true\n");
                    break;
                }
            }
            builder.append("count: ").append(count).append('\n');
            return ToolResult.success(builder.toString());
        } catch (Exception e) {
            return ToolResult.error("读取 GitHub 仓库文件树失败：" + e.getMessage());
        }
    }

    private int intArg(ToolCall call, String key, int fallback) {
        try {
            String value = call.arguments().get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
