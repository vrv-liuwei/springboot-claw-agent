package com.github.clawagent.intent;

import java.util.Optional;

/**
 * 待确认动作服务接口。
 * <p>
 * 统一承接 IM 文本确认、后台按钮确认、工具审批和计划确认。
 */
public interface PendingActionService {
    /**
     * 创建待确认动作，并绑定确认后的执行器。
     */
    PendingAction create(PendingActionCreateRequest request, PendingActionExecutor executor);

    /**
     * 按 sessionId/channelId/userId 查找当前用户最近的待确认动作。
     */
    Optional<PendingAction> findPending(String sessionId, String channelId, String userId);

    /**
     * 处理用户输入的确认或取消文本。
     */
    PendingActionResult handleUserInput(String sessionId, String channelId, String userId, String input);

    /**
     * 按工具调用、计划或意图的目标 ID 直接确认动作，主要服务后台按钮。
     */
    PendingActionResult confirmByTarget(PendingActionType type, String taskId, String stepId, String targetId, String input);

    /**
     * 按工具调用、计划或意图的目标 ID 直接拒绝动作，主要服务后台按钮。
     */
    PendingActionResult rejectByTarget(PendingActionType type, String taskId, String stepId, String targetId, String reason);
}
