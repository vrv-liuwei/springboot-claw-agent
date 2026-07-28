package com.github.clawagent.intent;

/**
 * 待确认动作的执行回调。
 * <p>
 * PendingActionService 只负责确认状态流转，真正的业务动作由 executor 在确认后执行。
 */
public interface PendingActionExecutor {
    /**
     * 用户确认后执行真实业务动作。
     */
    String confirm(PendingAction action, String input);

    /**
     * 用户取消时生成取消回复，默认不执行任何业务动作。
     */
    default String reject(PendingAction action, String reason) {
        return "已取消执行。";
    }
}
