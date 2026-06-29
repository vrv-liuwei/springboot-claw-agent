package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.DeviceRegisterRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceRegistryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void registerHeartbeatAndRevokeDevice() throws Exception {
        Path storePath = tempDir.resolve("devices.json");
        DeviceRegistryService service = new DeviceRegistryService(storePath);

        var registered = service.register(new DeviceRegisterRequest("Desktop App", "desktop", Map.of("os", "windows")));

        assertNotNull(registered.id());
        assertEquals("Desktop App", registered.name());
        assertEquals("active", registered.status());
        assertEquals(1, service.list().size());

        // 心跳只刷新最后在线时间，不改变设备登记状态。
        var heartbeat = service.heartbeat(registered.id());
        assertEquals("active", heartbeat.status());
        assertTrue(!heartbeat.lastSeenAt().isBefore(registered.lastSeenAt()));

        var revoked = service.revoke(registered.id());
        assertEquals("revoked", revoked.status());
        assertNotNull(revoked.revokedAt());
        assertTrue(Files.readString(storePath).contains("Desktop App"));
    }
}
