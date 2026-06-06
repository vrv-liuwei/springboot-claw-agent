package com.github.clawagent.core;

public enum StepType {
    /** 模型思考或推理阶段。 */
    THINK,
    /** 任务规划阶段。 */
    PLAN,
    /** 工具调用阶段。 */
    TOOL_CALL,
    /** 观察工具结果阶段。 */
    OBSERVE,
    /** 验证阶段。 */
    VERIFY,
    /** 最终回答阶段。 */
    FINAL
}
