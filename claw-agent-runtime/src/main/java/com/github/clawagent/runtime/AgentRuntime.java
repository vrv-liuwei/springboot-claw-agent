package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.SessionCreateRequest;

import java.util.List;
import java.util.Map;

public interface AgentRuntime {
    AgentResult submit(AgentRequest request);

    AgentResult submit(AgentRequest request, com.github.clawagent.spi.AgentCallback callback);

    AgentResult submitStream(AgentRequest request, com.github.clawagent.spi.AgentCallback callback, com.github.clawagent.spi.ChatStreamCallback streamCallback);

    AgentTask cancelTask(String taskId);

    String createSessionId();

    Map<String, Object> clearAllSessions();

    AgentTask getTask(String taskId);

    List<AgentStep> getSteps(String taskId);

    List<AgentMessage> getTaskMessages(String taskId, int limit);

    AgentSession createSession(SessionCreateRequest request);

    AgentSession getSession(String sessionId);

    List<AgentSession> listSessions(int limit);

    List<AgentTask> getSessionTasks(String sessionId, int limit);

    List<AgentMessage> getSessionMessages(String sessionId, int limit);

    AgentSession summarizeSession(String sessionId, int limit);

    List<AgentEvent> getSessionEvents(String sessionId, int limit);

    List<AgentEvent> getTaskEvents(String taskId, int limit);
}
