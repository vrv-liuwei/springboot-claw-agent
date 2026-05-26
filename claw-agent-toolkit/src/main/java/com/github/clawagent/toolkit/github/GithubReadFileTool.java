package com.github.clawagent.toolkit.github;

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
 * GitHub raw 文件读取工具。
 */
public class GithubReadFileTool implements AgentTool {
    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("repoUrl", ToolDefinition.stringProperty("GitHub 仓库 URL 或 owner/repo"));
        properties.put("path", ToolDefinition.stringProperty("仓库内文件路径，例如 SKILL.md 或 scripts/anysearch_cli.py"));
        properties.put("branch", ToolDefinition.stringProperty("可选分支，默认 main"));
        properties.put("timeoutMs", ToolDefinition.integerProperty("可选超时时间，毫秒，默认 60000"));
        properties.put("maxOutputChars", ToolDefinition.integerProperty("可选最大输出字符数，默认 20000"));
        return ToolDefinition.low(
                "builtin.github.read_file",
                "GitHub Read File",
                "读取 GitHub 仓库 raw 文件内容。用于查看 SKILL.md、runtime.conf、scripts 等文件，并与 skill.skills-install.install 配合完成 Skill 安装。",
                ToolDefinition.objectSchema(properties, false, List.of("repoUrl", "path")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            GithubRepo repo = GithubRepo.parse(call.arguments().get("repoUrl"), call.arguments().get("branch"));
            String path = call.arguments().get("path");
            int timeoutMs = intArg(call, "timeoutMs", 60_000);
            int maxOutputChars = intArg(call, "maxOutputChars", 20_000);
            AgentHttpResponse response = GithubClient.rawFile(repo, path, timeoutMs);
            if (response.statusCode() >= 300) {
                return ToolResult.error("GitHub raw 请求失败 status=" + response.statusCode() + " path=" + path + " body=" + response.body());
            }
            String body = response.body() == null ? "" : response.body();
            boolean truncated = body.length() > maxOutputChars;
            String content = truncated ? body.substring(0, maxOutputChars) : body;
            String rawUrl = "https://raw.githubusercontent.com/" + repo.owner() + "/" + repo.name()
                    + "/" + repo.branch() + "/" + path.replace('\\', '/').replaceAll("^/+", "");
            return ToolResult.success("repo: " + repo.owner() + "/" + repo.name() + "\n"
                    + "branch: " + repo.branch() + "\n"
                    + "path: " + path + "\n"
                    + "rawUrl: " + rawUrl + "\n"
                    + "originalChars: " + body.length() + "\n"
                    + "truncated: " + truncated + "\n\n"
                    + content);
        } catch (Exception e) {
            return ToolResult.error("读取 GitHub 文件失败：" + e.getMessage());
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
