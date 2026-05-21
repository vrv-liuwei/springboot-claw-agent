package com.github.clawagent.toolkit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个 toolkit 工具配置。
 * env 的含义由具体 AgentTool 自己解释，格式和 MCP Server 的 env 思路保持一致。
 */
public class ToolkitToolProperties {
    private boolean enabled = true;
    private Map<String, String> env = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public void setEnv(Map<String, String> env) {
        this.env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
    }
}
