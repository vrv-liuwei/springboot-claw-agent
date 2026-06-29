package com.github.clawagent.server.dto;

/**
 * API Token 创建响应。
 * token 明文只在创建时返回一次，刷新列表不会再返回。
 */
public record ApiTokenCreateResponse(
        ApiTokenView tokenInfo,
        String token
) {
}
