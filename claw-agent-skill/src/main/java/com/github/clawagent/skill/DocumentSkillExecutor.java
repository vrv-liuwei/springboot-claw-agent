package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档型 Skill 执行器。
 * 默认执行方式：读取 Skill 入口文档，返回给 Agent 作为专业流程上下文。
 */
class DocumentSkillExecutor implements SkillExecutor {
    @Override
    public ToolResult execute(SkillExecutionContext context, ToolCall call, AgentContext agentContext) {
        String content = readEntrypoint(context);
        StringBuilder result = new StringBuilder();
        result.append("Skill: ").append(context.manifest().name()).append('\n');
        result.append("Tool: ").append(context.toolName()).append('\n');
        result.append("Version: ").append(context.manifest().version()).append('\n');
        if (!context.manifest().permissions().isEmpty()) {
            result.append("Permissions: ").append(context.manifest().permissions()).append('\n');
        }
        result.append("Arguments: ").append(call.arguments()).append('\n');
        result.append("Content:\n").append(content);
        return ToolResult.success(result.toString());
    }

    private String readEntrypoint(SkillExecutionContext context) {
        String entrypoint = context.manifest().entrypoint() == null || context.manifest().entrypoint().isBlank()
                ? "README.md"
                : context.manifest().entrypoint();
        Path path = context.skillDir().resolve(entrypoint).normalize();
        Path root = context.skillDir().normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Skill entrypoint 越权：" + entrypoint);
        }
        if (!Files.exists(path)) {
            return context.manifest().description();
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Skill entrypoint 读取失败：" + e.getMessage(), e);
        }
    }
}
