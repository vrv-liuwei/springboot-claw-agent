package com.github.clawagent.server.service;

import com.github.clawagent.core.TaskStatus;

import java.util.Map;

/**
 * 外部子 Agent worker 的稳定回传结果。
 *
 * @param answer 子任务最终回答。
 * @param status 子任务最终状态。
 * @param metadata worker 侧补充的审计元数据。
 */
public record SubAgentWorkerDispatchResult(String answer, TaskStatus status, Map<String, String> metadata) {
}
