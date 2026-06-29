package com.github.clawagent.channel;

import com.github.clawagent.channel.dingtalk.DingtalkChannelAdapter;
import com.github.clawagent.channel.ddio.DdioChannelAdapter;
import com.github.clawagent.channel.feishu.FeishuChannelAdapter;
import com.github.clawagent.core.ChannelInboundMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelAdapterImplementationTest {

    @Test
    void feishuAdapterConvertsTextEventAndKeepsConfiguredChannelId() {
        FeishuChannelAdapter adapter = new FeishuChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-feishu", Map.of(
                "header", Map.of("event_type", "im.message.receive_v1"),
                "event", Map.of(
                        "sender", Map.of("sender_id", Map.of("user_id", "ou-user")),
                        "message", Map.of(
                                "chat_id", "oc-chat",
                                "message_id", "om-message",
                                "message_type", "text",
                                "content", "{\"text\":\"飞书消息\"}"
                        )
                )
        ));

        assertTrue(adapter.supports("lark"));
        assertEquals("corp-feishu", message.channelId());
        assertEquals("oc-chat", message.externalConversationId());
        assertEquals("ou-user", message.externalUserId());
        assertEquals("飞书消息", message.text());
        assertEquals("feishu", message.metadata().get("channel.adapter"));
    }

    @Test
    void dingtalkAdapterConvertsTextEventAndKeepsConfiguredChannelId() {
        DingtalkChannelAdapter adapter = new DingtalkChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-dingtalk", Map.of(
                "conversationId", "cid-1",
                "senderStaffId", "staff-1",
                "msgtype", "text",
                "text", Map.of("content", "钉钉消息")
        ));

        assertTrue(adapter.supports("dingtalk"));
        assertEquals("corp-dingtalk", message.channelId());
        assertEquals("cid-1", message.externalConversationId());
        assertEquals("staff-1", message.externalUserId());
        assertEquals("钉钉消息", message.text());
        assertEquals("dingtalk", message.metadata().get("channel.adapter"));
    }

    @Test
    void ddioAdapterConvertsTextEventAndKeepsConfiguredChannelId() {
        DdioChannelAdapter adapter = new DdioChannelAdapter();

        ChannelInboundMessage message = adapter.adaptInbound("corp-ddio", Map.of(
                "sendUserID", "user-1",
                "receTargetID", "target-1",
                "messageType", "2",
                "message", Map.of("messageType", "2", "body", "DDIO 文本")
        ));

        assertTrue(adapter.supports("ddio"));
        assertEquals("corp-ddio", message.channelId());
        assertEquals("user-1", message.externalConversationId());
        assertEquals("user-1", message.externalUserId());
        assertEquals("2", message.messageType());
        assertEquals("DDIO 文本", message.text());
        assertEquals("ddio", message.metadata().get("channel.adapter"));
        assertEquals("2", message.metadata().get("ddio.messageType"));
        assertEquals("target-1", message.metadata().get("channel.ddio.receTargetId"));
    }
}
