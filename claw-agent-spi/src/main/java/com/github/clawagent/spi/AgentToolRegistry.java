package com.github.clawagent.spi;

import com.github.clawagent.core.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AgentToolRegistry 是 Agent 可调用工具的运行时目录。
 * Runtime、MCP、Skill、Toolkit 和业务自定义 AgentTool 都通过它完成统一注册和查找。
 */
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public AgentToolRegistry(List<AgentTool> initialTools) {
        // Spring Bean 形式的业务工具会作为初始工具传入，先注册到统一目录。
        initialTools.forEach(this::register);
    }

    public synchronized void register(AgentTool tool) {
        String id = tool.definition().id();
        if (tools.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate tool id: " + id);
        }
        tools.put(id, tool);
    }

    public synchronized void registerOrReplace(AgentTool tool) {
        // MCP/Skill/Toolkit 的动态刷新场景允许覆盖同名工具，避免重复注册失败。
        tools.put(tool.definition().id(), tool);
    }

    public synchronized void unregisterByPrefix(String prefix) {
        // MCP Server 或 Skill 重新加载前，按工具 id 前缀清理旧版本工具。
        tools.keySet().removeIf(id -> id.startsWith(prefix));
    }

    public Optional<AgentTool> find(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public List<AgentTool> tools() {
        return new ArrayList<>(tools.values());
    }
}
