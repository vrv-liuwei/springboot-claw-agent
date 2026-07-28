package com.github.clawagent.intent;

import java.time.Instant;
import java.util.Map;

/**
 * 待用户确认的动作实例。
 * <p>
 * 它是工具审批、系统意图确认、计划确认的统一运行时对象。
 */
public record PendingAction(
        String actionId,
        PendingActionType type,
        PendingActionStatus status,
        String title,
        String description,
        IntentRisk risk,
        String confirmText,
        String sessionId,
        String channelId,
        String userId,
        String taskId,
        String stepId,
        String targetId,
        Map<String, String> metadata,
        Instant createdAt,
        Instant expiresAt
) {
    public PendingAction {
        risk = risk == null ? IntentRisk.MEDIUM : risk;
        status = status == null ? PendingActionStatus.PENDING : status;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 创建一个状态变更后的副本，保持 PendingAction 不可变。
     */
    public PendingAction withStatus(PendingActionStatus next) {
        return new PendingAction(actionId, type, next, title, description, risk, confirmText, sessionId,
                channelId, userId, taskId, stepId, targetId, metadata, createdAt, expiresAt);
    }
}
