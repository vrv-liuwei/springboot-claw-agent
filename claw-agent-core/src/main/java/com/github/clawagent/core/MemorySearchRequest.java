package com.github.clawagent.core;

import java.util.List;

/**
 * 记忆检索请求。
 *
 * @param userId 用户 ID，检索隔离边界。
 * @param query 当前用户问题或关键词。
 * @param scopeTypes 允许检索的 scope；默认由 provider 限定为 active 的 global/channel/session。
 * @param scopeId scope ID，可为空。
 * @param statuses 允许状态，模型上下文默认只传 active。
 * @param mode keyword、vector、hybrid。
 * @param topK 返回条数。
 */
public record MemorySearchRequest(
        String userId,
        String query,
        List<String> scopeTypes,
        String scopeId,
        List<String> statuses,
        String mode,
        int topK
) {
}
