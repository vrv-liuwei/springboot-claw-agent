package com.github.clawagent.channel;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.ChannelRegistry;
import com.github.clawagent.spi.ChatStreamCallback;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelRouterTest {

    @Test
    void channelPolicyOverridesInboundMetadataAndRecordsSource() {
        CapturingRuntime runtime = new CapturingRuntime();
        ChannelDefinition channel = new ChannelDefinition(
                "feishu",
                "飞书",
                "feishu",
                true,
                "auto",
                List.of("builtin.execute.command"),
                "/api/v1/channels/feishu/inbound",
                Map.of(),
                null,
                null);
        ChannelRouter router = new ChannelRouter(runtime, new SingleChannelRegistry(channel), new ChannelSessionMapper());

        router.receive("feishu", new ChannelInboundMessage(
                "feishu",
                "chat-1",
                "user-1",
                "text",
                "ping",
                Map.of("toolPermissionMode", "full", "approvedToolIds", "builtin.process.start"),
                Map.of()));

        Map<String, String> metadata = runtime.lastRequest.metadata();
        assertEquals("auto", metadata.get("toolPermissionMode"));
        assertEquals("builtin.execute.command", metadata.get("approvedToolIds"));
        assertEquals("channel:feishu", metadata.get("policy.approval.source"));
        assertEquals("channel", metadata.get("policy.approval.scope"));
        assertEquals("local>channel>task>agent-isolation>tool-enforcement", metadata.get("policy.resolutionOrder"));
    }

    @Test
    void sendsAnswerBackWhenChannelOutboundIsEnabled() {
        CapturingRuntime runtime = new CapturingRuntime();
        CapturingOutboundClient outboundClient = new CapturingOutboundClient();
        ChannelDefinition channel = new ChannelDefinition(
                "dingtalk",
                "钉钉",
                "dingtalk",
                true,
                "ask",
                List.of(),
                "/api/v1/channels/dingtalk/inbound",
                Map.of(),
                null,
                null);
        ChannelRouter router = new ChannelRouter(runtime, new SingleChannelRegistry(channel), new ChannelSessionMapper(), outboundClient);

        router.receive("dingtalk", new ChannelInboundMessage("dingtalk", "chat-1", "user-1", "text", "ping", Map.of(), Map.of()));

        assertEquals("dingtalk", outboundClient.channelId);
        assertEquals("chat-1", outboundClient.conversationId);
        assertEquals("ok", outboundClient.text);
    }

    @Test
    void appliesChannelUserBindingResolverBeforeSubmittingRuntimeTask() {
        CapturingRuntime runtime = new CapturingRuntime();
        ChannelDefinition channel = new ChannelDefinition(
                "feishu-main",
                "飞书主账号",
                "feishu",
                true,
                "ask",
                List.of(),
                "/api/v1/channels/feishu/inbound",
                Map.of(),
                null,
                null);
        ChannelUserBindingResolver resolver = (definition, message, metadata) -> {
            assertEquals("feishu-main", definition.id());
            assertEquals("open-user-1", message.externalUserId());
            return new java.util.LinkedHashMap<>() {{
                putAll(metadata);
                put("localUserId", "local-user-1");
                put("user.id", "local-user-1");
                put("user.username", "alice");
                put("channel.userBindingId", "binding-1");
            }};
        };
        ChannelRouter router = new ChannelRouter(runtime, new SingleChannelRegistry(channel), new ChannelSessionMapper(),
                null, null, null, resolver);

        router.receive("feishu-main", new ChannelInboundMessage(
                "feishu-main", "chat-1", "open-user-1", "text", "ping", Map.of(), Map.of()));

        Map<String, String> metadata = runtime.lastRequest.metadata();
        assertEquals("local-user-1", metadata.get("localUserId"));
        assertEquals("alice", metadata.get("user.username"));
        assertEquals("binding-1", metadata.get("channel.userBindingId"));
    }

    private static class SingleChannelRegistry implements ChannelRegistry {
        private final ChannelDefinition channel;

        private SingleChannelRegistry(ChannelDefinition channel) {
            this.channel = channel;
        }

        @Override
        public List<ChannelDefinition> list() {
            return List.of(channel);
        }

        @Override
        public Optional<ChannelDefinition> find(String channelId) {
            return channel.id().equals(channelId) ? Optional.of(channel) : Optional.empty();
        }

        @Override
        public ChannelDefinition save(ChannelDefinition request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean delete(String channelId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class CapturingRuntime implements AgentRuntime {
        private AgentRequest lastRequest;

        @Override
        public AgentResult submit(AgentRequest request) {
            this.lastRequest = request;
            return new AgentResult("task-1", "ok", TaskStatus.COMPLETED, request.sessionId());
        }

        @Override
        public AgentResult submit(AgentRequest request, AgentCallback callback) {
            return submit(request);
        }

        @Override
        public AgentResult submitStream(AgentRequest request, AgentCallback callback, ChatStreamCallback streamCallback) {
            return submit(request);
        }

        @Override
        public AgentTask cancelTask(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentTask approveToolCall(String taskId, String stepId, String toolId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentTask rejectToolCall(String taskId, String stepId, String toolId, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String createSessionId() {
            return "session-1";
        }

        @Override
        public Map<String, Object> clearAllSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentTask getTask(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentStep> getSteps(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentMessage> getTaskMessages(String taskId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentSession createSession(SessionCreateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentSession getSession(String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentSession> listSessions(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentTask> getSessionTasks(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentMessage> getSessionMessages(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentMessage> getSessionMessagesBefore(String sessionId, Instant beforeCreatedAt, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AgentSession summarizeSession(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEvent> getSessionEvents(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEvent> getTaskEvents(String taskId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<AgentEvent> queryEvents(Instant from, Instant to, String level, String type, String sessionId, String taskId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordTaskEvent(String taskId, String level, String type, String message, Map<String, String> details) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenUsageSummary getSessionTokenUsage(String sessionId, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenUsageSummary getTaskTokenUsage(String taskId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class CapturingOutboundClient extends ChannelOutboundClient {
        private String channelId;
        private String conversationId;
        private String text;

        @Override
        public ChannelSendResult sendTextDetailed(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
            this.channelId = channel.id();
            this.conversationId = sourceMessage.externalConversationId();
            this.text = text;
            return ChannelSendResult.sent("sent", Map.of());
        }
    }
}
