package com.github.clawagent.server.controller;

import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelAdapterReloadResult;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.spi.ChannelRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void resolveInboundChannelPrefersYamlDefaultAccountForPlatformTypePath() {
        ChannelDefinition builtin = channel("feishu", "feishu", false, Map.of("builtin", "true"));
        ChannelDefinition yamlAccount = channel("feishu-main", "feishu", true, Map.of("channel.isDefaultAccount", "true"));
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of(builtin, yamlAccount)),
                null, null, null, null, null, null);

        ChannelDefinition resolved = controller.resolveInboundChannel("feishu").orElseThrow();

        assertEquals("feishu-main", resolved.id());
    }

    @Test
    void resolveInboundChannelFallsBackToFirstEnabledPlatformAccountWhenNoDefaultIsMarked() {
        ChannelDefinition builtin = channel("dingtalk", "dingtalk", false, Map.of("builtin", "true"));
        ChannelDefinition yamlAccount = channel("dingtalk-main", "dingtalk", true, Map.of("channel.accountId", "main"));
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of(builtin, yamlAccount)),
                null, null, null, null, null, null);

        ChannelDefinition resolved = controller.resolveInboundChannel("dingtalk").orElseThrow();

        assertEquals("dingtalk-main", resolved.id());
    }

    @Test
    void resolveInboundChannelKeepsExactEnabledChannelId() {
        ChannelDefinition exact = channel("feishu", "feishu", true, Map.of("builtin", "true"));
        ChannelDefinition yamlAccount = channel("feishu-main", "feishu", true, Map.of("channel.isDefaultAccount", "true"));
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of(exact, yamlAccount)),
                null, null, null, null, null, null);

        ChannelDefinition resolved = controller.resolveInboundChannel("feishu").orElseThrow();

        assertEquals("feishu", resolved.id());
    }

    @Test
    void channelAdaptersReturnsRuntimeAdapterDiagnostics() {
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of()),
                new ChannelAdapterRegistry(List.of(new TestRuntimeAdapter())), null, null, null, null, null);

        var adapters = controller.channelAdapters();

        assertEquals(1, adapters.size());
        assertEquals("testim", adapters.get(0).type());
        assertEquals("custom", adapters.get(0).source());
        assertTrue(adapters.get(0).active());
    }

    @Test
    void reloadChannelAdaptersReturnsRefreshedDiagnostics() {
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of()),
                new ChannelAdapterRegistry(List.of(new TestRuntimeAdapter())), null, null, null, null, null);

        var result = controller.reloadChannelAdapters();

        assertEquals(1, result.candidateCount());
        assertEquals(1, result.activeCount());
        assertEquals("testim", result.adapters().get(0).type());
    }

    @Test
    void uploadChannelAdapterStoresJarAndReloadsAdapters() throws Exception {
        ClawAgentProperties properties = new ClawAgentProperties();
        properties.getChannels().setAdapterPath(List.of(tempDir.toString()));
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of()),
                new ChannelAdapterRegistry(List.of(new TestRuntimeAdapter())), null, null, null, null, properties);

        ChannelAdapterReloadResult result = controller.uploadChannelAdapter(new TestMultipartFile(
                "custom-adapter.jar", new byte[]{0x50, 0x4b, 0x03, 0x04}));

        assertTrue(Files.exists(tempDir.resolve("custom-adapter.jar")));
        assertEquals(1, result.activeCount());
    }

    @Test
    void uploadChannelAdapterRejectsNonJarFile() {
        ClawAgentProperties properties = new ClawAgentProperties();
        properties.getChannels().setAdapterPath(List.of(tempDir.toString()));
        ChannelController controller = new ChannelController(new FixedChannelRegistry(List.of()),
                new ChannelAdapterRegistry(List.of(new TestRuntimeAdapter())), null, null, null, null, properties);

        assertThrows(IllegalArgumentException.class, () -> controller.uploadChannelAdapter(
                new TestMultipartFile("custom-adapter.txt", new byte[]{1})));
    }

    private static ChannelDefinition channel(String id, String type, boolean enabled, Map<String, String> metadata) {
        return new ChannelDefinition(id, id, type, enabled, "ask", List.of(),
                "/api/v1/channels/" + id + "/inbound", metadata, Instant.EPOCH, Instant.EPOCH);
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

    private static class TestRuntimeAdapter implements ChannelRuntimeAdapter {
        @Override
        public String type() {
            return "testim";
        }
    }

    private static class TestMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final byte[] content;

        private TestMultipartFile(String originalFilename, byte[] content) {
            this.originalFilename = originalFilename;
            this.content = content;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "application/java-archive";
        }

        @Override
        public boolean isEmpty() {
            return content.length == 0;
        }

        @Override
        public long getSize() {
            return content.length;
        }

        @Override
        public byte[] getBytes() {
            return content;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            Files.write(dest.toPath(), content);
        }
    }
}
