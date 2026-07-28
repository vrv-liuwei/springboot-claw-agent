package com.github.clawagent.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ChannelMediaSupport 只处理入站媒体的本地缓存和 metadata 标准化。
 * 平台鉴权、资源 URL 选择仍由具体 adapter 负责，避免通用层写死 IM 平台协议。
 */
public final class ChannelMediaSupport {
    public static final String ATTACHMENTS_KEY = "attachments";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_MEDIA_DIR = ".clawagent/channels/media";
    private static final long DEFAULT_MAX_BYTES = 20L * 1024 * 1024;
    private static final int DEFAULT_DOWNLOAD_TIMEOUT_MS = 30000;

    private ChannelMediaSupport() {
    }

    public static boolean downloadEnabled(ChannelDefinition channel) {
        String value = metadataValue(channel, "mediaDownloadEnabled", "mediaDownloadEnabledEnv", "true");
        return !"false".equalsIgnoreCase(value) && !"0".equals(value);
    }

    public static Map<String, String> attachment(String source, String type, Map<String, String> values) {
        Map<String, String> attachment = new LinkedHashMap<>();
        attachment.put("source", safeValue(source, "channel"));
        attachment.put("type", safeValue(type, "file"));
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()) {
                    attachment.put(key, value);
                }
            });
        }
        return attachment;
    }

    public static Map<String, String> download(
            ChannelDefinition channel,
            String source,
            String type,
            String url,
            Map<String, String> headers,
            String channelId,
            String messageId,
            String platformKey,
            String fileName,
            int timeoutMs) {
        Map<String, String> result = attachment(source, type, Map.of(
                "platformKey", safeValue(platformKey, ""),
                "fileName", safeValue(fileName, "")));
        if (!downloadEnabled(channel)) {
            result.put("downloadStatus", "disabled");
            return result;
        }
        if (url == null || url.isBlank()) {
            result.put("downloadStatus", "skipped");
            result.put("downloadReason", "missing-url");
            return result;
        }
        if (!isHttpUrl(url)) {
            result.put("downloadStatus", "skipped");
            result.put("downloadReason", "unsupported-url-scheme");
            return result;
        }
        try {
            Path target = targetPath(channel, channelId, messageId, platformKey, fileName, type);
            int effectiveTimeoutMs = downloadTimeoutMs(channel, timeoutMs);
            // 下载失败不影响消息入站，具体错误通过 attachment metadata 交给审计和模型上下文。
            AgentHttpResponse response = AgentHttpClient.get(url, headers == null ? Map.of() : headers, effectiveTimeoutMs);
            if (!response.is2xx()) {
                result.put("downloadStatus", "failed");
                result.put("downloadReason", "http-" + response.statusCode());
                return result;
            }
            int bytes = response.bodyBytes() == null ? 0 : response.bodyBytes().length;
            long maxBytes = maxDownloadBytes(channel);
            if (maxBytes > 0 && bytes > maxBytes) {
                result.put("downloadStatus", "skipped");
                result.put("downloadReason", "max-bytes-exceeded");
                result.put("sizeBytes", String.valueOf(bytes));
                result.put("maxBytes", String.valueOf(maxBytes));
                return result;
            }
            Files.write(target, response.bodyBytes() == null ? new byte[0] : response.bodyBytes());
            result.put("downloadStatus", "downloaded");
            result.put("localPath", target.toAbsolutePath().normalize().toString());
            result.put("contentType", safeValue(response.contentType(), ""));
            result.put("sizeBytes", String.valueOf(bytes));
            result.put("downloadHost", safeHost(url));
            return result;
        } catch (Exception e) {
            result.put("downloadStatus", "failed");
            result.put("downloadReason", e.getClass().getSimpleName());
            result.put("downloadError", safeValue(e.getMessage(), "download failed"));
            return result;
        }
    }

    public static String attachmentsJson(List<Map<String, String>> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return "";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(new ArrayList<>(attachments));
        } catch (Exception ignored) {
            return "[]";
        }
    }

    public static void putAttachmentsMetadata(Map<String, String> metadata, List<Map<String, String>> attachments) {
        String attachmentJson = attachmentsJson(attachments);
        if (metadata == null || attachmentJson.isBlank()) {
            return;
        }
        metadata.put(ATTACHMENTS_KEY, attachmentJson);
        metadata.put("channel.attachmentCount", String.valueOf(attachments.size()));
        // 附件 JSON 供详情审查，轻量索引字段供跨任务检索、权限策略和审计列表直接过滤。
        metadata.put("channel.hasAttachments", "true");
        metadata.put("channel.mediaAttachmentCount", String.valueOf(countAttachments(attachments, Set.of("image", "file", "audio", "video", "media"))));
        metadata.put("channel.richAttachmentCount", String.valueOf(countAttachments(attachments, Set.of("markdown", "card", "post", "rich_text", "interactive"))));
        metadata.put("channel.downloadedAttachmentCount", String.valueOf(countByValue(attachments, "downloadStatus", "downloaded")));
        metadata.put("channel.failedAttachmentCount", String.valueOf(countByValue(attachments, "downloadStatus", "failed")));
        putJoined(metadata, "channel.attachmentTypes", collectValues(attachments, "type"));
        putJoined(metadata, "channel.attachmentSources", collectValues(attachments, "source"));
        putJoined(metadata, "channel.attachmentDownloadStatuses", collectValues(attachments, "downloadStatus"));
        putJoined(metadata, "channel.attachmentFileNames", collectValues(attachments, "fileName"));
        putJoined(metadata, "channel.attachmentPlatformKeys", collectValues(attachments, "platformKey"));
        putJoined(metadata, "channel.richRenderStatuses", collectValues(attachments, "renderStatus"));
        putJoined(metadata, "channel.richRenderFormats", collectValues(attachments, "renderFormat"));
        putAttachmentIndexes(metadata, attachments);
    }

    private static void putAttachmentIndexes(Map<String, String> metadata, List<Map<String, String>> attachments) {
        if (metadata == null || attachments == null || attachments.isEmpty()) {
            return;
        }
        for (int index = 0; index < attachments.size(); index++) {
            Map<String, String> attachment = attachments.get(index);
            if (attachment == null || attachment.isEmpty()) {
                continue;
            }
            String prefix = "channel.attachment." + index + ".";
            // 逐项索引用于 UI、审计和模型按序定位附件；完整详情仍以 attachments JSON 为准。
            putIfPresent(metadata, prefix + "source", attachment.get("source"));
            putIfPresent(metadata, prefix + "type", attachment.get("type"));
            putIfPresent(metadata, prefix + "platformKey", attachment.get("platformKey"));
            putIfPresent(metadata, prefix + "fileName", attachment.get("fileName"));
            putIfPresent(metadata, prefix + "localPath", attachment.get("localPath"));
            putIfPresent(metadata, prefix + "downloadStatus", attachment.get("downloadStatus"));
            putIfPresent(metadata, prefix + "downloadReason", attachment.get("downloadReason"));
            putIfPresent(metadata, prefix + "contentType", attachment.get("contentType"));
            putIfPresent(metadata, prefix + "sizeBytes", attachment.get("sizeBytes"));
            putIfPresent(metadata, prefix + "renderStatus", attachment.get("renderStatus"));
            putIfPresent(metadata, prefix + "renderFormat", attachment.get("renderFormat"));
            putIfPresent(metadata, prefix + "renderText", attachment.get("renderText"));
        }
    }

    private static Path targetPath(ChannelDefinition channel, String channelId, String messageId,
                                   String platformKey, String fileName, String type) throws Exception {
        Path root = Path.of(metadataValue(channel, "mediaDownloadDir", "mediaDownloadDirEnv", DEFAULT_MEDIA_DIR));
        Path dir = root.resolve(safeFileName(safeValue(channelId, "channel")))
                .resolve(safeFileName(safeValue(messageId, "message")));
        Files.createDirectories(dir);
        String fallback = safeValue(platformKey, type + "-" + System.currentTimeMillis());
        return dir.resolve(safeFileName(firstNonBlank(fileName, fallback)));
    }

    private static boolean isHttpUrl(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long maxDownloadBytes(ChannelDefinition channel) {
        return longMetadataValue(channel, "mediaMaxBytes", "mediaMaxBytesEnv", DEFAULT_MAX_BYTES);
    }

    private static int downloadTimeoutMs(ChannelDefinition channel, int timeoutMs) {
        long configured = longMetadataValue(channel, "mediaDownloadTimeoutMs", "mediaDownloadTimeoutMsEnv", timeoutMs);
        return configured <= 0 ? DEFAULT_DOWNLOAD_TIMEOUT_MS : (int) Math.min(configured, Integer.MAX_VALUE);
    }

    private static long longMetadataValue(ChannelDefinition channel, String directKey, String envKey, long fallback) {
        String value = metadataValue(channel, directKey, envKey, String.valueOf(fallback));
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String metadataValue(ChannelDefinition channel, String directKey, String envKey, String fallback) {
        if (channel == null || channel.metadata() == null) {
            return fallback;
        }
        String direct = safeValue(channel.metadata().get(directKey), "");
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = safeValue(channel.metadata().get(envKey), "");
        String envValue = envName.isBlank() ? "" : safeValue(System.getenv(envName), "");
        return envValue.isBlank() ? fallback : envValue;
    }

    private static String safeFileName(String value) {
        String normalized = safeValue(value, "file").replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private static String safeHost(String url) {
        try {
            URI uri = URI.create(url);
            return safeValue(uri.getHost(), "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? safeValue(second, "") : first.trim();
    }

    private static Set<String> collectValues(List<Map<String, String>> attachments, String key) {
        Set<String> values = new LinkedHashSet<>();
        if (attachments == null || key == null) {
            return values;
        }
        for (Map<String, String> attachment : attachments) {
            String value = attachment == null ? "" : safeValue(attachment.get(key), "");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }

    private static long countAttachments(List<Map<String, String>> attachments, Set<String> types) {
        if (attachments == null || types == null || types.isEmpty()) {
            return 0;
        }
        return attachments.stream()
                .filter(attachment -> types.contains(safeValue(attachment == null ? "" : attachment.get("type"), "").toLowerCase()))
                .count();
    }

    private static long countByValue(List<Map<String, String>> attachments, String key, String expected) {
        if (attachments == null || key == null || expected == null) {
            return 0;
        }
        return attachments.stream()
                .filter(attachment -> expected.equalsIgnoreCase(safeValue(attachment == null ? "" : attachment.get(key), "")))
                .count();
    }

    private static void putJoined(Map<String, String> metadata, String key, Set<String> values) {
        if (metadata != null && key != null && values != null && !values.isEmpty()) {
            metadata.put(key, String.join(",", values));
        }
    }

    private static void putIfPresent(Map<String, String> metadata, String key, String value) {
        if (metadata != null && key != null && value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private static String safeValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
