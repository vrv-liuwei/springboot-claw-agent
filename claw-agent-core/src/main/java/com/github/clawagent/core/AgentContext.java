package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentContext 是工具执行时可见的受控上下文。
 * 工具不要直接读取全局环境，后续权限、租户、设备信息都从这里传递。
 *
 * @param task 当前工具调用所属任务。
 * @param attributes 运行时扩展上下文，例如权限、租户、设备或链路追踪字段。
 */
public record AgentContext(AgentTask task, Map<String, String> attributes) {
    public static AgentContext forTask(AgentTask task) {
        return new AgentContext(task, new LinkedHashMap<>());
    }
}
