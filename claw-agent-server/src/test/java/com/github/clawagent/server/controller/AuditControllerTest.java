package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.runtime.AgentRuntime;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuditControllerTest {

    @Test
    void auditEventsCanFilterByIdentityToolRiskAndKeyword() {
        AuditController controller = new AuditController(runtimeWithEvents(List.of(
                event("match", "用户批准高危工具", Map.of(
                        "userId", "local-admin",
                        "channel.id", "feishu-main",
                        "toolId", "builtin.execute.command",
                        "riskLevel", "high")),
                event("other-user", "用户批准高危工具", Map.of(
                        "userId", "viewer",
                        "channel.id", "feishu-main",
                        "toolId", "builtin.execute.command",
                        "riskLevel", "high")),
                event("other-tool", "用户批准高危工具", Map.of(
                        "userId", "local-admin",
                        "channel.id", "feishu-main",
                        "toolId", "builtin.filesystem.read_text_file",
                        "riskLevel", "low"))
        )));

        List<AgentEvent> result = controller.auditEvents(null, null, "", "", "", "",
                "local-admin", "feishu-main", "builtin.execute.command", "high",
                "", "", "批准", 10);

        assertEquals(1, result.size());
        assertEquals("match", result.get(0).id());
    }

    @Test
    void auditEventsCanFilterByDetailKeyAndValue() {
        AuditController controller = new AuditController(runtimeWithEvents(List.of(
                event("token", "Token 权限命中", Map.of("apiToken.ownerUserId", "admin-001")),
                event("device", "设备权限命中", Map.of("device.boundUserId", "admin-001")),
                event("other", "Token 权限命中", Map.of("apiToken.ownerUserId", "viewer-001"))
        )));

        List<AgentEvent> result = controller.auditEvents(null, null, "", "", "", "",
                "", "", "", "", "apiToken.ownerUserId", "admin", "", 10);

        assertEquals(1, result.size());
        assertEquals("token", result.get(0).id());
    }

    private AgentEvent event(String id, String message, Map<String, String> details) {
        return new AgentEvent(id, "session-1", "task-1", "INFO", "policy.resolved", message, details, Instant.now());
    }

    private AgentRuntime runtimeWithEvents(List<AgentEvent> events) {
        // Controller 单测只关心审计查询，不启动真实 Runtime，避免把测试扩大到模型和工具链路。
        return (AgentRuntime) Proxy.newProxyInstance(
                AgentRuntime.class.getClassLoader(),
                new Class<?>[]{AgentRuntime.class},
                (proxy, method, args) -> {
                    if ("queryEvents".equals(method.getName())) {
                        return events;
                    }
                    throw new UnsupportedOperationException("Unexpected runtime call: " + method.getName());
                });
    }
}
