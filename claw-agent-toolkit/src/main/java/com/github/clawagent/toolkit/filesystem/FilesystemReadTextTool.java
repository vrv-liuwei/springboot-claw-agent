package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置文本文件读取工具。
 */
public class FilesystemReadTextTool implements AgentTool {
    private final FilesystemAccess access;

    public FilesystemReadTextTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("要读取的文本文件路径，必须位于 allowed-roots 内"));
        properties.put("charset", ToolDefinition.stringProperty("可选字符集，默认 UTF-8"));
        properties.put("head", ToolDefinition.integerProperty("可选，只读取前 N 行"));
        properties.put("tail", ToolDefinition.integerProperty("可选，只读取后 N 行"));
        return ToolDefinition.low(
                "builtin.filesystem.read_text_file",
                "Read Text File",
                "读取 allowed-roots 内的文本文件。参数：path；可选 charset/head/tail。",
                ToolDefinition.objectSchema(properties, false, List.of("path")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path path = access.resolveReadable(call.arguments().get("path"));
            access.checkReadSize(path);
            if (!Files.isRegularFile(path)) {
                return ToolResult.error("目标不是普通文件：" + path);
            }
            Charset charset = charset(call.arguments().get("charset"));
            String content = Files.readString(path, charset);
            // head/tail 用于限制返回内容，避免把大文件一次性塞进上下文。
            content = sliceLines(content, intArg(call, "head", 0), intArg(call, "tail", 0));
            return ToolResult.success("path: " + path + "\n\n" + content);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private Charset charset(String value) {
        return value == null || value.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(value.trim());
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private String sliceLines(String content, int head, int tail) {
        if (head <= 0 && tail <= 0) {
            return content;
        }
        List<String> lines = content.lines().toList();
        if (head > 0) {
            return String.join("\n", lines.subList(0, Math.min(head, lines.size())));
        }
        int from = Math.max(0, lines.size() - tail);
        return String.join("\n", lines.subList(from, lines.size()));
    }
}
