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
        properties.put("startLine", ToolDefinition.integerProperty("可选，从第 N 行开始读取，1-based"));
        properties.put("limit", ToolDefinition.integerProperty("可选，与 startLine 配合使用，读取最多 N 行"));
        properties.put("showLineNumbers", ToolDefinition.integerProperty("可选，1 表示输出行号"));
        return ToolDefinition.low(
                "builtin.filesystem.read_text_file",
                "Read Text File",
                "读取 allowed-roots 内的文本文件。参数：path；可选 charset/head/tail/startLine/limit/showLineNumbers。",
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
            int startLine = intArg(call, "startLine", 0);
            int limit = intArg(call, "limit", 0);
            boolean showLineNumbers = boolArg(call, "showLineNumbers");
            if (startLine > 0 || limit > 0 || showLineNumbers) {
                String content = readLineRange(path, charset, Math.max(1, startLine), limit <= 0 ? 80 : limit, showLineNumbers);
                return ToolResult.success("path: " + path + "\n\n" + content);
            }
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

    private boolean boolArg(ToolCall call, String name) {
        String value = call.arguments().get(name);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private String readLineRange(Path path, Charset charset, int startLine, int limit, boolean showLineNumbers) throws Exception {
        StringBuilder output = new StringBuilder();
        try (var lines = Files.lines(path, charset)) {
            List<String> selected = lines.skip((long) startLine - 1)
                    .limit(Math.max(1, limit))
                    .toList();
            for (int i = 0; i < selected.size(); i++) {
                if (showLineNumbers) {
                    output.append(String.format("%6d: ", startLine + i));
                }
                output.append(selected.get(i)).append('\n');
            }
        }
        return output.toString().stripTrailing();
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
