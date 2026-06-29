package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelAdapterRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void customAdapterCanDriveInboundOutboundConnectivityAndStream() {
        ChannelAdapterRegistry registry = new ChannelAdapterRegistry(List.of(new WorkchatAdapter()));
        ChannelDefinition channel = new ChannelDefinition("corp-workchat", "企业 IM", "workchat",
                true, "ask", List.of(), "/workchat/inbound", Map.of(), null, null);

        ChannelInboundPayloadResult inbound = ChannelInboundPayloadAdapter.adaptWithResponse(registry, channel, channel.id(), Map.of(
                "conversation", "room-1",
                "user", "user-1",
                "body", "hello"
        ));
        ChannelOutboundClient outboundClient = new ChannelOutboundClient(registry);
        ChannelStreamClientManager streamManager = new ChannelStreamClientManager(registry, true);

        assertEquals("workchat", registry.find("workchat").orElseThrow().type());
        assertEquals("corp-workchat", inbound.message().channelId());
        assertEquals("hello", inbound.message().text());
        assertTrue(outboundClient.sendText(channel, inbound.message(), "answer"));
        assertEquals("ready", outboundClient.checkConnectivity(channel).status());
        assertEquals("running", streamManager.start(channel).status());
        assertEquals("stopped", streamManager.stop(channel).status());
        ChannelAdapterDescriptor descriptor = registry.adapters().stream()
                .filter(adapter -> "workchat".equals(adapter.type()))
                .findFirst()
                .orElseThrow();
        assertTrue(descriptor.active());
        assertEquals("custom", descriptor.source());
    }

    @Test
    void loadsRuntimeAdaptersFromConfiguredJarDirectory() throws Exception {
        Path adapterDir = tempDir.resolve("adapters");
        Files.createDirectories(adapterDir);
        Path jar = adapterDir.resolve("workchat-adapter.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/" + ChannelRuntimeAdapter.class.getName()));
            out.write(JarLoadedChannelAdapter.class.getName().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        try (ChannelAdapterRegistry registry = ChannelAdapterRegistry.builtin(null, List.of(adapterDir))) {
            ChannelDefinition channel = new ChannelDefinition("corp-jar", "企业 IM Jar", "jar-workchat",
                    true, "ask", List.of(), "/jar/inbound", Map.of(), null, null);
            ChannelInboundPayloadResult inbound = ChannelInboundPayloadAdapter.adaptWithResponse(registry, channel, channel.id(), Map.of());
            ChannelOutboundClient outbound = new ChannelOutboundClient(registry);

            assertEquals("jar-workchat", registry.find("jar-workchat").orElseThrow().type());
            ChannelAdapterDescriptor descriptor = registry.adapters().stream()
                    .filter(adapter -> "jar-workchat".equals(adapter.type()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(descriptor.active());
            assertEquals("external-jar", descriptor.source());
            assertEquals("loaded-from-jar", inbound.message().text());
            assertTrue(outbound.sendText(channel, inbound.message(), "answer"));
        }
    }

    private static class WorkchatAdapter implements ChannelRuntimeAdapter {
        @Override
        public String type() {
            return "workchat";
        }

        @Override
        public ChannelInboundPayloadResult adaptInbound(ChannelDefinition channel, String channelId, Map<String, Object> rawPayload) {
            return ChannelInboundPayloadResult.message(new ChannelInboundMessage(
                    channelId,
                    stringValue(rawPayload.get("conversation")),
                    stringValue(rawPayload.get("user")),
                    "text",
                    stringValue(rawPayload.get("body")),
                    Map.of("channel.adapter", "workchat"),
                    rawPayload));
        }

        @Override
        public ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
            return ChannelSendResult.sent("sent", Map.of("platform", "workchat"));
        }

        @Override
        public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
            return ChannelConnectivityStatus.ready(channel.id(), type(), false, "ready", Map.of());
        }

        @Override
        public ChannelStreamHandle startStream(ChannelDefinition channel) {
            return new ChannelStreamHandle("workchat-stream", this, () -> {
            });
        }
    }
}
