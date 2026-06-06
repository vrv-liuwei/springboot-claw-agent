package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentStep 记录一次可审计的执行动作。
 * M1 中主要记录工具调用；后续模型思考、审批、验证也会落到同一个结构。
 */
public class AgentStep {
    /** 步骤 ID。 */
    private final String id;
    /** 步骤所属任务 ID。 */
    private final String taskId;
    /** 步骤类型，例如工具调用、模型调用、审批、验证。 */
    private final StepType type;
    /** 步骤名称，通常是工具 ID 或阶段名称。 */
    private final String name;
    /** 步骤输入参数，保存轻量字符串键值。 */
    private final Map<String, String> input;
    /** 步骤开始时间。 */
    private final Instant startedAt;
    /** 步骤结束时间。 */
    private Instant finishedAt;
    /** 步骤状态。 */
    private StepStatus status;
    /** 步骤输出内容。 */
    private String output;
    /** 步骤失败原因。 */
    private String error;

    /**
     * 创建运行中的步骤。
     *
     * @param id 步骤 ID。
     * @param taskId 步骤所属任务 ID。
     * @param type 步骤类型。
     * @param name 步骤名称。
     * @param input 步骤输入参数。
     */
    public AgentStep(String id, String taskId, StepType type, String name, Map<String, String> input) {
        this(id, taskId, type, name, input, Instant.now(), null, StepStatus.RUNNING, null, null);
    }

    /**
     * 创建或恢复步骤。
     *
     * @param id 步骤 ID。
     * @param taskId 步骤所属任务 ID。
     * @param type 步骤类型。
     * @param name 步骤名称。
     * @param input 步骤输入参数。
     * @param startedAt 步骤开始时间。
     * @param finishedAt 步骤结束时间。
     * @param status 步骤状态。
     * @param output 步骤输出内容。
     * @param error 步骤失败原因。
     */
    public AgentStep(String id, String taskId, StepType type, String name, Map<String, String> input,
                     Instant startedAt, Instant finishedAt, StepStatus status, String output, String error) {
        this.id = id;
        this.taskId = taskId;
        this.type = type;
        this.name = name;
        this.input = input == null ? new LinkedHashMap<>() : new LinkedHashMap<>(input);
        // 步骤列表用于审计执行链路，读取历史记录时不能重新生成 startedAt/finishedAt。
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.finishedAt = finishedAt;
        this.status = status == null ? StepStatus.RUNNING : status;
        this.output = output;
        this.error = error;
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String taskId() { return taskId; }
    public String getTaskId() { return taskId; }
    public StepType type() { return type; }
    public StepType getType() { return type; }
    public String name() { return name; }
    public String getName() { return name; }
    public Map<String, String> input() { return input; }
    public Map<String, String> getInput() { return input; }
    public Instant startedAt() { return startedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public StepStatus status() { return status; }
    public StepStatus getStatus() { return status; }
    public String output() { return output; }
    public String getOutput() { return output; }
    public String error() { return error; }
    public String getError() { return error; }

    public void succeed(String output) {
        this.output = output;
        this.status = StepStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void fail(String error) {
        this.error = error;
        this.status = StepStatus.FAILED;
        this.finishedAt = Instant.now();
    }
}
