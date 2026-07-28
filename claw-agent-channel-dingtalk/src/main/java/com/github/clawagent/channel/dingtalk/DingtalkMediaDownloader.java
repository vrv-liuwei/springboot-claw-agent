package com.github.clawagent.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelMediaSupport;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉机器人媒体下载辅助。
 * downloadCode 需要先调用平台接口换取临时 downloadUrl，再复用通用媒体缓存逻辑。
 */
final class DingtalkMediaDownloader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_DOWNLOAD_URL_API = "https://api.dingtalk.com/v1.0/robot/messageFiles/download";

    private DingtalkMediaDownloader() {
    }

    static Map<String, String> downloadCode(ChannelDefinition channel,
                                            String type,
                                            String downloadCode,
                                            String fileName,
                                            String channelId,
                                            String messageId) {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("downloadCode", safeValue(downloadCode, ""));
        base.put("fileName", safeValue(fileName, defaultFileName(type)));
        if (!ChannelMediaSupport.downloadEnabled(channel)) {
            base.put("downloadStatus", "disabled");
            return ChannelMediaSupport.attachment("dingtalk", type, base);
        }
        if (downloadCode == null || downloadCode.isBlank()) {
            base.put("downloadStatus", "skipped");
            base.put("downloadReason", "missing-download-code");
            return ChannelMediaSupport.attachment("dingtalk", type, base);
        }
        String accessToken = metadataValue(channel, "accessToken", "accessTokenEnv");
        String robotCode = firstNonBlank(
                metadataValue(channel, "robotCode", "robotCodeEnv"),
                metadataValue(channel, "clientId", "clientIdEnv"),
                metadataValue(channel, "appKey", "appKeyEnv"),
                metadataValue(channel, "appId", "appIdEnv"));
        if (accessToken.isBlank() || robotCode.isBlank()) {
            base.put("downloadStatus", "skipped");
            base.put("downloadReason", missingReason(accessToken, robotCode));
            return ChannelMediaSupport.attachment("dingtalk", type, base);
        }
        try {
            String downloadUrl = resolveDownloadUrl(channel, accessToken, robotCode, downloadCode);
            if (downloadUrl.isBlank()) {
                base.put("downloadStatus", "failed");
                base.put("downloadReason", "missing-download-url");
                return ChannelMediaSupport.attachment("dingtalk", type, base);
            }
            Map<String, String> attachment = ChannelMediaSupport.download(channel, "dingtalk", type, downloadUrl,
                    Map.of(), channelId, messageId, downloadCode, fileName, 30000);
            attachment.put("downloadCode", downloadCode);
            attachment.put("robotCode", mask(robotCode));
            return attachment;
        } catch (Exception e) {
            base.put("downloadStatus", "failed");
            base.put("downloadReason", e.getClass().getSimpleName());
            base.put("downloadError", safeValue(e.getMessage(), "downloadCode resolve failed"));
            return ChannelMediaSupport.attachment("dingtalk", type, base);
        }
    }

    private static String resolveDownloadUrl(ChannelDefinition channel, String accessToken,
                                             String robotCode, String downloadCode) throws Exception {
        String api = firstNonBlank(
                metadataValue(channel, "downloadCodeApiUrl", "downloadCodeApiUrlEnv"),
                DEFAULT_DOWNLOAD_URL_API);
        String body = OBJECT_MAPPER.writeValueAsString(Map.of(
                "downloadCode", downloadCode,
                "robotCode", robotCode));
        // 钉钉官方接口要求把 access token 放在 x-acs-dingtalk-access-token 头里。
        AgentHttpResponse response = AgentHttpClient.postJson(api, body, Map.of(
                "x-acs-dingtalk-access-token", accessToken), 30000);
        if (!response.is2xx()) {
            throw new IllegalStateException("钉钉 downloadCode 换取下载地址失败：http-" + response.statusCode());
        }
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        String url = firstNonBlank(
                textAt(root, "downloadUrl"),
                textAt(root, "url"),
                textAt(root, "result", "downloadUrl"),
                textAt(root, "result", "url"));
        if (url.isBlank()) {
            throw new IllegalStateException("钉钉 downloadCode 响应缺少 downloadUrl");
        }
        return url;
    }

    private static String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
        if (channel == null || channel.metadata() == null) {
            return "";
        }
        String direct = safeValue(channel.metadata().get(directKey), "");
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = safeValue(channel.metadata().get(envKey), "");
        return envName.isBlank() ? "" : safeValue(System.getenv(envName), "");
    }

    private static String textAt(JsonNode root, String... path) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return "";
        }
        JsonNode current = root;
        for (String segment : path) {
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return "";
            }
        }
        return current.isValueNode() ? current.asText("") : "";
    }

    private static String missingReason(String accessToken, String robotCode) {
        if (accessToken == null || accessToken.isBlank()) {
            return "missing-access-token";
        }
        return robotCode == null || robotCode.isBlank() ? "missing-robot-code" : "missing-credential";
    }

    private static String defaultFileName(String type) {
        return "image".equalsIgnoreCase(type) ? "dingtalk-image" : "dingtalk-file";
    }

    private static String mask(String value) {
        String text = safeValue(value, "");
        return text.length() <= 8 ? "********" : text.substring(0, 4) + "****" + text.substring(text.length() - 4);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String safeValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }
}
