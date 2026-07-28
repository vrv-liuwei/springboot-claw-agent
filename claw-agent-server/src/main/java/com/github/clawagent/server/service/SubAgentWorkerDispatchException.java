package com.github.clawagent.server.service;

import java.util.Map;

/**
 * 子 Agent worker 调度失败异常。
 * 除了错误文本，还携带主进程侧已知的 worker 审计元数据，便于任务失败时仍能回放 pid、耗时和退出原因。
 */
public class SubAgentWorkerDispatchException extends RuntimeException {
    private final Map<String, String> metadata;

    public SubAgentWorkerDispatchException(String message, Map<String, String> metadata) {
        super(message);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public SubAgentWorkerDispatchException(String message, Throwable cause, Map<String, String> metadata) {
        super(message, cause);
        this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Map<String, String> metadata() {
        return metadata;
    }
}
