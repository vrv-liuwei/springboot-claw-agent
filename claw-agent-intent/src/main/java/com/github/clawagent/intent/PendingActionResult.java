package com.github.clawagent.intent;

/**
 * 用户确认/取消输入的处理结果。
 * <p>
 * handled=false 表示当前输入不是确认类文本，调用方应继续走意图路由或普通对话。
 */
public record PendingActionResult(
        boolean handled,
        PendingAction action,
        String answer
) {
    /**
     * 表示当前输入未被 PendingActionService 消费。
     */
    public static PendingActionResult none() {
        return new PendingActionResult(false, null, "");
    }
}
