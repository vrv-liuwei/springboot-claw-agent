package com.github.clawagent.intent;

/**
 * 待确认动作类型。
 * <p>
 * 用类型区分工具审批、系统意图确认和计划确认，底层确认流程保持统一。
 */
public enum PendingActionType {
    TOOL_APPROVAL,
    INTENT_CONFIRMATION,
    PLAN_APPROVAL
}
