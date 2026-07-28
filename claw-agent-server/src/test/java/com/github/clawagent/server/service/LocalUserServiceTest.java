package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;
import com.github.clawagent.server.dto.LocalUserCreateRequest;
import com.github.clawagent.server.dto.LocalUserPasswordChangeRequest;
import com.github.clawagent.server.dto.LocalUserPermissionUpdateRequest;
import com.github.clawagent.server.dto.LocalUserView;
import com.github.clawagent.spi.LocalUserRecord;
import com.github.clawagent.spi.LocalUserStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalUserServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsUserAndVerifiesPasswordWithoutPersistingPlainText() throws Exception {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);

        LocalUserView user = service.create(new LocalUserCreateRequest(
                "Admin",
                "123456",
                "管理员",
                "admin",
                Map.of("source", "test")));

        assertEquals("admin", user.username());
        assertEquals("管理员", user.displayName());
        assertTrue(service.verify("admin", "123456"));
        assertFalse(service.verify("admin", "bad-password"));
        assertEquals(user.id(), service.authenticate("admin", "123456").orElseThrow().id());
        String rawStore = new String(java.nio.file.Files.readAllBytes(TestIdentityStores.databasePath(tempDir)), StandardCharsets.ISO_8859_1);
        assertFalse(rawStore.contains("123456"));
    }

    @Test
    void setupOwnerOnlyWorksBeforeLocalUsersExist() {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);

        LocalUserView owner = service.setupOwner(new LocalUserCreateRequest("root", "123456", "Owner", "user", Map.of()));

        assertEquals("owner", owner.role());
        assertTrue(service.isInitialized());
        assertTrue(service.ownerExists());
        assertEquals(1, service.count());
        assertThrows(IllegalStateException.class,
                () -> service.setupOwner(new LocalUserCreateRequest("second", "123456", "", "owner", Map.of())));
    }

    @Test
    void defaultsFirstBlankRoleUserToOwnerAndNormalizesUnsupportedRoles() {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);

        LocalUserView first = service.create(new LocalUserCreateRequest("first", "123456", "", "", Map.of()));
        LocalUserView second = service.create(new LocalUserCreateRequest("second", "123456", "", "bad-role", Map.of()));

        assertEquals("owner", first.role());
        assertEquals("user", second.role());
    }

    @Test
    void rejectsDuplicateUserAndDisablesLoginAfterDisable() {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = service.create(new LocalUserCreateRequest("admin", "123456", "", "admin", Map.of()));

        assertThrows(IllegalArgumentException.class,
                () -> service.create(new LocalUserCreateRequest("ADMIN", "abcdef", "", "admin", Map.of())));

        service.disable(user.id());

        assertFalse(service.verify("admin", "123456"));
        assertEquals("disabled", service.list().get(0).status());
    }

    @Test
    void changesPasswordForActiveUser() {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = service.create(new LocalUserCreateRequest("dev", "old-pass", "", "user", Map.of()));

        service.changePassword(user.id(), new LocalUserPasswordChangeRequest("new-pass"));

        assertFalse(service.verify("dev", "old-pass"));
        assertTrue(service.verify("dev", "new-pass"));
    }

    @Test
    void verifyReturnsFalseWhenStoredPasswordFieldsAreMissing() throws Exception {
        LocalUserStore store = new LocalUserStore() {
            @Override
            public List<LocalUserRecord> read() {
                return List.of(new LocalUserRecord("broken", "admin", "admin", "admin", "active",
                        Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, Map.of()));
            }

            @Override
            public void write(List<LocalUserRecord> records) {
                // 这个用例只验证缺失密码字段时的登录兜底，不需要持久化写入。
            }
        };
        LocalUserService service = new LocalUserService(store);

        assertFalse(service.verify("admin", "123456"));
    }

    @Test
    void updatesUserPermissionMetadataForPolicyEnrichment() {
        LocalUserService service = TestIdentityStores.localUserService(tempDir);
        LocalUserView user = service.create(new LocalUserCreateRequest("operator", "123456", "", "operator", Map.of(
                "source", "test",
                "approvedToolIds", "old.tool")));

        LocalUserView updated = service.updatePermissions(user.id(), new LocalUserPermissionUpdateRequest(
                "custom",
                List.of("builtin.execute.command", "builtin.filesystem.read_text_file")));

        assertEquals("custom", updated.metadata().get("permissionMode"));
        assertEquals("builtin.execute.command,builtin.filesystem.read_text_file", updated.metadata().get("approvedToolIds"));
        assertEquals("test", updated.metadata().get("source"));
    }
}
