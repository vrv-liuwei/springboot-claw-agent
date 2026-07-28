package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;

import com.github.clawagent.server.dto.ChannelUserBindingRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelUserBindingServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void bindUpdatesSameExternalUserAndKeepsLatestLocalUser() {
        ChannelUserBindingService service = TestIdentityStores.channelUserBindingService(tempDir);

        service.bind("feishu-main", new ChannelUserBindingRequest(
                "ou_1", "张三", "user-1", "alice", Map.of("source", "first")));
        var updated = service.bind("feishu-main", new ChannelUserBindingRequest(
                "ou_1", "张三", "user-2", "bob", Map.of("source", "second")));

        assertEquals("user-2", updated.localUserId());
        assertEquals("bob", updated.localUsername());
        assertEquals("second", updated.metadata().get("source"));
        assertEquals(1, service.list("feishu-main").size());
        assertEquals("user-2", service.findActive("feishu-main", "ou_1").orElseThrow().localUserId());
    }

    @Test
    void unbindMarksRecordInactiveAndRemovesActiveLookup() {
        ChannelUserBindingService service = TestIdentityStores.channelUserBindingService(tempDir);
        service.bind("dingtalk-main", new ChannelUserBindingRequest(
                "ding-1", "李四", "user-1", "operator", Map.of()));

        assertTrue(service.unbind("dingtalk-main", "ding-1"));

        assertEquals("unbound", service.list("dingtalk-main").get(0).status());
        assertTrue(service.findActive("dingtalk-main", "ding-1").isEmpty());
    }
}
