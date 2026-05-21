package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置文件元信息工具。
 */
public class FilesystemFileInfoTool implements AgentTool {
    private final FilesystemAccess access;

    public FilesystemFileInfoTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("文件或目录路径，必须位于 allowed-roots 内"));
        return ToolDefinition.low(
                "builtin.filesystem.get_file_info",
                "Get File Info",
                "获取 allowed-roots 内文件或目录的基础元信息。参数：path。",
                ToolDefinition.objectSchema(properties, false, List.of("path")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path path = access.resolveReadable(call.arguments().get("path"));
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            // 输出用固定字段，方便日志、页面和模型稳定解析。
            return ToolResult.success("path: " + path
                    + "\ntype: " + type(attrs)
                    + "\nsize: " + attrs.size()
                    + "\ncreatedAt: " + attrs.creationTime()
                    + "\nmodifiedAt: " + attrs.lastModifiedTime()
                    + "\nreadable: " + Files.isReadable(path)
                    + "\nwritable: " + Files.isWritable(path));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String type(BasicFileAttributes attrs) {
        if (attrs.isDirectory()) {
            return "directory";
        }
        if (attrs.isRegularFile()) {
            return "file";
        }
        return "other";
    }
}
