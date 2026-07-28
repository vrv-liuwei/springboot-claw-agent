package com.github.clawagent.channel;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelEventMetadataSupportTest {

    @Test
    void mapsMessagePayloadToStandardSemanticFields() {
        Map<String, String> metadata = new LinkedHashMap<>();

        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "feishu",
                ChannelEventMetadataSupport.EVENT_SOURCE, "http",
                ChannelEventMetadataSupport.EVENT_TYPE, "im.message.receive_v1",
                ChannelEventMetadataSupport.PLATFORM_MESSAGE_TYPE, "text"
        ));

        assertEquals("message", metadata.get("channel.eventCategory"));
        assertEquals("message.received", metadata.get("channel.eventSemantic"));
        assertEquals("received", metadata.get("channel.eventAction"));
        assertEquals("feishu", metadata.get("channel.eventProvider"));
    }

    @Test
    void mapsKnownReactionAndMemberEventsWithoutMessagePayload() {
        Map<String, String> reactionMetadata = new LinkedHashMap<>();
        Map<String, String> memberMetadata = new LinkedHashMap<>();

        ChannelEventMetadataSupport.putStandardEvent(reactionMetadata, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "feishu",
                ChannelEventMetadataSupport.EVENT_TYPE, "im.message.reaction.created_v1"
        ));
        ChannelEventMetadataSupport.putStandardEvent(memberMetadata, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "feishu",
                ChannelEventMetadataSupport.EVENT_TYPE, "im.chat.member.user.deleted_v1"
        ));

        assertEquals("reaction", reactionMetadata.get("channel.eventCategory"));
        assertEquals("reaction.created", reactionMetadata.get("channel.eventSemantic"));
        assertEquals("created", reactionMetadata.get("channel.eventAction"));
        assertEquals("member", memberMetadata.get("channel.eventCategory"));
        assertEquals("member.removed", memberMetadata.get("channel.eventSemantic"));
        assertEquals("removed", memberMetadata.get("channel.eventAction"));
    }

    @Test
    void mapsDingtalkBotMessageAndUnknownEvent() {
        Map<String, String> botMessage = new LinkedHashMap<>();
        Map<String, String> unknown = new LinkedHashMap<>();

        ChannelEventMetadataSupport.putStandardEvent(botMessage, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "dingtalk",
                ChannelEventMetadataSupport.EVENT_TYPE, "chatbot_message"
        ));
        ChannelEventMetadataSupport.putStandardEvent(unknown, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "custom",
                ChannelEventMetadataSupport.EVENT_TYPE, "organization.updated"
        ));

        assertEquals("message", botMessage.get("channel.eventCategory"));
        assertEquals("message.received", botMessage.get("channel.eventSemantic"));
        assertEquals("event", unknown.get("channel.eventCategory"));
        assertEquals("event.received", unknown.get("channel.eventSemantic"));
    }

    @Test
    void respectsExplicitSemanticOverrides() {
        Map<String, String> metadata = new LinkedHashMap<>();

        ChannelEventMetadataSupport.putStandardEvent(metadata, Map.of(
                ChannelEventMetadataSupport.ADAPTER, "custom",
                ChannelEventMetadataSupport.EVENT_CATEGORY, "approval",
                ChannelEventMetadataSupport.EVENT_SEMANTIC, "approval.requested",
                ChannelEventMetadataSupport.EVENT_ACTION, "requested",
                ChannelEventMetadataSupport.EVENT_PROVIDER, "custom-im",
                ChannelEventMetadataSupport.EVENT_TYPE, "custom.approval"
        ));

        assertEquals("approval", metadata.get("channel.eventCategory"));
        assertEquals("approval.requested", metadata.get("channel.eventSemantic"));
        assertEquals("requested", metadata.get("channel.eventAction"));
        assertEquals("custom-im", metadata.get("channel.eventProvider"));
    }
}
