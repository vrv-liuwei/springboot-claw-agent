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
 * 内置文件回滚工具。
 * 只允许把 .clawagent/backups/filesystem 下的备份恢复到 allowed-roots 内的目标文件。
 */
public class FilesystemRollbackFileTool implements AgentTool {
    private final FilesystemAccess access;
    private final FileChangeSupport changeSupport;

    public FilesystemRollbackFileTool(FilesystemAccess access) {
        this.access = access;
        this.changeSupport = new FileChangeSupport();
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("要恢复的目标文件路径，必须位于 allowed-roots 内"));
        properties.put("backupPath", ToolDefinition.stringProperty("write_file 输出中的 backupPath"));
        properties.put("charset", ToolDefinition.stringProperty("可选字符集，默认 UTF-8"));
        return new ToolDefinition(
                "builtin.filesystem.rollback_file",
                "Rollback File",
                "用 write_file 生成的备份恢复目标文件。高风险工具，默认需要审批。",
                "high",
                ToolDefinition.objectSchema(properties, false, List.of("path", "backupPath")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path path = access.resolveWritable(call.arguments().get("path"));
            Path backupPath = changeSupport.resolveBackup(call.arguments().get("backupPath"));
            Charset charset = charset(call.arguments().get("charset"));
            String beforeRollback = Files.exists(path) ? Files.readString(path, charset) : "";
            String restored = Files.readString(backupPath, charset);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            // 回滚也是写操作，仍然经过高危工具审批和 allowed-roots 校验。
            Files.writeString(path, restored, charset, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.success(changeSupport.formatRollbackResult(path, backupPath, beforeRollback, restored));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private Charset charset(String value) {
        return value == null || value.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(value.trim());
    }
}
