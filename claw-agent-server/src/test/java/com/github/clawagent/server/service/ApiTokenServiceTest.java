package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.ApiTokenCreateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTokenServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createPersistsOnlyHashAndRevokeDisablesToken() throws Exception {
        Path storePath = tempDir.resolve("api-tokens.json");
        ApiTokenService service = new ApiTokenService(storePath);

        var created = service.create(new ApiTokenCreateRequest("CI Token", Map.of("source", "test")));
        String plainToken = created.token();

        assertNotNull(plainToken);
        assertTrue(plainToken.startsWith("cla_"));
        assertTrue(service.verify(plainToken));
        assertTrue(service.verifyAndTouch(plainToken, "GET", "/api/v1/tasks"));

        // 列表视图只暴露 token 前缀和状态，不返回明文或哈希。
        var tokens = service.list();
        assertEquals(1, tokens.size());
        assertEquals("CI Token", tokens.get(0).name());
        assertEquals("active", tokens.get(0).status());
        assertNotNull(tokens.get(0).lastUsedAt());
        assertEquals(1L, tokens.get(0).usageCount());
        assertEquals("GET", tokens.get(0).lastUsedMethod());
        assertEquals("/api/v1/tasks", tokens.get(0).lastUsedPath());
        assertTrue(plainToken.startsWith(tokens.get(0).tokenPrefix()));

        // 落盘文件只能保存哈希，避免刷新页面或读取配置时恢复完整 token。
        String rawStore = Files.readString(storePath);
        assertFalse(rawStore.contains(plainToken));
        assertTrue(rawStore.contains("tokenHash"));

        var revoked = service.revoke(tokens.get(0).id());
        assertEquals("revoked", revoked.status());
        assertEquals(1L, revoked.usageCount());
        assertFalse(service.verify(plainToken));
    }
}
