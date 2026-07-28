package com.github.clawagent.server.dto;

import java.util.Map;
import java.util.List;
import java.time.Instant;

/**
 * API Token 创建请求。
 * metadata 只保存轻量说明字段，不保存密钥或外部凭证。
 */
public record ApiTokenCreateRequest(
        String name,
        String ownerUserId,
        String ownerUsername,
        String permissionMode,
        List<String> approvedToolIds,
        List<String> scopes,
        Instant expiresAt,
        Map<String, String> metadata
) {
    public ApiTokenCreateRequest(String name, Map<String, String> metadata) {
        this(name, null, null, null, List.of(), List.of(), null, metadata);
    }
}
