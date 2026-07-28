package com.github.clawagent.intent;

import java.time.Duration;
import java.util.Map;

/**
 * 创建待确认动作的请求。
 * <p>
 * 调用方把动作类型、风险、会话身份和目标 ID 传入，由 PendingActionService 生成确认文本和生命周期。
 */
public record PendingActionCreateRequest(
        PendingActionType type,
        String title,
        String description,
        IntentRisk risk,
        String sessionId,
        String channelId,
        String userId,
        String taskId,
        String stepId,
        String targetId,
        Map<String, String> metadata,
        Duration ttl
) {
    public PendingActionCreateRequest {
        risk = risk == null ? IntentRisk.MEDIUM : risk;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        ttl = ttl == null ? Duration.ofMinutes(2) : ttl;
    }
}
