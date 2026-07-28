package com.github.clawagent.server.service;

import com.github.clawagent.server.support.TestIdentityStores;
import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createPersistsTokenForConsoleCopyAndDeleteRemovesToken() throws Exception {
        ApiTokenService service = TestIdentityStores.apiTokenService(tempDir);

        var created = service.create(new ApiTokenCreateRequest("CI Token", Map.of("source", "test")));
        String plainToken = created.token();

        assertNotNull(plainToken);
        assertTrue(plainToken.startsWith("cla_"));
        assertTrue(service.verify(plainToken));
        assertTrue(service.verifyAndTouch(plainToken, "GET", "/api/v1/tasks"));

        // 本地管理台需要二次复制 Token；列表返回明文，但仍不返回 tokenHash。
        var tokens = service.list();
        assertEquals(1, tokens.size());
        assertEquals("CI Token", tokens.get(0).name());
        assertEquals("active", tokens.get(0).status());
        assertEquals(plainToken, tokens.get(0).token());
        assertNotNull(tokens.get(0).lastUsedAt());
        assertEquals(1L, tokens.get(0).usageCount());
        assertEquals("GET", tokens.get(0).lastUsedMethod());
        assertEquals("/api/v1/tasks", tokens.get(0).lastUsedPath());
        assertTrue(plainToken.startsWith(tokens.get(0).tokenPrefix()));

        // 本地 SQLite 保存明文用于管理台复制；真正鉴权仍依赖 hash，不把 hash 暴露到列表视图。
        String rawStore = new String(java.nio.file.Files.readAllBytes(TestIdentityStores.databasePath(tempDir)), StandardCharsets.ISO_8859_1);
        assertTrue(rawStore.contains(plainToken));
        assertTrue(rawStore.contains("token_hash"));

        var deleted = service.delete(tokens.get(0).id());
        assertEquals("active", deleted.status());
        assertEquals(1L, deleted.usageCount());
        assertEquals(0, service.list().size());
        assertFalse(service.verify(plainToken));
    }

    @Test
    void tokenCanCarryOwnerScopeAndPermissionPolicy() {
        ApiTokenService service = TestIdentityStores.apiTokenService(tempDir);

        var created = service.create(new ApiTokenCreateRequest(
                "Desktop Token",
                "user-1",
                "alice",
                "custom",
                List.of("builtin.execute.command", "builtin.execute.command", "builtin.filesystem.read_text_file"),
                List.of("tasks:write", "tasks:read"),
                Instant.now().plusSeconds(3600),
                Map.of("source", "desktop")));

        var view = created.tokenInfo();

        assertEquals("user-1", view.ownerUserId());
        assertEquals("alice", view.ownerUsername());
        assertEquals("custom", view.permissionMode());
        assertEquals(List.of("builtin.execute.command", "builtin.filesystem.read_text_file"), view.approvedToolIds());
        assertEquals(List.of("tasks:write", "tasks:read"), view.scopes());
        assertTrue(service.authenticateAndTouch(created.token(), "POST", "/api/v1/tasks").isPresent());
    }

    @Test
    void expiredTokenCannotAuthenticate() {
        ApiTokenService service = TestIdentityStores.apiTokenService(tempDir);
        var created = service.create(new ApiTokenCreateRequest(
                "Expired",
                "",
                "",
                "",
                List.of(),
                List.of("tasks:write"),
                Instant.now().minusSeconds(1),
                Map.of()));

        assertFalse(service.verify(created.token()));
        assertTrue(service.authenticateAndTouch(created.token(), "GET", "/api/v1/tasks").isEmpty());
    }
}
