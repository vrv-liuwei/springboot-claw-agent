package com.github.clawagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentRequest 是业务入口传给 Harness Runtime 的最小请求对象。
 * 这里保留 channel/session/user 字段，是为了让权限、审计、记忆在后续阶段有稳定上下文。
 *
 * @param input 用户原始输入；附件正文、知识库正文不应直接拼入该字段。
 * @param sessionId 会话 ID，用于把任务、消息、事件串到同一会话。
 * @param channelId 请求来源渠道，例如 webui、automation、api。
 * @param userId 用户 ID，用于权限、审计、知识库和附件隔离。
 * @param metadata 请求轻量扩展元信息，只保存可序列化的小字段。
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
