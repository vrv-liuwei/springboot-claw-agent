package com.github.clawagent.core;

public enum TaskStatus {
    /** 任务已创建，等待执行。 */
    PENDING,
    /** 任务正在执行。 */
    RUNNING,
    /** 任务等待用户审批。 */
    WAITING_APPROVAL,
    /** 任务已完成。 */
    COMPLETED,
    /** 任务执行失败。 */
    FAILED,
    /** 任务已取消。 */
    CANCELLED
}
