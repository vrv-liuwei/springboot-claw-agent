package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.server.dto.AgentOrchestrationGraphView;
import com.github.clawagent.server.dto.SubAgentTaskRequest;
import com.github.clawagent.server.dto.SubAgentTaskResponse;
import com.github.clawagent.spi.TaskStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Agent 编排接口。
 * 当前先提供父任务创建只读子任务的能力，不引入独立 worker 调度器。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentOrchestrationController {
    private static final String DEFAULT_ROLE = "subagent";
    private static final String READ_ONLY_ISOLATION = "read-only";
    private static final int MAX_GRAPH_NODES = 500;

    private final AgentRuntime runtime;
    private final TaskStore taskStore;

    public AgentOrchestrationController(AgentRuntime runtime,
                                        @Qualifier("taskStore") TaskStore taskStore) {
        this.runtime = runtime;
        this.taskStore = taskStore;
    }

    /**
     * 从父任务派生一个只读子 Agent 任务。
     * 子任务继承项目上下文，但不继承高危工具批准，避免父任务授权被横向扩散。
     */
    @PostMapping("/{parentTaskId}/subtasks")
    public SubAgentTaskResponse createSubTask(@PathVariable String parentTaskId,
                                              @RequestBody SubAgentTaskRequest request) {
        AgentTask parent = runtime.getTask(parentTaskId);
        String role = normalize(request.role(), DEFAULT_ROLE);
        Map<String, String> metadata = buildChildMetadata(parent, request, role);
        AgentRequest childRequest = new AgentRequest(
                decorateInput(request.input(), role),
                parent.sessionId(),
                normalize(parent.channelId(), "webui"),
                normalize(parent.userId(), "console"),
                metadata);

        AgentResult result = runtime.submit(childRequest);
        AgentTask child = runtime.getTask(result.taskId());
        recordSubTaskEvent(parent, child, role);
        return new SubAgentTaskResponse(parent.id(), child.id(), role, READ_ONLY_ISOLATION, result, child);
    }

    /**
     * 查询某个父任务派生出的子 Agent 任务。
     */
    @GetMapping("/{parentTaskId}/subtasks")
    public List<AgentTask> subTasks(@PathVariable String parentTaskId,
                                    @RequestParam(name = "limit", defaultValue = "100") int limit) {
        runtime.getTask(parentTaskId);
        return taskStore.findSubTasks(parentTaskId, safeLimit(limit));
    }

    /**
     * 查询父子 Agent 编排图。
     * 图结构从任务 metadata 里的 agent.parentTaskId 派生，便于前端展示当前任务拆分状态。
     */
    @GetMapping("/{rootTaskId}/graph")
    public AgentOrchestrationGraphView graph(@PathVariable String rootTaskId,
                                             @RequestParam(name = "depth", defaultValue = "3") int depth) {
        AgentTask root = runtime.getTask(rootTaskId);
        int maxDepth = Math.min(Math.max(depth, 1), 8);
        List<AgentOrchestrationGraphView.Node> nodes = new ArrayList<>();
        List<AgentOrchestrationGraphView.Edge> edges = new ArrayList<>();
        Queue<TaskDepth> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        boolean truncated = false;

        queue.add(new TaskDepth(root, 0));
        while (!queue.isEmpty()) {
            TaskDepth current = queue.poll();
            AgentTask task = current.task();
            if (task == null || !visited.add(task.id())) {
                continue;
            }
            nodes.add(toNode(task, current.depth()));
            if (nodes.size() >= MAX_GRAPH_NODES) {
                truncated = true;
                break;
            }
            if (current.depth() >= maxDepth) {
                continue;
            }
            // 子任务查询复用 TaskStore，避免维护第二份编排关系表。
            for (AgentTask child : taskStore.findSubTasks(task.id(), MAX_GRAPH_NODES)) {
                edges.add(new AgentOrchestrationGraphView.Edge(
                        task.id(),
                        child.id(),
                        normalize(child.metadata().get("agent.role"), DEFAULT_ROLE),
                        normalize(child.metadata().get("agent.isolation"), READ_ONLY_ISOLATION)));
                queue.add(new TaskDepth(child, current.depth() + 1));
            }
        }
        return toGraph(rootTaskId, maxDepth, truncated, nodes, edges);
    }

    private Map<String, String> buildChildMetadata(AgentTask parent, SubAgentTaskRequest request, String role) {
        Map<String, String> metadata = new LinkedHashMap<>();
        copyIfPresent(parent.metadata(), metadata,
                "workspaceId", "workspace.id", "workspaceName", "workspace.name",
                "workspaceRoot", "workspace.root", "workspace.projectPath",
                "projectPath", "activeProjectPath", "cwd",
                "knowledge.enabled", "knowledge.documentIds", "knowledge.scope", "knowledge.intent",
                "attachmentIds", "attachmentKnowledgeDocumentIds", "attachments");
        if (request.metadata() != null) {
            metadata.putAll(request.metadata());
        }
        metadata.put("agent.kind", "subagent");
        metadata.put("agent.parentTaskId", parent.id());
        metadata.put("agent.rootTaskId", normalize(parent.metadata().get("agent.rootTaskId"), parent.id()));
        metadata.put("agent.role", role);
        metadata.put("agent.isolation", READ_ONLY_ISOLATION);
        metadata.put("toolPermissionMode", "ask");
        metadata.put("policy.approval.source", "agent-isolation:" + READ_ONLY_ISOLATION);
        metadata.put("policy.approval.scope", "agent");
        metadata.put("policy.resolutionOrder", "local>channel>task>agent-isolation>tool-enforcement");
        metadata.put("policy.overrideReason", "只读子 Agent 不继承父任务高危批准。");
        metadata.remove("approvedToolIds");
        metadata.remove("allowHighRiskTools");
        return metadata;
    }

    private void copyIfPresent(Map<String, String> source, Map<String, String> target, String... keys) {
        for (String key : keys) {
            String value = source.get(key);
            if (value != null && !value.isBlank()) {
                target.put(key, value);
            }
        }
    }

    private void recordSubTaskEvent(AgentTask parent, AgentTask child, String role) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("childTaskId", child.id());
        details.put("role", role);
        details.put("isolation", READ_ONLY_ISOLATION);
        runtime.recordTaskEvent(parent.id(), "INFO", "agent.subtask_created", "子 Agent 任务已创建", details);
    }

    private String decorateInput(String input, String role) {
        return "[子 Agent role=" + role + " isolation=" + READ_ONLY_ISOLATION + "]\n" + normalize(input, "");
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }

    private AgentOrchestrationGraphView.Node toNode(AgentTask task, int depth) {
        return new AgentOrchestrationGraphView.Node(
                task.id(),
                task.metadata().get("agent.parentTaskId"),
                normalize(task.metadata().get("agent.role"), depth == 0 ? "root" : DEFAULT_ROLE),
                normalize(task.metadata().get("agent.isolation"), depth == 0 ? "root" : READ_ONLY_ISOLATION),
                task.status(),
                task.input(),
                depth,
                task.createdAt(),
                task.updatedAt());
    }

    private AgentOrchestrationGraphView toGraph(String rootTaskId,
                                                int maxDepth,
                                                boolean truncated,
                                                List<AgentOrchestrationGraphView.Node> nodes,
                                                List<AgentOrchestrationGraphView.Edge> edges) {
        long running = nodes.stream().filter(node -> node.status() != null
                && ("RUNNING".equals(node.status().name()) || "PENDING".equals(node.status().name()))).count();
        long waiting = nodes.stream().filter(node -> node.status() != null
                && ("WAITING_APPROVAL".equals(node.status().name()) || "CONTINUATION_REQUIRED".equals(node.status().name()))).count();
        long completed = nodes.stream().filter(node -> node.status() != null && "COMPLETED".equals(node.status().name())).count();
        long failed = nodes.stream().filter(node -> node.status() != null
                && ("FAILED".equals(node.status().name()) || "CANCELLED".equals(node.status().name()))).count();
        return new AgentOrchestrationGraphView(
                rootTaskId,
                nodes.size(),
                (int) running,
                (int) waiting,
                (int) completed,
                (int) failed,
                maxDepth,
                truncated,
                List.copyOf(nodes),
                List.copyOf(edges));
    }

    private record TaskDepth(AgentTask task, int depth) {
    }
}
