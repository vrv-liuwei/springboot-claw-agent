package com.github.clawagent.server.dto;

import java.util.Map;

/**
 * API Token 创建请求。
 * metadata 只保存轻量说明字段，不保存密钥或外部凭证。
 */
public record ApiTokenCreateRequest(
        String name,
        Map<String, String> metadata
) {
}
