package com.github.clawagent.core;

/**
 * 自动化单次运行状态。
 */
public enum AutomationRunStatus {
    /** 正在执行。 */
    RUNNING,
    /** 执行完成。 */
    COMPLETED,
    /** 执行失败。 */
    FAILED
}
