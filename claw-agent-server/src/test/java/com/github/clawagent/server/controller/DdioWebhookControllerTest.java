package com.github.clawagent.server.controller;

import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.spi.ChannelRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DdioWebhookControllerTest {

    @Test
    void resolveDdioChannelPrefersEnabledYamlAccountOverBuiltinPlaceholder() {
        ChannelDefinition builtin = channel("ddio", "ddio", false, Map.of("builtin", "true"));
        ChannelDefinition yamlAccount = channel("ddio-main", "ddio", true, Map.of("channel.isDefaultAccount", "true"));
        DdioWebhookController controller = new DdioWebhookController(new FixedChannelRegistry(List.of(builtin, yamlAccount)), null);

        ChannelDefinition resolved = controller.resolveDdioChannel();

        assertEquals("ddio-main", resolved.id());
    }

    @Test
    void resolveDdioChannelFallsBackToExactDdioWhenItIsEnabled() {
        ChannelDefinition exact = channel("ddio", "ddio", true, Map.of("builtin", "true"));
        ChannelDefinition yamlAccount = channel("ddio-main", "ddio", true, Map.of("channel.isDefaultAccount", "true"));
        DdioWebhookController controller = new DdioWebhookController(new FixedChannelRegistry(List.of(exact, yamlAccount)), null);

        ChannelDefinition resolved = controller.resolveDdioChannel();

        assertEquals("ddio", resolved.id());
    }

    private static ChannelDefinition channel(String id, String type, boolean enabled, Map<String, String> metadata) {
        return new ChannelDefinition(id, id, type, enabled, "ask", List.of(),
                "/ddio/message", metadata, Instant.EPOCH, Instant.EPOCH);
    }

    private static class FixedChannelRegistry implements ChannelRegistry {
        private final List<ChannelDefinition> channels;

        private FixedChannelRegistry(List<ChannelDefinition> channels) {
            this.channels = channels;
        }

        @Override
        public List<ChannelDefinition> list() {
            return channels;
        }

        @Override
        public Optional<ChannelDefinition> find(String channelId) {
            return channels.stream().filter(channel -> channel.id().equals(channelId)).findFirst();
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
}
