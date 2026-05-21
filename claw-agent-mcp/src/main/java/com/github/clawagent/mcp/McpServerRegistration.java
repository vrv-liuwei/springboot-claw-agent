package com.github.clawagent.mcp;

import java.time.Instant;

/**
 * MCP Server 注册结果。
 * 当前 M3 骨架只做注册和状态管理，真实连接握手会在后续 connector 中完成。
 */
public class McpServerRegistration {
    /** MCP Server 的标准配置内容，来源于 mcp.json 或 API 表单。 */
    private final McpServerConfig config;
    /** 本次注册对象创建时间；用于管理台展示，不代表配置文件创建时间。 */
    private final Instant registeredAt;
    /** 当前 JVM 内的运行态状态，例如 REGISTERED、CONNECTED、FAILED。 */
    private McpServerStatus status;
    /** 状态补充说明，通常保存连接成功、失败原因或禁用原因。 */
    private String message;

    public McpServerRegistration(McpServerConfig config) {
        this.config = config;
        this.registeredAt = Instant.now();
        this.status = config.enabled() ? McpServerStatus.REGISTERED : McpServerStatus.DISABLED;
    }

    public McpServerConfig config() { return config; }
    public McpServerConfig getConfig() { return config; }
    public Instant registeredAt() { return registeredAt; }
    public Instant getRegisteredAt() { return registeredAt; }
    public McpServerStatus status() { return status; }
    public McpServerStatus getStatus() { return status; }
    public String message() { return message; }
    public String getMessage() { return message; }

    public void markStatus(McpServerStatus status, String message) {
        // 运行态状态只反映当前进程，不写回 mcp.json。
        this.status = status;
        this.message = message;
    }
}
