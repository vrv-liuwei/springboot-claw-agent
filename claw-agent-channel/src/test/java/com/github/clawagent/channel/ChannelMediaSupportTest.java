package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelMediaSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void skipsDownloadWhenDisabled() {
        ChannelDefinition channel = channel(Map.of("mediaDownloadEnabled", "false"));

        Map<String, String> result = ChannelMediaSupport.download(channel, "test", "file",
                "https://example.invalid/file.txt", Map.of(), "channel", "message", "file", "file.txt", 1000);

        assertEquals("disabled", result.get("downloadStatus"));
        assertFalse(result.containsKey("localPath"));
    }

    @Test
    void rejectsNonHttpMediaUrl() {
        ChannelDefinition channel = channel(Map.of("mediaDownloadDir", tempDir.toString()));

        Map<String, String> result = ChannelMediaSupport.download(channel, "test", "file",
                "file:///C:/Windows/win.ini", Map.of(), "channel", "message", "file", "file.txt", 1000);

        assertEquals("skipped", result.get("downloadStatus"));
        assertEquals("unsupported-url-scheme", result.get("downloadReason"));
        assertFalse(result.containsKey("localPath"));
    }

    @Test
    void skipsFileWhenResponseExceedsConfiguredLimit() throws Exception {
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ChannelDefinition channel = channel(Map.of(
                    "mediaDownloadDir", tempDir.toString(),
                    "mediaMaxBytes", "4"));

            Map<String, String> result = ChannelMediaSupport.download(channel, "test", "file",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
                    Map.of(), "channel", "message", "file", "file.txt", 1000);

            assertEquals("skipped", result.get("downloadStatus"));
            assertEquals("max-bytes-exceeded", result.get("downloadReason"));
            assertEquals("10", result.get("sizeBytes"));
            assertFalse(result.containsKey("localPath"));
        } finally {
            // 测试内的轻量 HTTP 服务只用于模拟附件响应，结束后立即释放端口。
            server.stop(0);
        }
    }

    @Test
    void downloadsFileWithinConfiguredLimit() throws Exception {
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            ChannelDefinition channel = channel(Map.of(
                    "mediaDownloadDir", tempDir.toString(),
                    "mediaMaxBytes", "16"));

            Map<String, String> result = ChannelMediaSupport.download(channel, "test", "file",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/file",
                    Map.of(), "channel", "message", "file", "file.txt", 1000);

            assertEquals("downloaded", result.get("downloadStatus"));
            assertEquals("2", result.get("sizeBytes"));
            assertTrue(Path.of(result.get("localPath")).toFile().isFile());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void writesSearchableAttachmentIndexMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();

        ChannelMediaSupport.putAttachmentsMetadata(metadata, List.of(
                ChannelMediaSupport.attachment("feishu", "image", Map.of(
                        "downloadStatus", "downloaded",
                        "fileName", "image.png",
                        "platformKey", "img-key")),
                ChannelMediaSupport.attachment("feishu", "file", Map.of(
                        "downloadStatus", "skipped",
                        "downloadReason", "missing-url",
                        "fileName", "report.pdf",
                        "platformKey", "file-key")),
                ChannelMediaSupport.attachment("feishu", "card", Map.of(
                        "renderStatus", "rendered",
                        "renderFormat", "markdown",
                        "renderText", "### 审批卡片"))));

        assertTrue(metadata.get(ChannelMediaSupport.ATTACHMENTS_KEY).contains("\"type\":\"image\""));
        assertEquals("3", metadata.get("channel.attachmentCount"));
        assertEquals("true", metadata.get("channel.hasAttachments"));
        assertEquals("2", metadata.get("channel.mediaAttachmentCount"));
        assertEquals("1", metadata.get("channel.richAttachmentCount"));
        assertEquals("1", metadata.get("channel.downloadedAttachmentCount"));
        assertEquals("0", metadata.get("channel.failedAttachmentCount"));
        assertEquals("image,file,card", metadata.get("channel.attachmentTypes"));
        assertEquals("feishu", metadata.get("channel.attachmentSources"));
        assertEquals("downloaded,skipped", metadata.get("channel.attachmentDownloadStatuses"));
        assertEquals("image.png,report.pdf", metadata.get("channel.attachmentFileNames"));
        assertEquals("img-key,file-key", metadata.get("channel.attachmentPlatformKeys"));
        assertEquals("rendered", metadata.get("channel.richRenderStatuses"));
        assertEquals("markdown", metadata.get("channel.richRenderFormats"));
        assertEquals("image", metadata.get("channel.attachment.0.type"));
        assertEquals("downloaded", metadata.get("channel.attachment.0.downloadStatus"));
        assertEquals("file-key", metadata.get("channel.attachment.1.platformKey"));
        assertEquals("missing-url", metadata.get("channel.attachment.1.downloadReason"));
        assertEquals("rendered", metadata.get("channel.attachment.2.renderStatus"));
        assertEquals("markdown", metadata.get("channel.attachment.2.renderFormat"));
        assertEquals("### 审批卡片", metadata.get("channel.attachment.2.renderText"));
    }

    @Test
    void ignoresEmptyAttachmentIndexMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();

        ChannelMediaSupport.putAttachmentsMetadata(metadata, List.of());

        assertFalse(metadata.containsKey(ChannelMediaSupport.ATTACHMENTS_KEY));
        assertFalse(metadata.containsKey("channel.attachmentCount"));
    }

    private ChannelDefinition channel(Map<String, String> metadata) {
        return new ChannelDefinition("channel", "Test Channel", "test", true, "ask",
                List.of(), "/webhook", metadata, Instant.now(), Instant.now());
    }
}
