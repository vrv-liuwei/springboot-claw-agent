package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentContext 是工具执行时可见的受控上下文。
 * 工具不要直接读取全局环境，后续权限、租户、设备信息都从这里传递。
 */
public record AgentContext(AgentTask task, Map<String, String> attributes) {
    public static AgentContext forTask(AgentTask task) {
        return new AgentContext(task, new LinkedHashMap<>());
    }
}
