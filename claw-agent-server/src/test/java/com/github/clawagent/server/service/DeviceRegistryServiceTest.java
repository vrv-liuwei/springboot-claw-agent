package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;
import com.github.clawagent.server.dto.DeviceRegisterRequest;
import com.github.clawagent.server.dto.DevicePairRequest;
import com.github.clawagent.server.dto.DevicePairingCreateRequest;
import com.github.clawagent.server.dto.DevicePermissionUpdateRequest;
import com.github.clawagent.server.dto.DeviceSecretVerifyRequest;
import com.github.clawagent.server.dto.DeviceUserBindRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceRegistryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void registerHeartbeatAndRevokeDevice() throws Exception {
        DeviceRegistryService service = TestIdentityStores.deviceRegistryService(tempDir);

        var registered = service.register(new DeviceRegisterRequest("Desktop App", "desktop", "ask", List.of(), Map.of("os", "windows")));

        assertNotNull(registered.id());
        assertEquals("Desktop App", registered.name());
        assertEquals("active", registered.status());
        assertEquals("ask", registered.permissionMode());
        assertEquals(1, service.list().size());

        // 心跳只刷新最后在线时间，不改变设备登记状态。
        var heartbeat = service.heartbeat(registered.id());
        assertEquals("active", heartbeat.status());
        assertTrue(!heartbeat.lastSeenAt().isBefore(registered.lastSeenAt()));

        var revoked = service.revoke(registered.id());
        assertEquals("revoked", revoked.status());
        assertNotNull(revoked.revokedAt());
        assertTrue(readDatabaseText().contains("Desktop App"));
    }

    @Test
    void pairingCodeReturnsDeviceSecretOnlyOnceAndStoresHash() throws Exception {
        DeviceRegistryService service = TestIdentityStores.deviceRegistryService(tempDir);

        var pairing = service.createPairingCode(new DevicePairingCreateRequest(
                "Desktop App", "desktop", 300, "custom", List.of("builtin.execute.command"), Map.of("os", "windows")));
        var paired = service.pair(new DevicePairRequest(pairing.code(), Map.of("version", "1.0.0")));

        assertEquals("active", paired.device().status());
        assertEquals("custom", paired.device().permissionMode());
        assertEquals(List.of("builtin.execute.command"), paired.device().approvedToolIds());
        assertNotNull(paired.deviceSecret());
        assertTrue(service.verifySecret(paired.device().id(), new DeviceSecretVerifyRequest(paired.deviceSecret())).verified());
        assertTrue(!service.verifySecret(paired.device().id(), new DeviceSecretVerifyRequest("bad-secret")).verified());

        String stored = readDatabaseText();
        assertTrue(!stored.contains(pairing.code()));
        assertTrue(!stored.contains(paired.deviceSecret()));
        assertTrue(stored.contains("device_secret_prefix"));
    }

    @Test
    void updatesDevicePermissionBinding() {
        DeviceRegistryService service = TestIdentityStores.deviceRegistryService(tempDir);
        var registered = service.register(new DeviceRegisterRequest("Gateway", "gateway", "ask", List.of(), Map.of()));

        var updated = service.updatePermissions(registered.id(),
                new DevicePermissionUpdateRequest("custom", List.of("builtin.filesystem.read_text_file", "builtin.filesystem.read_text_file")));

        assertEquals("custom", updated.permissionMode());
        assertEquals(List.of("builtin.filesystem.read_text_file"), updated.approvedToolIds());
    }

    @Test
    void rotatesDeviceSecretAndInvalidatesOldSecret() {
        DeviceRegistryService service = TestIdentityStores.deviceRegistryService(tempDir);
        var pairing = service.createPairingCode(new DevicePairingCreateRequest(
                "Desktop App", "desktop", 300, "ask", List.of(), Map.of()));
        var paired = service.pair(new DevicePairRequest(pairing.code(), Map.of()));

        var rotated = service.rotateSecret(paired.device().id());

        assertNotNull(rotated.deviceSecret());
        assertTrue(service.verifySecret(rotated.device().id(), new DeviceSecretVerifyRequest(rotated.deviceSecret())).verified());
        assertTrue(!service.verifySecret(rotated.device().id(), new DeviceSecretVerifyRequest(paired.deviceSecret())).verified());
    }

    @Test
    void bindsAndUnbindsLocalUser() {
        DeviceRegistryService service = TestIdentityStores.deviceRegistryService(tempDir);
        var registered = service.register(new DeviceRegisterRequest("Desktop", "desktop", "ask", List.of(), Map.of()));

        var bound = service.bindUser(registered.id(), new DeviceUserBindRequest("user-1", "alice"));
        var unbound = service.bindUser(registered.id(), new DeviceUserBindRequest("", ""));

        assertEquals("user-1", bound.boundUserId());
        assertEquals("alice", bound.boundUsername());
        assertEquals(null, unbound.boundUserId());
        assertEquals(null, unbound.boundUsername());
    }

    private String readDatabaseText() throws Exception {
        return new String(java.nio.file.Files.readAllBytes(TestIdentityStores.databasePath(tempDir)), StandardCharsets.ISO_8859_1);
    }
}
