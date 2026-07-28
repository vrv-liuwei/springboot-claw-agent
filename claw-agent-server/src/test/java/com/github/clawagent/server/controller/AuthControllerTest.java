package com.github.clawagent.server.controller;

import com.github.clawagent.server.support.TestIdentityStores;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.LocalUserLoginRequest;
import com.github.clawagent.server.dto.LocalUserPasswordChangeRequest;
import com.github.clawagent.server.dto.LocalUserPermissionUpdateRequest;
import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.LocalUserSessionService;
import com.github.clawagent.server.service.LocalUserService;
import com.github.clawagent.spi.AgentEventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void loginMeAndLogoutUseLocalUserSession() {
        RecordingEventStore eventStore = new RecordingEventStore();
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);
        AuthController controller = new AuthController(
                TestIdentityStores.apiTokenService(tempDir), userService, sessionService, eventStore);
        controller.createUser(new LocalUserCreateRequest("admin", "123456", "管理员", "admin", Map.of()));

        var login = controller.login(new LocalUserLoginRequest("admin", "123456"));

        assertNotNull(login.sessionToken());
        assertEquals("admin", login.user().username());
        assertEquals("active", login.session().status());

        var current = controller.currentUser("Bearer " + login.sessionToken(), null);
        assertEquals("admin", current.user().username());
        assertEquals(login.session().sessionId(), current.session().sessionId());
        assertEquals(1, controller.sessions().size());

        var revoked = controller.revokeSession(login.session().sessionId());
        assertEquals("revoked", revoked.status());
        assertThrows(ResponseStatusException.class,
                () -> controller.currentUser("Bearer " + login.sessionToken(), null));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.login_succeeded".equals(event.type())));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.session_revoked".equals(event.type())));

        var secondLogin = controller.login(new LocalUserLoginRequest("admin", "123456"));
        Map<String, Object> logout = controller.logout(null, secondLogin.sessionToken());
        assertEquals(true, logout.get("success"));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.logout".equals(event.type())));
    }

    @Test
    void setupInitialOwnerAndRejectsSecondSetup() {
        RecordingEventStore eventStore = new RecordingEventStore();
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);
        AuthController controller = new AuthController(
                TestIdentityStores.apiTokenService(tempDir), userService, sessionService, eventStore);

        var status = controller.setupStatus();
        assertEquals(false, status.initialized());

        var owner = controller.setupOwner(new LocalUserCreateRequest("owner", "123456", "Owner", "user", Map.of()));

        assertEquals("owner", owner.role());
        assertEquals(true, controller.setupStatus().initialized());
        assertThrows(ResponseStatusException.class,
                () -> controller.setupOwner(new LocalUserCreateRequest("second", "123456", "", "owner", Map.of())));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.owner_setup".equals(event.type())));
    }

    @Test
    void loginRejectsInvalidPasswordAndDisabledUser() {
        RecordingEventStore eventStore = new RecordingEventStore();
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);
        AuthController controller = new AuthController(
                TestIdentityStores.apiTokenService(tempDir), userService, sessionService, eventStore);
        var user = controller.createUser(new LocalUserCreateRequest("dev", "123456", "", "user", Map.of()));

        assertThrows(ResponseStatusException.class,
                () -> controller.login(new LocalUserLoginRequest("dev", "bad-password")));

        controller.disableUser(user.id());

        assertThrows(ResponseStatusException.class,
                () -> controller.login(new LocalUserLoginRequest("dev", "123456")));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.login_failed".equals(event.type())));
    }

    @Test
    void updatesLocalUserPermissionsAndAuditsChange() {
        RecordingEventStore eventStore = new RecordingEventStore();
        LocalUserService userService = TestIdentityStores.localUserService(tempDir);
        LocalUserSessionService sessionService = TestIdentityStores.localUserSessionService(tempDir, userService);
        AuthController controller = new AuthController(
                TestIdentityStores.apiTokenService(tempDir), userService, sessionService, eventStore);
        var user = controller.createUser(new LocalUserCreateRequest("operator", "123456", "", "operator", Map.of()));

        var updated = controller.updateUserPermissions(user.id(), new LocalUserPermissionUpdateRequest(
                "custom",
                List.of("builtin.execute.command")));

        assertEquals("custom", updated.metadata().get("permissionMode"));
        assertEquals("builtin.execute.command", updated.metadata().get("approvedToolIds"));
        assertTrue(eventStore.events.stream().anyMatch(event -> "auth.user.permissions_updated".equals(event.type())));
    }

    private static final class RecordingEventStore implements AgentEventStore {
        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void saveEvent(AgentEvent event) {
            events.add(event);
        }

        @Override
        public List<AgentEvent> findEventsBySession(String sessionId, int limit) {
            return events;
        }

        @Override
        public List<AgentEvent> findEventsByTask(String taskId, int limit) {
            return events;
        }

        @Override
        public List<AgentEvent> findEvents(Instant from, Instant to, String level, String type,
                                           String sessionId, String taskId, int limit) {
            return events;
        }
    }
}
