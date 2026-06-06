package com.github.clawagent.core;

/**
 * 自动化调度类型。
 */
public enum AutomationScheduleType {
    /** 只执行一次。 */
    ONCE,
    /** 按固定间隔重复执行。 */
    INTERVAL,
    /** 按 cron 表达式调度执行。 */
    CRON
}
