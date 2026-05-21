package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentRequest 是业务入口传给 Harness Runtime 的最小请求对象。
 * 这里保留 channel/session/user 字段，是为了让权限、审计、记忆在后续阶段有稳定上下文。
 */
public record AgentRequest(
        String input,
        String sessionId,
        String channelId,
        String userId,
        Map<String, String> metadata
) {
    public AgentRequest {
        // 外部 API 调用不一定会传 metadata，这里统一归一化，避免 Runtime 构造任务时出现空指针。
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public static AgentRequest userMessage(String input) {
        return new AgentRequest(input, null, "webui", "anonymous", new LinkedHashMap<>());
    }
}
