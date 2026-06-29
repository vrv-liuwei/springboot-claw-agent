package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.mcp.McpImportRequest;
import com.github.clawagent.mcp.McpPromptContent;
import com.github.clawagent.mcp.McpPromptDescriptor;
import com.github.clawagent.mcp.McpPromptGetRequest;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.mcp.McpResourceContent;
import com.github.clawagent.mcp.McpResourceDescriptor;
import com.github.clawagent.mcp.McpServerConfig;
import com.github.clawagent.mcp.McpServerRegistration;
import com.github.clawagent.mcp.McpToolDescriptor;
import com.github.clawagent.spi.AgentEventStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 管理接口。
 * 这里仅负责服务注册、连接和资源读取，具体协议生命周期由 McpRegistry 屏蔽。
 */
@RestController
@RequestMapping("/api/v1")
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpRegistry mcpRegistry;
    private final AgentEventStore eventStore;

    public McpController(McpRegistry mcpRegistry,
                         @Qualifier("agentEventStore") AgentEventStore eventStore) {
        this.mcpRegistry = mcpRegistry;
        this.eventStore = eventStore;
    }

    @PostMapping("/mcp/servers")
    public McpServerRegistration registerMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server register received id={} name={} transport={}", config.id(), config.name(), config.transport());
        McpServerRegistration registration = mcpRegistry.register(config);
        recordMcpAudit("mcp.server.registered", "MCP 服务已注册", registration.config(), Map.of(
                "status", String.valueOf(registration.status())));
        return registration;
    }

    @PutMapping("/mcp/servers/{serverId}")
    public McpServerRegistration updateMcpServer(
            @PathVariable("serverId") String serverId,
            @RequestBody McpServerConfig config) {
        log.info("mcp server update received id={} name={} transport={}", serverId, config.name(), config.transport());
        McpServerRegistration registration = mcpRegistry.update(serverId, config);
        recordMcpAudit("mcp.server.updated", "MCP 服务已更新", registration.config(), Map.of(
                "status", String.valueOf(registration.status())));
        return registration;
    }

    @DeleteMapping("/mcp/servers/{serverId}")
    public Map<String, Object> deleteMcpServer(@PathVariable("serverId") String serverId) {
        log.warn("mcp server delete requested id={}", serverId);
        boolean deleted = mcpRegistry.delete(serverId);
        recordMcpAudit("mcp.server.deleted", deleted ? "MCP 服务已删除" : "MCP 服务删除未命中", serverId, null, null,
                Map.of("deleted", String.valueOf(deleted)));
        return Map.of("deleted", deleted, "serverId", serverId);
    }

    @PostMapping("/mcp/servers/test")
    public McpServerRegistration testMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server test received id={} name={} transport={}", config.id(), config.name(), config.transport());
        return mcpRegistry.test(config);
    }

    @PostMapping("/mcp/import")
    public List<McpServerRegistration> importMcpServers(@RequestBody McpImportRequest request) {
        log.info("mcp import requested");
        List<McpServerRegistration> registrations = mcpRegistry.importServers(request.json());
        recordMcpAudit("mcp.server.imported", "MCP 服务已导入", null, null, null,
                Map.of("count", String.valueOf(registrations.size())));
        return registrations;
    }

    @GetMapping("/mcp/servers")
    public List<McpServerRegistration> mcpServers() {
        return mcpRegistry.list();
    }

    @GetMapping("/mcp/servers/{serverId}")
    public McpServerRegistration mcpServer(@PathVariable("serverId") String serverId) {
        return mcpRegistry.find(serverId).orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在：" + serverId));
    }

    @PostMapping("/mcp/servers/{serverId}/connect")
    public McpServerRegistration connectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server connect requested id={}", serverId);
        McpServerRegistration registration = mcpRegistry.connect(serverId);
        recordMcpAudit("mcp.server.connected", "MCP 服务已连接", registration.config(), Map.of(
                "status", String.valueOf(registration.status())));
        return registration;
    }

    @PostMapping("/mcp/servers/{serverId}/disconnect")
    public McpServerRegistration disconnectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server disconnect requested id={}", serverId);
        McpServerRegistration registration = mcpRegistry.disconnect(serverId);
        recordMcpAudit("mcp.server.disconnected", "MCP 服务已断开", registration.config(), Map.of(
                "status", String.valueOf(registration.status())));
        return registration;
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-tools")
    public List<McpToolDescriptor> refreshMcpTools(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh tools requested id={}", serverId);
        return mcpRegistry.refreshTools(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/tools")
    public List<McpToolDescriptor> mcpTools(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listTools(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-resources")
    public List<McpResourceDescriptor> refreshMcpResources(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh resources requested id={}", serverId);
        return mcpRegistry.refreshResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources")
    public List<McpResourceDescriptor> mcpResources(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources/read")
    public McpResourceContent readMcpResource(
            @PathVariable("serverId") String serverId,
            @RequestParam("uri") String uri) {
        log.info("mcp resource read requested id={} uri={}", serverId, uri);
        return mcpRegistry.readResource(serverId, uri);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-prompts")
    public List<McpPromptDescriptor> refreshMcpPrompts(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh prompts requested id={}", serverId);
        return mcpRegistry.refreshPrompts(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/prompts")
    public List<McpPromptDescriptor> mcpPrompts(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listPrompts(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/prompts/{promptName}/get")
    public McpPromptContent getMcpPrompt(
            @PathVariable("serverId") String serverId,
            @PathVariable("promptName") String promptName,
            @RequestBody(required = false) McpPromptGetRequest request) {
        log.info("mcp prompt get requested id={} name={}", serverId, promptName);
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return mcpRegistry.getPrompt(serverId, promptName, arguments);
    }

    private void recordMcpAudit(String type, String message, McpServerConfig config, Map<String, String> extra) {
        if (config == null) {
            recordMcpAudit(type, message, null, null, null, extra);
            return;
        }
        recordMcpAudit(type, message, config.id(), config.name(), String.valueOf(config.transport()), extra);
    }

    private void recordMcpAudit(String type, String message, String serverId, String name, String transport,
                                Map<String, String> extra) {
        Map<String, String> details = new LinkedHashMap<>();
        putIfPresent(details, "serverId", serverId);
        putIfPresent(details, "name", name);
        putIfPresent(details, "transport", transport);
        if (extra != null) {
            details.putAll(extra);
        }
        // MCP 配置可能包含命令和环境变量，审计只记录安全摘要，不落完整配置。
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), "", "", "INFO", type, message, details));
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }
}
