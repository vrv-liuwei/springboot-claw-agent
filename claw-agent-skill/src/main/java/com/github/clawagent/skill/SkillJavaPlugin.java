package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

import java.util.Map;

/**
 * Skill Java 插件接口。
 * 插件类可放在 Skill 目录的 lib/*.jar 中，也可以由业务应用 classpath 提供。
 */
public interface SkillJavaPlugin {
    /**
     * 兼容简单插件：只返回文本内容。
     */
    String execute(Map<String, String> arguments, Map<String, Object> config);

    /**
     * 结构化执行入口。
     * 复杂插件可以覆盖这个方法，直接返回成功/失败状态；老插件只实现 execute 也能继续工作。
     */
    default ToolResult executeTool(ToolCall call, AgentContext agentContext, Map<String, Object> config) {
        // 默认桥接到旧的文本执行方法，降低已有插件迁移成本。
        String result = execute(call.arguments(), config);
        return ToolResult.success(result == null ? "" : result);
    }
}
