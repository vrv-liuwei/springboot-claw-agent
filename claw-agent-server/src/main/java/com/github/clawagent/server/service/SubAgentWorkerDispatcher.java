package com.github.clawagent.server.service;

import com.github.clawagent.core.AgentTask;
import com.github.clawagent.spring.ClawAgentProperties;

/**
 * 子 Agent worker 调度入口。
 * Controller 只判断是否需要隔离执行，具体进程协议、超时和输出解析交给实现类处理。
 */
public interface SubAgentWorkerDispatcher {
    boolean canDispatch(ClawAgentProperties.SubAgentWorker worker);

    SubAgentWorkerDispatchResult dispatch(AgentTask task, ClawAgentProperties.SubAgentWorker worker);
}
