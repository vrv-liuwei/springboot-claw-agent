package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.LocalUserLoginResponse;
import com.github.clawagent.server.dto.LocalUserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalUserSessionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createPersistsOnlyHashAndAuthenticateTouchesSession() throws Exception {
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = userService.create(new LocalUserCreateRequest("admin", "123456", "管理员", "admin", Map.of()));
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);

        LocalUserLoginResponse created = sessionService.create(user);

        assertNotNull(created.sessionToken());
        assertTrue(created.sessionToken().startsWith("clas_"));
        assertEquals("active", created.session().status());
        assertTrue(created.sessionToken().startsWith(created.session().tokenPrefix()));

        String rawStore = new String(java.nio.file.Files.readAllBytes(TestIdentityStores.databasePath(tempDir)), StandardCharsets.ISO_8859_1);
        assertFalse(rawStore.contains(created.sessionToken()));
        assertTrue(rawStore.contains("token_hash"));

        LocalUserLoginResponse current = sessionService.authenticate(created.sessionToken()).orElseThrow();

        assertEquals(user.id(), current.user().id());
        assertEquals(created.session().sessionId(), current.session().sessionId());
        assertNotNull(current.session().lastUsedAt());
        assertEquals(null, current.sessionToken());
    }

    @Test
    void revokeAndExpiryDisableSession() {
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = userService.create(new LocalUserCreateRequest("dev", "123456", "开发", "user", Map.of()));
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService, Duration.ofMillis(1));

        LocalUserLoginResponse created = sessionService.create(user);
        assertTrue(sessionService.revoke(created.sessionToken()).isPresent());
        assertTrue(sessionService.authenticate(created.sessionToken()).isEmpty());

        LocalUserLoginResponse expiring = sessionService.create(user);
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(sessionService.authenticate(expiring.sessionToken()).isEmpty());
    }

    @Test
    void listAndRevokeBySessionIdSupportAdminSessionManagement() {
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = userService.create(new LocalUserCreateRequest("admin", "123456", "管理员", "admin", Map.of()));
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);

        LocalUserLoginResponse created = sessionService.create(user);
        List<?> sessions = sessionService.list();

        assertEquals(1, sessions.size());
        assertTrue(sessionService.revokeBySessionId(created.session().sessionId()).isPresent());
        assertTrue(sessionService.authenticate(created.sessionToken()).isEmpty());
        assertEquals("revoked", sessionService.list().get(0).status());
    }
}
