package com.github.clawagent.core;

public enum StepStatus {
    /** 步骤等待执行。 */
    PENDING,
    /** 步骤正在执行。 */
    RUNNING,
    /** 步骤执行成功。 */
    SUCCEEDED,
    /** 步骤执行失败。 */
    FAILED,
    /** 步骤被跳过。 */
    SKIPPED
}
