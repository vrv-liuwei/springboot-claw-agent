package com.github.clawagent.channel.dingtalk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.channel.ChannelConnectivityStatus;
import com.github.clawagent.channel.ChannelSendResult;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.core.http.AgentHttpClient;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpRequest;
import com.github.clawagent.core.http.AgentHttpClient.AgentHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉自定义机器人 HTTP 出站实现。
 */
public class DingtalkOutboundClient {
    private static final Logger log = LoggerFactory.getLogger(DingtalkOutboundClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DingtalkOutboundClient() {
    }

    public ChannelConnectivityStatus checkConnectivity(ChannelDefinition channel) {
        String webhookUrl = metadataValue(channel, "webhookUrl", "webhookUrlEnv");
        if (webhookUrl.isBlank() && isStreamMode(channel)) {
            return checkStreamConnectivity(channel);
        }
        List<String> missingKeys = missingMetadata("webhookUrl/webhookUrlEnv", webhookUrl);
        if (!missingKeys.isEmpty()) {
            return ChannelConnectivityStatus.incomplete(stringValue(channel.id()), safeType(channel), missingKeys,
                    "钉钉自定义机器人出站回写需要 Webhook URL。", Map.of("protocol", "dingtalk-custom-robot"));
        }
        String secret = metadataValue(channel, "secret", "secretEnv");
        try {
            String signedUrl = appendSignature(webhookUrl, secret);
            return ChannelConnectivityStatus.ready(stringValue(channel.id()), safeType(channel), false,
                    "钉钉 Webhook 配置格式可用；检测未发送消息，避免产生测试噪声。",
                    Map.of("protocol", "dingtalk-custom-robot", "signed", String.valueOf(!signedUrl.equals(webhookUrl))));
        } catch (Exception e) {
            return ChannelConnectivityStatus.failed(stringValue(channel.id()), safeType(channel),
                    safeError("钉钉签名检查失败", e), Map.of("protocol", "dingtalk-custom-robot"));
        }
    }

    private ChannelConnectivityStatus checkStreamConnectivity(ChannelDefinition channel) {
        String clientId = firstNonBlank(
                metadataValue(channel, "clientId", "clientIdEnv"),
                metadataValue(channel, "appKey", "appKeyEnv"),
                metadataValue(channel, "appId", "appIdEnv"));
        String clientSecret = firstNonBlank(
                metadataValue(channel, "clientSecret", "clientSecretEnv"),
                metadataValue(channel, "appSecret", "appSecretEnv"));
        List<String> missingKeys = missingMetadata("clientId/clientIdEnv 或 appKey/appKeyEnv 或 appId/appIdEnv", clientId,
                "clientSecret/clientSecretEnv 或 appSecret/appSecretEnv", clientSecret);
        if (!missingKeys.isEmpty()) {
            return ChannelConnectivityStatus.incomplete(stringValue(channel.id()), safeType(channel), missingKeys,
                    "钉钉 Stream 需要客户端 ID 和客户端密钥。", Map.of("protocol", "dingtalk-stream"));
        }
        // Stream 模式不在 health 中主动连平台，避免健康检查启动常驻连接；真实连接由 stream/start 验证。
        return ChannelConnectivityStatus.ready(stringValue(channel.id()), safeType(channel), false,
                "钉钉 Stream 凭证已配置；请通过 Stream 启动验证长连接。",
                Map.of("protocol", "dingtalk-stream", "mode", metadataValue(channel, "connectionMode", "connectionModeEnv")));
    }

    public ChannelSendResult sendText(ChannelDefinition channel, String text) {
        return sendText(channel, null, text);
    }

    public ChannelSendResult sendText(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        String webhookUrl = metadataValue(channel, "webhookUrl", "webhookUrlEnv");
        if (webhookUrl.isBlank() && isStreamMode(channel)) {
            return sendBySessionWebhook(channel, sourceMessage, text);
        }
        if (webhookUrl.isBlank()) {
            return ChannelSendResult.failed("钉钉出站需要 webhookUrl 或 webhookUrlEnv。",
                    Map.of("protocol", "dingtalk-custom-robot", "reason", "missing-webhook"));
        }
        String secret = metadataValue(channel, "secret", "secretEnv");
        String targetUrl = appendSignature(webhookUrl, secret);
        try {
            String messageType = outboundMessageType(channel);
            Map<String, Object> body = shouldUseMarkdown(messageType, text)
                    ? markdownBody(channel, text)
                    : Map.of("msgtype", "text", "text", Map.of("content", text));
            AgentHttpResponse response = AgentHttpClient.execute(jsonPost(targetUrl, body));
            Map<String, String> details = httpDetails("dingtalk-custom-robot", messageType, response);
            return isSuccess(response)
                    ? ChannelSendResult.sent("钉钉消息已提交。", details)
                    : ChannelSendResult.failed("钉钉平台返回失败。", details);
        } catch (Exception e) {
            throw new IllegalStateException("发送钉钉文本消息失败", e);
        }
    }

    private ChannelSendResult sendBySessionWebhook(ChannelDefinition channel, ChannelInboundMessage sourceMessage, String text) {
        String sessionWebhook = firstNonBlank(
                metadataValue(sourceMessage == null ? null : sourceMessage.metadata(), "dingtalk.sessionWebhook", "dingtalk.sessionWebhookEnv"),
                metadataValue(sourceMessage == null ? null : sourceMessage.metadata(), "sessionWebhook", "sessionWebhookEnv"));
        if (sessionWebhook.isBlank()) {
            return ChannelSendResult.failed("钉钉 Stream 出站需要回调事件里的 sessionWebhook，当前入站消息未携带该字段。",
                    Map.of("protocol", "dingtalk-stream", "reason", "missing-session-webhook"));
        }
        try {
            String messageType = outboundMessageType(channel);
            Map<String, Object> body = shouldUseMarkdown(messageType, text)
                    ? markdownBody(channel, text)
                    : Map.of("msgtype", "text", "text", Map.of("content", text));
            log.info("dingtalk session webhook send channelId={} messageType={} textLength={}",
                    channel == null ? "" : channel.id(), body.get("msgtype"), stringValue(text).length());
            AgentHttpResponse response = AgentHttpClient.execute(jsonPost(sessionWebhook, body));
            Map<String, String> details = httpDetails("dingtalk-session-webhook", stringValue(body.get("msgtype")), response);
            return isSuccess(response)
                    ? ChannelSendResult.sent("钉钉 Stream 会话消息已提交。", details)
                    : ChannelSendResult.failed("钉钉 Stream 会话回写失败。", details);
        } catch (Exception e) {
            throw new IllegalStateException("发送钉钉 Stream 会话消息失败", e);
        }
    }

    private Map<String, Object> markdownBody(ChannelDefinition channel, String text) {
        String title = firstNonBlank(metadataValue(channel, "markdownTitle", "markdownTitleEnv"), "ClawAgent");
        return Map.of(
                "msgtype", "markdown",
                "markdown", Map.of("title", title, "text", text));
    }

    private String appendSignature(String webhookUrl, String secret) {
        Map<String, String> signed = DingtalkInboundAdapter.signedQuery(secret, System.currentTimeMillis());
        if (signed.isEmpty()) {
            return webhookUrl;
        }
        String separator = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + separator + "timestamp=" + signed.get("timestamp") + "&sign=" + signed.get("sign");
    }

    private AgentHttpRequest jsonPost(String url, Object body) throws Exception {
        return AgentHttpRequest.post(url)
                .timeoutMs(20_000)
                .contentType("application/json; charset=utf-8")
                .body(objectMapper.writeValueAsString(body));
    }

    private boolean isSuccess(AgentHttpResponse response) {
        if (!response.is2xx()) {
            return false;
        }
        try {
            Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
            Object errcode = body.get("errcode");
            if (errcode != null) {
                return "0".equals(String.valueOf(errcode));
            }
            Object code = body.get("code");
            return code == null || "0".equals(String.valueOf(code));
        } catch (Exception ignored) {
            return true;
        }
    }

    private Map<String, String> httpDetails(String protocol, String messageType, AgentHttpResponse response) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("protocol", protocol);
        details.put("messageType", messageType);
        details.put("httpStatus", String.valueOf(response.statusCode()));
        String body = stringValue(response.body());
        if (!body.isBlank()) {
            details.put("responseBody", body.length() > 1000 ? body.substring(0, 1000) + "...[truncated]" : body);
        }
        return details;
    }

    private String metadataValue(ChannelDefinition channel, String directKey, String envKey) {
        if (channel == null || channel.metadata() == null) {
            return "";
        }
        return metadataValue(channel.metadata(), directKey, envKey);
    }

    private String metadataValue(Map<String, String> metadata, String directKey, String envKey) {
        if (metadata == null) {
            return "";
        }
        String direct = stringValue(metadata.get(directKey));
        if (!direct.isBlank()) {
            return direct;
        }
        String envName = stringValue(metadata.get(envKey));
        return envName.isBlank() ? "" : stringValue(System.getenv(envName));
    }

    private String outboundMessageType(ChannelDefinition channel) {
        return firstNonBlank(
                metadataValue(channel, "outboundMessageType", "outboundMessageTypeEnv"),
                metadataValue(channel, "messageType", "messageTypeEnv"),
                "markdown").toLowerCase();
    }

    private boolean shouldUseMarkdown(String messageType, String text) {
        return "markdown".equals(messageType) || "auto".equals(messageType) && looksLikeMarkdown(text);
    }

    private boolean looksLikeMarkdown(String text) {
        String value = stringValue(text);
        return value.contains("**") || value.contains("__") || value.contains("`")
                || value.contains("](") || value.contains("\n#") || value.contains("\n- ");
    }

    private List<String> missingMetadata(String key, String value, String... rest) {
        List<String> missing = new ArrayList<>();
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
        if (rest != null) {
            for (int i = 0; i + 1 < rest.length; i += 2) {
                if (rest[i + 1] == null || rest[i + 1].isBlank()) {
                    missing.add(rest[i]);
                }
            }
        }
        return missing;
    }

    private boolean isStreamMode(ChannelDefinition channel) {
        String mode = metadataValue(channel, "connectionMode", "connectionModeEnv").toLowerCase();
        return "stream".equals(mode) || "dingtalk-stream".equals(mode);
    }

    private String firstNonBlank(String... values) {
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

    private String safeType(ChannelDefinition channel) {
        return channel.type() == null ? "" : channel.type().trim().toLowerCase();
    }

    private String safeError(String prefix, Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = e.getClass().getSimpleName();
        }
        return prefix + "：" + detail;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
