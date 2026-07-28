package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.nio.file.Path;

/**
 * 把本地 Skill 暴露成 ClawAgent 标准工具。
 * 具体执行方式由 SkillExecutorFactory 根据 manifest.metadata 选择，避免工具适配层堆积业务逻辑。
 */
public class SkillAgentTool implements AgentTool {
    /** Skill 执行器工厂，根据 metadata.executor / metadata.tools.<toolName> 选择执行器。 */
    private final SkillExecutorFactory executorFactory;
    /** Skill 的 manifest 元数据，决定工具名称、描述、权限和入口文件。 */
    private final SkillManifest manifest;
    /** 当前适配出来的工具名，可能是 default 或 manifest.tools 中的某个名称。 */
    private final String toolName;
    /** 注册到 AgentToolRegistry 的完整工具 id，例如 skill.skills-create.create。 */
    private final String toolId;
    /** Skill 本地目录，用于读取入口文件并限制文件访问边界。 */
    private final Path skillDir;

    public SkillAgentTool(SkillManifest manifest, String toolName, String toolId, Path skillDir) {
        this(manifest, toolName, toolId, skillDir, null);
    }

    public SkillAgentTool(SkillManifest manifest, String toolName, String toolId, Path skillDir,
                          SkillProcessExecutor processExecutor) {
        this.manifest = manifest;
        this.toolName = toolName;
        this.toolId = toolId;
        this.skillDir = skillDir;
        this.executorFactory = new SkillExecutorFactory(processExecutor);
    }

    @Override
    public ToolDefinition definition() {
        String description = manifest.description() == null || manifest.description().isBlank()
                ? "执行本地 Skill：" + manifest.name()
                : manifest.description();
        return new ToolDefinition(toolId, manifest.name() + "/" + toolName, description, riskLevel(), inputSchema());
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            // 每次调用时读取 executor 配置，便于用户编辑 manifest 后通过重载/重启生效。
            var executorConfig = executorFactory.configFor(manifest, toolName);
            SkillExecutor executor = executorFactory.executorFor(executorConfig);
            return executor.execute(new SkillExecutionContext(manifest, toolName, skillDir, executorConfig), call, context);
        } catch (RuntimeException e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String riskLevel() {
        for (String permission : manifest.permissions()) {
            String normalized = permission == null ? "" : permission.toLowerCase();
            if (normalized.contains("shell") || normalized.contains("write") || normalized.contains("delete")) {
                return "high";
            }
            if (normalized.contains("file") || normalized.contains("network") || normalized.contains("http")) {
                return "medium";
            }
        }
        return "low";
    }

    private java.util.Map<String, Object> inputSchema() {
        java.util.Map<String, Object> executorConfig = executorFactory.configFor(manifest, toolName);
        Object schema = executorConfig.get("inputSchema");
        if (schema instanceof java.util.Map<?, ?> map) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return ToolDefinition.objectSchema(java.util.Map.of(), true, java.util.List.of());
    }
}
