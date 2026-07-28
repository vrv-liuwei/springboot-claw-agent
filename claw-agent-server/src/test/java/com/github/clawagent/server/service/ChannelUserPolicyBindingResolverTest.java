package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.server.dto.ChannelUserBindingRequest;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelUserPolicyBindingResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void boundExternalUserReusesLocalUserPolicyForInboundChannelTask() {
        LocalUserService users = TestIdentityStores.localUserService(tempDir);
        DeviceRegistryService devices = TestIdentityStores.deviceRegistryService(tempDir);
        var user = users.create(new LocalUserCreateRequest("alice", "123456", "Alice", "user", Map.of(
                "permissionMode", "custom",
                "approvedToolIds", "builtin.execute.command,builtin.filesystem.read_text_file"
        )));
        ChannelUserBindingService bindings = TestIdentityStores.channelUserBindingService(tempDir);
        bindings.bind("feishu-main", new ChannelUserBindingRequest(
                "ou_abc", "飞书用户", user.id(), user.username(), Map.of()));
        ChannelUserPolicyBindingResolver resolver = new ChannelUserPolicyBindingResolver(
                bindings, new TaskPolicyEnrichmentService(users, devices));
        ChannelDefinition channel = new ChannelDefinition("feishu-main", "飞书主号", "feishu", true,
                "auto", List.of("builtin.execute.command", "builtin.process.start"),
                "/api/v1/channels/feishu-main/inbound", Map.of(), Instant.EPOCH, Instant.EPOCH);
        ChannelInboundMessage message = new ChannelInboundMessage(
                "feishu-main", "chat-1", "ou_abc", "text", "hello", Map.of(), Map.of());

        Map<String, String> metadata = resolver.resolve(channel, message, Map.of(
                "toolPermissionMode", "auto",
                "policy.approval.source", "channel:feishu-main",
                "policy.approval.scope", "channel",
                "approvedToolIds", "builtin.execute.command,builtin.process.start"
        ));

        assertEquals(user.id(), metadata.get("localUserId"));
        assertEquals("custom", metadata.get("toolPermissionMode"));
        assertEquals("user", metadata.get("policy.approval.scope"));
        assertEquals("builtin.execute.command", metadata.get("approvedToolIds"));
        assertEquals("ou_abc", metadata.get("channel.boundExternalUserId"));
    }
}
