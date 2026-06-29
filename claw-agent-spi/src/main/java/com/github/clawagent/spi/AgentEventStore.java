package com.github.clawagent.spi;

import com.github.clawagent.core.AgentEvent;

import java.time.Instant;
import java.util.List;

/**
 * 运行事件存储。
 * 用于保存可审计的会话日志，包括工具调用链、LLM 请求响应和 token 用量。
 */
public interface AgentEventStore {
    void saveEvent(AgentEvent event);

    List<AgentEvent> findEventsBySession(String sessionId, int limit);

    List<AgentEvent> findEventsByTask(String taskId, int limit);

    List<AgentEvent> findEvents(Instant from, Instant to, String level, String type, String sessionId, String taskId, int limit);
}
