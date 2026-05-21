package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置文件搜索工具。
 */
public class FilesystemSearchFilesTool implements AgentTool {
    private final FilesystemAccess access;

    public FilesystemSearchFilesTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("搜索根目录，必须位于 allowed-roots 内"));
        properties.put("pattern", ToolDefinition.stringProperty("glob 模式，例如 *.java 或 **/*.md"));
        properties.put("maxDepth", ToolDefinition.integerProperty("可选最大搜索深度，默认 8"));
        properties.put("limit", ToolDefinition.integerProperty("可选最大返回条数，默认使用配置 maxSearchResults"));
        return ToolDefinition.low(
                "builtin.filesystem.search_files",
                "Search Files",
                "在 allowed-roots 内按 glob 搜索文件。参数：path/pattern；可选 maxDepth/limit。",
                ToolDefinition.objectSchema(properties, false, List.of("path", "pattern")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path root = access.resolveReadable(call.arguments().get("path"));
            String pattern = call.arguments().get("pattern");
            if (pattern == null || pattern.isBlank()) {
                return ToolResult.error("缺少参数：pattern");
            }
            int maxDepth = intArg(call, "maxDepth", 8);
            int limit = intArg(call, "limit", access.properties().getMaxSearchResults());
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.trim());
            StringBuilder output = new StringBuilder("root: ").append(root).append('\n');
            // 同时匹配相对路径和文件名，兼容 *.java 与 **/*.java 两类常见写法。
            try (var stream = Files.walk(root, Math.max(1, maxDepth))) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> matcher.matches(root.relativize(path)) || matcher.matches(path.getFileName()))
                        .limit(Math.max(1, limit))
                        .forEach(path -> output.append(root.relativize(path)).append('\n'));
            }
            return ToolResult.success(output.toString().trim());
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }
}
