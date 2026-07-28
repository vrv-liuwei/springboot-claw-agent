package com.github.clawagent.server.service;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.server.config.ServerAuthProperties;
import com.github.clawagent.server.support.TestIdentityStores;

import com.github.clawagent.server.dto.DeviceRegisterRequest;
import com.github.clawagent.server.dto.DevicePermissionUpdateRequest;
import com.github.clawagent.server.dto.DeviceUserBindRequest;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.PolicyResolveView;
import com.github.clawagent.spi.ChannelRegistry;
import com.github.clawagent.spring.ClawAgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TaskPolicyEnrichmentServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void devicePolicyTightensTaskPolicyAndIntersectsToolWhitelist() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var device = devices.register(new DeviceRegisterRequest(
                "Desktop", "desktop", "custom",
                List.of("builtin.execute.command", "builtin.filesystem.read_text_file"),
                Map.of()));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("webui", "console", Map.of(
                "deviceId", device.id(),
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start"
        ));

        assertEquals("custom", enriched.get("toolPermissionMode"));
        assertEquals("builtin.execute.command", enriched.get("approvedToolIds"));
        assertEquals("device:" + device.id(), enriched.get("policy.approval.source"));
        assertEquals("device", enriched.get("policy.approval.scope"));
    }

    @Test
    void readOnlyDevicePolicyEnablesAgentIsolation() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var device = devices.register(new DeviceRegisterRequest("Kiosk", "desktop", "ask", List.of(), Map.of()));
        devices.updatePermissions(device.id(), new DevicePermissionUpdateRequest("read-only", List.of()));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("webui", "console", Map.of("device.id", device.id()));

        assertEquals("read-only", enriched.get("toolPermissionMode"));
        assertEquals("read-only", enriched.get("agent.isolation"));
        assertFalse(enriched.containsKey("approvedToolIds"));
    }

    @Test
    void localUserMetadataCanConstrainTaskPolicy() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        users.create(new LocalUserCreateRequest("alice", "123456", "Alice", "user", Map.of(
                "permissionMode", "ask",
                "approvedToolIds", "builtin.filesystem.read_text_file"
        )));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("api", "alice", Map.of("toolPermissionMode", "full-access"));

        assertEquals("ask", enriched.get("toolPermissionMode"));
        assertEquals("builtin.filesystem.read_text_file", enriched.get("approvedToolIds"));
        assertEquals("user", enriched.get("policy.approval.scope"));
    }

    @Test
    void viewerRoleDefaultsToReadOnlyEvenWithoutPermissionMetadata() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        users.create(new LocalUserCreateRequest("viewer", "123456", "Viewer", "viewer", Map.of()));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("api", "viewer", Map.of("toolPermissionMode", "auto"));

        assertEquals("read-only", enriched.get("toolPermissionMode"));
        assertEquals("read-only", enriched.get("agent.isolation"));
        assertEquals("user", enriched.get("policy.approval.scope"));
    }

    @Test
    void apiTokenPolicyConstrainsProgrammaticTaskPolicy() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("api", "", Map.of(
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start",
                "apiToken.id", "token-1",
                "apiToken.permissionMode", "custom",
                "apiToken.approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        ));

        assertEquals("custom", enriched.get("toolPermissionMode"));
        assertEquals("builtin.execute.command", enriched.get("approvedToolIds"));
        assertEquals("api-token:token-1", enriched.get("policy.approval.source"));
        assertEquals("api-token", enriched.get("policy.approval.scope"));
    }

    @Test
    void channelRegistryPolicyAppliesWhenTaskOnlyCarriesChannelId() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        ChannelDefinition channel = new ChannelDefinition(
                "feishu-main", "飞书主通道", "feishu", true,
                "custom",
                List.of("builtin.execute.command", "builtin.filesystem.read_text_file"),
                "", Map.of(), null, null);
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices,
                new InMemoryChannelRegistry(List.of(channel)));

        PolicyResolveView view = service.resolve("feishu-main", "", Map.of(
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start"
        ));

        assertEquals("custom", view.effectiveMode());
        assertEquals("channel", view.scope());
        assertEquals("channel:feishu-main", view.source());
        assertEquals(List.of("builtin.execute.command"), view.approvedToolIds());
        assertEquals("custom", view.effectiveMetadata().get("toolPermissionMode"));
        assertEquals("builtin.execute.command", view.effectiveMetadata().get("approvedToolIds"));
        assertEquals(2, view.layers().size());
    }

    @Test
    void apiTokenOwnerUserPolicyIsAlsoApplied() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var user = users.create(new LocalUserCreateRequest("carol", "123456", "Carol", "user", Map.of(
                "permissionMode", "ask",
                "approvedToolIds", "builtin.filesystem.read_text_file"
        )));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("api", "", Map.of(
                "apiToken.id", "token-2",
                "apiToken.ownerUserId", user.id(),
                "apiToken.permissionMode", "auto",
                "apiToken.approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        ));

        assertEquals("ask", enriched.get("toolPermissionMode"));
        assertEquals("builtin.filesystem.read_text_file", enriched.get("approvedToolIds"));
        assertEquals("user", enriched.get("policy.approval.scope"));
    }

    @Test
    void deviceBoundUserPolicyAppliesWhenTaskOnlyCarriesDeviceId() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var user = users.create(new LocalUserCreateRequest("bob", "123456", "Bob", "user", Map.of(
                "permissionMode", "custom",
                "approvedToolIds", "builtin.filesystem.read_text_file,builtin.execute.command"
        )));
        var device = devices.register(new DeviceRegisterRequest(
                "Desktop", "desktop", "auto",
                List.of("builtin.execute.command", "builtin.process.start"),
                Map.of()));
        devices.bindUser(device.id(), new DeviceUserBindRequest(user.id(), user.username()));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        Map<String, String> enriched = service.enrich("webui", "", Map.of("deviceId", device.id()));

        assertEquals("custom", enriched.get("toolPermissionMode"));
        assertEquals("builtin.execute.command", enriched.get("approvedToolIds"));
        assertEquals("user", enriched.get("policy.approval.scope"));
    }

    @Test
    void resolveExplainsEffectivePolicyAndAllMatchedLayers() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var user = users.create(new LocalUserCreateRequest("dave", "123456", "Dave", "user", Map.of(
                "permissionMode", "custom",
                "approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        )));
        var device = devices.register(new DeviceRegisterRequest(
                "Desktop", "desktop", "auto",
                List.of("builtin.execute.command", "builtin.process.start"),
                Map.of()));
        devices.bindUser(device.id(), new DeviceUserBindRequest(user.id(), user.username()));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        PolicyResolveView view = service.resolve("ddio", "", Map.of(
                "deviceId", device.id(),
                "apiToken.id", "token-1",
                "apiToken.permissionMode", "ask",
                "apiToken.approvedToolIds", "builtin.execute.command,builtin.process.start"
        ));

        assertEquals("ask", view.effectiveMode());
        assertEquals("api-token", view.scope());
        assertEquals("api-token:token-1", view.source());
        assertEquals(List.of("builtin.execute.command"), view.approvedToolIds());
        assertEquals("ask", view.effectiveMetadata().get("toolPermissionMode"));
        assertEquals("builtin.execute.command", view.effectiveMetadata().get("approvedToolIds"));
        assertEquals("ddio", view.effectiveMetadata().get("channel.id"));
        assertEquals(3, view.layers().size());
        assertEquals(1, view.layers().stream().filter(layer -> Boolean.TRUE.equals(layer.effective())).count());
    }

    @Test
    void agentReadOnlyIsolationOverridesChannelAndUserWhitelist() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        var user = users.create(new LocalUserCreateRequest("erin", "123456", "Erin", "user", Map.of(
                "permissionMode", "custom",
                "approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        )));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        PolicyResolveView view = service.resolve("feishu-main", user.id(), Map.of(
                "toolPermissionMode", "auto",
                "policy.approval.source", "channel:feishu-main",
                "policy.approval.scope", "channel",
                "approvedToolIds", "builtin.execute.command,builtin.process.start",
                "agent.isolation", "read-only"
        ));

        assertEquals("read-only", view.effectiveMode());
        assertEquals("agent", view.scope());
        assertEquals("agent-isolation:read-only", view.source());
        assertEquals(List.of(), view.approvedToolIds());
        assertEquals("read-only", view.effectiveMetadata().get("agent.isolation"));
        assertFalse(view.effectiveMetadata().containsKey("approvedToolIds"));
        assertEquals(3, view.layers().size());
    }

    @Test
    void agentEffectiveIsolationOverridesEvenWithoutLegacyIsolationField() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        PolicyResolveView view = service.resolve("webui", "", Map.of(
                "toolPermissionMode", "full-access",
                "approvedToolIds", "builtin.execute.command",
                "agent.isolation.requested", "write",
                "agent.isolation.effective", "read-only"
        ));

        assertEquals("read-only", view.effectiveMode());
        assertEquals("agent", view.scope());
        assertEquals("agent-isolation:read-only", view.source());
        assertEquals(List.of(), view.approvedToolIds());
        assertEquals("read-only", view.effectiveMetadata().get("agent.isolation"));
        assertFalse(view.effectiveMetadata().containsKey("approvedToolIds"));
    }

    @Test
    void agentPolicyCanConstrainSubTaskTools() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices);

        PolicyResolveView view = service.resolve("webui", "", Map.of(
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start",
                "agent.id", "sub-agent-1",
                "agent.permissionMode", "custom",
                "agent.approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        ));

        assertEquals("custom", view.effectiveMode());
        assertEquals("agent", view.scope());
        assertEquals("agent:sub-agent-1", view.source());
        assertEquals(List.of("builtin.execute.command"), view.approvedToolIds());
        assertEquals("custom", view.effectiveMetadata().get("toolPermissionMode"));
        assertEquals("builtin.execute.command", view.effectiveMetadata().get("approvedToolIds"));
        assertEquals(2, view.layers().size());
    }

    @Test
    void configuredAgentRolePolicyParticipatesInPolicyResolution() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        ClawAgentProperties properties = new ClawAgentProperties();
        ClawAgentProperties.AgentPolicy coderPolicy = new ClawAgentProperties.AgentPolicy();
        coderPolicy.setPermissionMode("custom");
        coderPolicy.setApprovedToolIds(List.of("builtin.execute.command", "builtin.filesystem.read_text_file"));
        properties.getAgents().setPolicies(Map.of("coder", coderPolicy));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices, null, properties);

        PolicyResolveView view = service.resolve("webui", "", Map.of(
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start",
                "agent.role", "Coder"
        ));

        assertEquals("custom", view.effectiveMode());
        assertEquals("agent", view.scope());
        assertEquals("agent-role:Coder", view.source());
        assertEquals(List.of("builtin.execute.command"), view.approvedToolIds());
        assertEquals("builtin.execute.command", view.effectiveMetadata().get("approvedToolIds"));
        assertEquals(2, view.layers().size());
    }

    @Test
    void configuredUserRolePolicyParticipatesInPolicyResolution() {
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        users.create(new LocalUserCreateRequest("ops", "123456", "Operator", "operator", Map.of()));
        ServerAuthProperties authProperties = new ServerAuthProperties();
        ServerAuthProperties.UserRolePolicy operatorPolicy = new ServerAuthProperties.UserRolePolicy();
        operatorPolicy.setPermissionMode("custom");
        operatorPolicy.setApprovedToolIds(List.of("builtin.execute.command", "builtin.filesystem.read_text_file"));
        authProperties.setRolePolicies(Map.of("operator", operatorPolicy));
        TaskPolicyEnrichmentService service = new TaskPolicyEnrichmentService(users, devices, null, null, authProperties);

        PolicyResolveView view = service.resolve("webui", "ops", Map.of(
                "toolPermissionMode", "auto",
                "approvedToolIds", "builtin.execute.command,builtin.process.start"
        ));

        assertEquals("custom", view.effectiveMode());
        assertEquals("user", view.scope());
        assertEquals(List.of("builtin.execute.command"), view.approvedToolIds());
        assertEquals("custom", view.effectiveMetadata().get("toolPermissionMode"));
        assertEquals("builtin.execute.command", view.effectiveMetadata().get("approvedToolIds"));
        assertEquals(2, view.layers().size());
    }

    private static class InMemoryChannelRegistry implements ChannelRegistry {
        private final Map<String, ChannelDefinition> channels = new LinkedHashMap<>();

        private InMemoryChannelRegistry(List<ChannelDefinition> channels) {
            channels.forEach(channel -> this.channels.put(channel.id(), channel));
        }

        @Override
        public List<ChannelDefinition> list() {
            return new ArrayList<>(channels.values());
        }

        @Override
        public Optional<ChannelDefinition> find(String channelId) {
            return Optional.ofNullable(channels.get(channelId));
        }

        @Override
        public ChannelDefinition save(ChannelDefinition request) {
            // 测试只需要验证策略读取，仍实现 save 以满足注册表接口契约。
            channels.put(request.id(), request);
            return request;
        }

        @Override
        public boolean delete(String channelId) {
            return channels.remove(channelId) != null;
        }
    }
}
