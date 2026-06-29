package com.github.clawagent.server.dto;

import java.util.List;

/**
 * 记忆检索 HTTP 请求。
 *
 * @param userId 当前用户 ID，所有记忆检索必须按用户隔离。
 * @param query 检索关键词或自然语言问题。
 * @param scopeTypes 允许检索的范围，默认 global/channel/session。
 * @param scopeId 指定范围 ID，例如 channelId 或 sessionId。
 * @param statuses 允许检索的状态；模型上下文默认只使用 active。
 * @param mode 检索模式：keyword、vector、hybrid。
 * @param topK 返回命中数量上限。
 */
public record MemorySearchPayload(
        String userId,
        String query,
        List<String> scopeTypes,
        String scopeId,
        List<String> statuses,
        String mode,
        Integer topK
) {
}
