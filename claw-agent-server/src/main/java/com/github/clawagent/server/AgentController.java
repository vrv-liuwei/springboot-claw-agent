package com.github.clawagent.server;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.SessionCreateRequest;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.mcp.McpImportRequest;
import com.github.clawagent.mcp.McpServerConfig;
import com.github.clawagent.mcp.McpServerRegistration;
import com.github.clawagent.mcp.McpPromptDescriptor;
import com.github.clawagent.mcp.McpPromptContent;
import com.github.clawagent.mcp.McpPromptGetRequest;
import com.github.clawagent.mcp.McpResourceDescriptor;
import com.github.clawagent.mcp.McpResourceContent;
import com.github.clawagent.mcp.McpToolDescriptor;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.skill.SkillPackage;
import com.github.clawagent.skill.SkillRegistration;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.TodoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M1 管理 API。
 * 后续 Gateway/Auth/Channel 会包裹这些接口；当前先保证本地平台可直接验证运行链路。
 */
@RestController
@RequestMapping("/api/v1")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRuntime runtime;
    private final AgentToolRegistry toolRegistry;
    private final McpRegistry mcpRegistry;
    private final SkillRegistry skillRegistry;
    private final TodoStore todoStore;
    /** 流式任务后台执行池；避免阻塞 Spring MVC 请求线程。 */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public AgentController(AgentRuntime runtime, AgentToolRegistry toolRegistry, McpRegistry mcpRegistry, SkillRegistry skillRegistry, TodoStore todoStore) {
        this.runtime = runtime;
        this.toolRegistry = toolRegistry;
        this.mcpRegistry = mcpRegistry;
        this.skillRegistry = skillRegistry;
        this.todoStore = todoStore;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "name", "clawagent");
    }

    @PostMapping("/tasks")
    public AgentResult submit(@RequestBody AgentRequest request) {
        log.info("agent task submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        AgentResult result = runtime.submit(request);
        log.info("agent task submit finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    @PostMapping("/tasks/stream")
    public SseEmitter submitStream(@RequestBody AgentRequest request) {
        log.info("agent task stream submit received sessionId={} channelId={} userId={} input={}",
                request.sessionId(), request.channelId(), request.userId(), preview(request.input()));
        SseEmitter emitter = new SseEmitter(0L);
        streamExecutor.submit(() -> {
            try {
                AgentResult result = runtime.submitStream(request, new AgentCallback() {
                            @Override
                            public void onEvent(String eventType, String taskId, String message) {
                                onEvent(eventType, taskId, message, Map.of());
                            }

                            @Override
                            public void onEvent(String eventType, String taskId, String message, Map<String, String> details) {
                                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                                payload.put("eventType", eventType);
                                payload.put("taskId", nullToEmpty(taskId));
                                payload.put("message", nullToEmpty(message));
                                if (details != null) {
                                    payload.putAll(details);
                                }
                                sendSse(emitter, eventType, payload);
                            }
                        },
                        new com.github.clawagent.spi.ChatStreamCallback() {
                            @Override
                            public void onDelta(String content) {
                                String delta = normalizeStreamContent(content);
                                if (!delta.isBlank()) {
                                    // 模型流式协议里可能出现 null 片段，后端先过滤，避免前端聊天窗口出现 nullnull。
                                    sendSse(emitter, "llm.delta", Map.of("content", delta));
                                }
                            }

                            @Override
                            public void onComplete(String content) {
                                sendSse(emitter, "llm.completed", Map.of("length", String.valueOf(nullToEmpty(content).length())));
                            }
                        });
                sendSse(emitter, "result", Map.of(
                        "taskId", result.taskId(),
                        "status", result.status().name(),
                        "answer", result.answer()));
                emitter.complete();
            } catch (RuntimeException e) {
                sendSse(emitter, "error", Map.of("message", nullToEmpty(e.getMessage())));
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/assistant")
    public AgentResult assistant(@RequestParam("query") String query) {
        log.info("assistant query received input={}", preview(query));
        AgentResult result = runtime.submit(AgentRequest.userMessage(query));
        log.info("assistant query finished taskId={} status={}", result.taskId(), result.status());
        return result;
    }

    @PostMapping("/sessions")
    public AgentSession createSession(@RequestBody SessionCreateRequest request) {
        log.info("session create received channelId={} userId={} title={}", request.channelId(), request.userId(), preview(request.title()));
        return runtime.createSession(request);
    }

    @PostMapping("/sessions/id")
    public Map<String, String> createSessionId() {
        String sessionId = runtime.createSessionId();
        log.info("session id allocated sessionId={}", sessionId);
        return Map.of("sessionId", sessionId);
    }

    @DeleteMapping("/sessions")
    public Map<String, Object> clearSessions() {
        log.warn("session clear all requested");
        Map<String, Object> result = runtime.clearAllSessions();
        if (todoStore instanceof AgentDataCleaner cleaner) {
            // TodoStore 可能是独立内存实现；SQLite 场景下重复 delete 空表也是安全的。
            cleaner.clearAllAgentData();
        }
        return result;
    }

    @GetMapping("/sessions")
    public List<AgentSession> sessions(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        log.debug("session list requested limit={}", limit);
        return runtime.listSessions(limit);
    }

    @GetMapping("/sessions/{sessionId}")
    public AgentSession session(@PathVariable("sessionId") String sessionId) {
        return runtime.getSession(sessionId);
    }

    @GetMapping("/sessions/{sessionId}/tasks")
    public List<AgentTask> sessionTasks(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return runtime.getSessionTasks(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<AgentMessage> sessionMessages(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return runtime.getSessionMessages(sessionId, limit);
    }

    @PostMapping("/sessions/{sessionId}/summary")
    public AgentSession summarizeSession(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "80") int limit) {
        log.info("session summary requested sessionId={} limit={}", sessionId, limit);
        return runtime.summarizeSession(sessionId, limit);
    }

    @GetMapping("/sessions/{sessionId}/events")
    public List<AgentEvent> sessionEvents(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        log.debug("session events requested sessionId={} limit={}", sessionId, limit);
        return runtime.getSessionEvents(sessionId, limit);
    }

    @PostMapping("/mcp/servers")
    public McpServerRegistration registerMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server register received id={} name={} transport={}", config.id(), config.name(), config.transport());
        return mcpRegistry.register(config);
    }

    @PostMapping("/mcp/servers/test")
    public McpServerRegistration testMcpServer(@RequestBody McpServerConfig config) {
        log.info("mcp server test received id={} name={} transport={}", config.id(), config.name(), config.transport());
        return mcpRegistry.test(config);
    }

    @PostMapping("/mcp/import")
    public List<McpServerRegistration> importMcpServers(@RequestBody McpImportRequest request) {
        log.info("mcp import requested");
        return mcpRegistry.importServers(request.json());
    }

    @GetMapping("/mcp/servers")
    public List<McpServerRegistration> mcpServers() {
        return mcpRegistry.list();
    }

    @GetMapping("/mcp/servers/{serverId}")
    public McpServerRegistration mcpServer(@PathVariable("serverId") String serverId) {
        return mcpRegistry.find(serverId).orElseThrow(() -> new IllegalArgumentException("MCP 服务不存在：" + serverId));
    }

    @PostMapping("/mcp/servers/{serverId}/connect")
    public McpServerRegistration connectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server connect requested id={}", serverId);
        return mcpRegistry.connect(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/disconnect")
    public McpServerRegistration disconnectMcpServer(@PathVariable("serverId") String serverId) {
        log.info("mcp server disconnect requested id={}", serverId);
        return mcpRegistry.disconnect(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-tools")
    public List<McpToolDescriptor> refreshMcpTools(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh tools requested id={}", serverId);
        return mcpRegistry.refreshTools(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/tools")
    public List<McpToolDescriptor> mcpTools(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listTools(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-resources")
    public List<McpResourceDescriptor> refreshMcpResources(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh resources requested id={}", serverId);
        return mcpRegistry.refreshResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources")
    public List<McpResourceDescriptor> mcpResources(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listResources(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/resources/read")
    public McpResourceContent readMcpResource(
            @PathVariable("serverId") String serverId,
            @RequestParam("uri") String uri) {
        log.info("mcp resource read requested id={} uri={}", serverId, uri);
        return mcpRegistry.readResource(serverId, uri);
    }

    @PostMapping("/mcp/servers/{serverId}/refresh-prompts")
    public List<McpPromptDescriptor> refreshMcpPrompts(@PathVariable("serverId") String serverId) {
        log.info("mcp server refresh prompts requested id={}", serverId);
        return mcpRegistry.refreshPrompts(serverId);
    }

    @GetMapping("/mcp/servers/{serverId}/prompts")
    public List<McpPromptDescriptor> mcpPrompts(@PathVariable("serverId") String serverId) {
        return mcpRegistry.listPrompts(serverId);
    }

    @PostMapping("/mcp/servers/{serverId}/prompts/{promptName}/get")
    public McpPromptContent getMcpPrompt(
            @PathVariable("serverId") String serverId,
            @PathVariable("promptName") String promptName,
            @RequestBody(required = false) McpPromptGetRequest request) {
        log.info("mcp prompt get requested id={} name={}", serverId, promptName);
        Map<String, Object> arguments = request == null ? Map.of() : request.arguments();
        return mcpRegistry.getPrompt(serverId, promptName, arguments);
    }

    @PostMapping("/skills")
    public SkillRegistration installSkill(@RequestBody SkillPackage skillPackage) {
        log.info("skill install received id={} name={}",
                skillPackage.manifest() == null ? null : skillPackage.manifest().id(),
                skillPackage.manifest() == null ? null : skillPackage.manifest().name());
        return skillRegistry.install(skillPackage);
    }

    @GetMapping("/skills")
    public List<SkillRegistration> skills() {
        return skillRegistry.list();
    }

    @GetMapping("/skills/{skillId}")
    public SkillRegistration skill(@PathVariable("skillId") String skillId) {
        return skillRegistry.find(skillId).orElseThrow(() -> new IllegalArgumentException("Skill 不存在：" + skillId));
    }

    @PostMapping("/skills/{skillId}/enable")
    public SkillRegistration enableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill enable requested id={}", skillId);
        return skillRegistry.enable(skillId);
    }

    @PostMapping("/skills/{skillId}/disable")
    public SkillRegistration disableSkill(@PathVariable("skillId") String skillId) {
        log.info("skill disable requested id={}", skillId);
        return skillRegistry.disable(skillId);
    }

    @GetMapping("/tasks/{taskId}")
    public AgentTask task(@PathVariable("taskId") String taskId) {
        return runtime.getTask(taskId);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public AgentTask cancelTask(@PathVariable("taskId") String taskId) {
        log.warn("task cancel requested taskId={}", taskId);
        return runtime.cancelTask(taskId);
    }

    @GetMapping("/tasks/{taskId}/steps")
    public List<AgentStep> steps(@PathVariable("taskId") String taskId) {
        return runtime.getSteps(taskId);
    }

    @GetMapping("/tasks/{taskId}/messages")
    public List<AgentMessage> taskMessages(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.debug("task messages requested taskId={} limit={}", taskId, limit);
        return runtime.getTaskMessages(taskId, limit);
    }

    @GetMapping("/tasks/{taskId}/events")
    public List<AgentEvent> taskEvents(
            @PathVariable("taskId") String taskId,
            @RequestParam(name = "limit", defaultValue = "200") int limit,
            @RequestParam(name = "todoId", required = false) String todoId,
            @RequestParam(name = "stepId", required = false) String stepId) {
        log.debug("task events requested taskId={} limit={} todoId={} stepId={}", taskId, limit, todoId, stepId);
        List<AgentEvent> events = runtime.getTaskEvents(taskId, limit);
        if (todoId != null && !todoId.isBlank()) {
            events = events.stream()
                    .filter(event -> todoId.equals(event.details().get("todoId")))
                    .toList();
        }
        if (stepId != null && !stepId.isBlank()) {
            events = events.stream()
                    .filter(event -> stepId.equals(event.details().get("stepId")))
                    .toList();
        }
        return events;
    }

    @GetMapping("/tools")
    public Object tools() {
        log.debug("tool definitions requested");
        return toolRegistry.definitions();
    }

    @GetMapping("/todos")
    public List<TodoItem> todos(
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        log.trace("todo list requested sessionId={} taskId={} limit={}", sessionId, taskId, limit);
        return todoStore.listTodoItems(sessionId, taskId, limit);
    }

    @PostMapping("/todos/{todoId}/status")
    public TodoItem updateTodoStatus(
            @PathVariable("todoId") String todoId,
            @RequestBody Map<String, String> body) {
        String status = body == null ? "" : body.getOrDefault("status", "");
        log.info("todo status update requested id={} status={}", todoId, status);
        return todoStore.updateTodoStatus(todoId, status);
    }

    private String preview(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }

    private void sendSse(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 发送失败：" + e.getMessage(), e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalizeStreamContent(String content) {
        if (content == null) {
            return "";
        }
        String text = content.trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return content.replaceFirst("(?i)^(null)+", "");
    }
}
