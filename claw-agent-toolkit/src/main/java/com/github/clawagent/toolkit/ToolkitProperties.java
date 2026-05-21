package com.github.clawagent.toolkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * toolkit 模块通用配置，不依赖 Spring Boot。
 * 每个内置工具只读取自己名下的 env，避免 starter 为每个工具扩展专用字段。
 */
public class ToolkitProperties {
    private boolean enabled = true;
    private Map<String, ToolkitToolProperties> tools = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, ToolkitToolProperties> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolkitToolProperties> tools) {
        this.tools = tools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tools);
    }

    public ToolkitToolProperties tool(String id) {
        // 未配置的工具默认启用，保证“开箱即用”的系统能力不会因为缺少配置被关闭。
        return tools.getOrDefault(id, new ToolkitToolProperties());
    }
}
