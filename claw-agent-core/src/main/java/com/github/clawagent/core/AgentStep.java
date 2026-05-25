package com.github.clawagent.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentStep 记录一次可审计的执行动作。
 * M1 中主要记录工具调用；后续模型思考、审批、验证也会落到同一个结构。
 */
public class AgentStep {
    private final String id;
    private final String taskId;
    private final StepType type;
    private final String name;
    private final Map<String, String> input;
    private final Instant startedAt;
    private Instant finishedAt;
    private StepStatus status;
    private String output;
    private String error;

    public AgentStep(String id, String taskId, StepType type, String name, Map<String, String> input) {
        this(id, taskId, type, name, input, Instant.now(), null, StepStatus.RUNNING, null, null);
    }

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
