package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置目录列表工具。
 */
public class FilesystemListDirectoryTool implements AgentTool {
    private final FilesystemAccess access;

    public FilesystemListDirectoryTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("要列出的目录路径，必须位于 allowed-roots 内"));
        properties.put("limit", ToolDefinition.integerProperty("可选最大返回条数，默认 200"));
        return ToolDefinition.low(
                "builtin.filesystem.list_directory",
                "List Directory",
                "列出 allowed-roots 内目录下的文件和子目录。参数：path；可选 limit。",
                ToolDefinition.objectSchema(properties, false, List.of("path")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path dir = access.resolveReadable(call.arguments().get("path"));
            if (!Files.isDirectory(dir)) {
                return ToolResult.error("目标不是目录：" + dir);
            }
            int limit = intArg(call, "limit", 200);
            StringBuilder output = new StringBuilder("path: ").append(dir).append('\n');
            // 目录输出稳定排序，方便模型和人类审计对比。
            try (var stream = Files.list(dir)) {
                stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                        .limit(Math.max(1, limit))
                        .forEach(path -> output.append(Files.isDirectory(path) ? "[DIR]  " : "[FILE] ")
                                .append(path.getFileName())
                                .append('\n'));
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
