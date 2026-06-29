package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;
import com.github.clawagent.channel.dingtalk.DingtalkStreamClient;
import com.dingtalk.open.app.api.message.GenericOpenDingTalkEvent;
import org.junit.jupiter.api.Test;
import shade.com.alibaba.fastjson2.JSONObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelStreamClientManagerTest {

    @Test
    void reportsMissingFeishuCredentialsBeforeStartingSdkClient() {
        ChannelStreamClientManager manager = new ChannelStreamClientManager(null);
        ChannelDefinition channel = new ChannelDefinition("feishu", "飞书", "feishu", true,
                "ask", List.of(), "/api/v1/channels/feishu/inbound", Map.of("connectionMode", "long-connection"), null, null);

        ChannelStreamStatus status = manager.start(channel);

        assertEquals("failed", status.status());
        assertEquals("feishu-long-connection", status.mode());
    }

    @Test
    void reportsMissingDingtalkCredentialsBeforeStartingSdkClient() {
        ChannelStreamClientManager manager = new ChannelStreamClientManager(null);
        ChannelDefinition channel = new ChannelDefinition("dingtalk", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk/inbound", Map.of("connectionMode", "stream"), null, null);

        ChannelStreamStatus status = manager.start(channel);

        assertEquals("failed", status.status());
        assertEquals("dingtalk-stream", status.mode());
    }

    @Test
    void reportsStoppedWhenNoClientRuns() {
        ChannelStreamClientManager manager = new ChannelStreamClientManager(null);
        ChannelDefinition channel = new ChannelDefinition("api", "API", "api", true,
                "ask", List.of(), "/api/v1/channels/inbound", Map.of(), null, null);

        ChannelStreamStatus status = manager.status(channel);

        assertEquals("stopped", status.status());
    }

    @Test
    void normalizesDingtalkStreamTextPayload() {
        DingtalkStreamClient client = new DingtalkStreamClient(null);
        ChannelDefinition channel = new ChannelDefinition("dingtalk", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk/inbound", Map.of(), null, null);
        GenericOpenDingTalkEvent event = new GenericOpenDingTalkEvent();
        event.setEventId("event-1");
        event.setEventType("chat_message");
        event.setEventCorpId("corp-1");
        event.setEventUnifiedAppId("app-1");
        event.setData(JSONObject.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "senderNick", "李四",
                "msgtype", "text",
                "text", JSONObject.of("content", "钉钉 Stream 消息")));

        ChannelInboundMessage message = client.toInboundMessage(channel, event);

        assertEquals("dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("staff-1", message.externalUserId());
        assertEquals("text", message.messageType());
        assertEquals("钉钉 Stream 消息", message.text());
        assertEquals("dingtalk-stream", message.metadata().get("channel.adapter"));
        assertEquals("李四", message.metadata().get("channel.senderNick"));
        assertEquals("event-1", message.rawPayload().get("eventId"));
    }

    @Test
    void fallsBackToDingtalkStreamRawDataWhenTextShapeIsUnknown() {
        DingtalkStreamClient client = new DingtalkStreamClient(null);
        ChannelDefinition channel = new ChannelDefinition("dingtalk", "钉钉", "dingtalk", true,
                "ask", List.of(), "/api/v1/channels/dingtalk/inbound", Map.of(), null, null);
        GenericOpenDingTalkEvent event = new GenericOpenDingTalkEvent();
        event.setEventType("unknown_event");
        event.setEventCorpId("corp-1");
        event.setEventUnifiedAppId("app-1");
        event.setData(JSONObject.of("payload", JSONObject.of("value", "raw")));

        ChannelInboundMessage message = client.toInboundMessage(channel, event);

        assertEquals("corp-1", message.externalConversationId());
        assertEquals("app-1", message.externalUserId());
        assertEquals("unknown_event", message.messageType());
        assertEquals("{\"payload\":{\"value\":\"raw\"}}", message.text());
    }
}
