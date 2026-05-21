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
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置文件写入工具。
 * 写入属于高风险操作，会被默认 ToolExecutionGuard 要求审批。
 */
public class FilesystemWriteFileTool implements AgentTool {
    private final FilesystemAccess access;

    public FilesystemWriteFileTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("要写入的文件路径，必须位于 allowed-roots 内"));
        properties.put("content", ToolDefinition.stringProperty("写入内容"));
        properties.put("append", Map.of("type", "boolean", "description", "是否追加写入，默认 false"));
        properties.put("charset", ToolDefinition.stringProperty("可选字符集，默认 UTF-8"));
        return new ToolDefinition(
                "builtin.filesystem.write_file",
                "Write File",
                "写入 allowed-roots 内的文件。参数：path/content；可选 append/charset。高风险工具，默认需要审批。",
                "high",
                ToolDefinition.objectSchema(properties, false, List.of("path", "content")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path path = access.resolveWritable(call.arguments().get("path"));
            String content = call.arguments().get("content");
            if (content == null) {
                return ToolResult.error("缺少参数：content");
            }
            Charset charset = charset(call.arguments().get("charset"));
            boolean append = Boolean.parseBoolean(call.arguments().getOrDefault("append", "false"));
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            // 追加和覆盖显式分支，避免模型误传 append 时造成不可预期的覆盖。
            if (append) {
                Files.writeString(path, content, charset, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return ToolResult.success("写入成功：" + path);
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private Charset charset(String value) {
        return value == null || value.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(value.trim());
    }
}
