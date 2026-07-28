package com.github.clawagent.server.service;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.core.TodoItem;
import com.github.clawagent.server.dto.PlanRevisionSummaryView;
import com.github.clawagent.server.dto.PlanTemplateView;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.PlanStore;
import com.github.clawagent.spi.TodoStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * PlanService 维护计划草稿、用户确认和 Todo 转换，不接管 Runtime 执行细节。
 */
@Service
public class PlanService {
    private static final List<PlanTemplate> BUILTIN_TEMPLATES = List.of(
            new PlanTemplate("local-dev", "开发任务", "适合改代码、跑编译、总结变更。", "plan",
                    "计划必须包含定位代码、修改实现、运行测试或编译、总结风险这几个阶段。"),
            new PlanTemplate("review-only", "只读审查", "适合先分析项目或方案，不写文件不执行高危命令。", "plan",
                    "计划必须保持只读，优先使用读取、搜索、审查类步骤，避免写文件和执行脚本。"),
            new PlanTemplate("bugfix", "缺陷修复", "适合复现问题、定位原因、修复并验证。", "plan",
                    "计划必须包含复现或确认错误、定位根因、最小修复、回归验证和失败时调整方案。"),
            new PlanTemplate("integration", "联调验证", "适合接口、通道、外部服务或前后端联调。", "plan",
                    "计划必须列出配置检查、接口调用、日志核对、失败重试和最终验收标准。")
    );

    private final PlanStore planStore;
    private final TodoStore todoStore;
    private final AgentEventStore eventStore;
    private final PlanDraftPlanner planner;

    public PlanService(@Qualifier("planStore") PlanStore planStore,
                       @Qualifier("todoStore") TodoStore todoStore,
                       @Qualifier("agentEventStore") AgentEventStore eventStore,
                       PlanDraftPlanner planner) {
        this.planStore = planStore;
        this.todoStore = todoStore;
        this.eventStore = eventStore;
        this.planner = planner;
    }

    public PlanDraft create(String sessionId, String input, String mode, Map<String, String> metadata) {
        return create(sessionId, input, mode, "", metadata);
    }

    public PlanDraft create(String sessionId, String input, String mode, String templateId, Map<String, String> metadata) {
        Map<String, String> enrichedMetadata = applyTemplateMetadata(templateId, metadata);
        // 计划模式只负责生成执行方案，不再额外阻塞用户确认；真正执行时仍由工具风险审批兜底。
        PlanDraft plan = autoApprove(planner.createPlan(sessionId, input, mode, enrichedMetadata));
        planStore.savePlan(plan);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("version", String.valueOf(plan.version()));
        if (enrichedMetadata.containsKey("plan.templateId")) {
            details.put("templateId", enrichedMetadata.get("plan.templateId"));
            details.put("templateTitle", enrichedMetadata.getOrDefault("plan.templateTitle", ""));
        }
        saveEvent(plan, "plan.created", "计划已生成并自动确认", details);
        return plan;
    }

    public List<PlanTemplateView> templates() {
        return BUILTIN_TEMPLATES.stream()
                .map(template -> new PlanTemplateView(template.id(), template.title(), template.description(), template.mode(), template.promptHint()))
                .toList();
    }

    public PlanDraft get(String planId) {
        return planStore.findPlan(planId).orElseThrow(() -> new IllegalArgumentException("计划不存在：" + planId));
    }

    public List<PlanDraft> list(String sessionId, int limit) {
        return planStore.listPlans(sessionId, Math.min(Math.max(limit, 1), 100));
    }

    public PlanDraft revise(String planId, String feedback) {
        PlanDraft current = get(planId);
        ensureDraft(current);
        // 自动确认后的计划仍可在启动前修订，修订结果继续保持可执行状态。
        PlanDraft revised = autoApprove(planner.revisePlan(current, feedback));
        planStore.savePlan(revised);
        saveEvent(revised, "plan.updated", "计划已根据用户反馈更新", revisionDetails(current, revised, feedback));
        return revised;
    }

    public Optional<PlanRevisionSummaryView> latestRevisionSummary(String planId) {
        PlanDraft plan = get(planId);
        return eventStore.findEventsBySession(plan.sessionId(), 200).stream()
                .filter(event -> "plan.updated".equals(event.type()))
                .filter(event -> planId.equals(event.details().get("planId")))
                .max(Comparator.comparing(AgentEvent::createdAt))
                .map(this::toRevisionSummary);
    }

    public PlanDraft approve(String planId) {
        PlanDraft plan = get(planId);
        if ("APPROVED".equalsIgnoreCase(plan.status())) {
            // 兼容旧版前端或重复请求，自动确认后的计划无需重复写确认事件。
            return plan;
        }
        ensureDraft(plan);
        PlanDraft approved = plan.withStatus("APPROVED", null, null);
        planStore.savePlan(approved);
        // 计划模式默认同意生成的执行计划，前端不再展示“确认计划”的阻塞步骤。
        saveEvent(approved, "plan.approved", "计划已自动确认", Map.of("version", String.valueOf(approved.version())));
        return approved;
    }

    public PlanDraft cancel(String planId) {
        PlanDraft plan = get(planId);
        PlanDraft cancelled = plan.withStatus("DONE", "cancelled", null);
        planStore.savePlan(cancelled);
        saveEvent(cancelled, "plan.done", "用户已取消计划", Map.of("outcome", "cancelled"));
        return cancelled;
    }

    public PlanDraft ensureRunnable(String planId) {
        PlanDraft plan = get(planId);
        if (!"APPROVED".equalsIgnoreCase(plan.status()) && !"RUNNING".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("计划未确认，不能执行：" + planId);
        }
        return plan;
    }

    public PlanDraft markRunning(String planId, String taskId) {
        PlanDraft plan = ensureRunnable(planId);
        if ("RUNNING".equalsIgnoreCase(plan.status()) && Objects.equals(plan.taskId(), taskId)) {
            // task.started 可能因 SSE/回调重入重复到达，同一任务不能重复写入启动事件和 Todo。
            return plan;
        }
        PlanDraft running = plan.withTaskId(taskId).withStatus("RUNNING", null, null);
        planStore.savePlan(running);
        saveEvent(running, "plan.started", "计划开始执行", Map.of("taskId", taskId));
        savePlanTodos(running, taskId);
        return running;
    }

    public PlanDraft markDone(String planId, String outcome) {
        PlanDraft plan = get(planId);
        PlanDraft done = plan.withStatus("DONE", outcome, null);
        planStore.savePlan(done);
        saveEvent(done, "plan.done", "计划执行结束", Map.of("outcome", outcome == null ? "" : outcome));
        return done;
    }

    public PlanDraft markBlocked(String planId, String blockReason) {
        PlanDraft plan = get(planId);
        PlanDraft blocked = plan.withStatus("BLOCKED", null, blockReason);
        planStore.savePlan(blocked);
        saveEvent(blocked, "plan.blocked", "计划执行被阻塞", Map.of("blockReason", blockReason == null ? "" : blockReason));
        return blocked;
    }

    public String executionInput(PlanDraft plan) {
        StringBuilder input = new StringBuilder();
        input.append("按当前任务计划执行，计划已默认同意，不要再次要求用户确认计划，也不要重新创建计划。计划ID=")
                .append(plan.id()).append("，版本=").append(plan.version()).append("。\n");
        input.append("禁止调用 builtin.todo.create_plan；本计划已由系统转换为 Todo，执行时按现有 Todo 顺序推进。\n");
        input.append("目标：").append(plan.goal()).append("\n");
        if (plan.summary() != null && !plan.summary().isBlank()) {
            input.append("计划摘要：").append(plan.summary()).append("\n");
        }
        input.append("执行步骤：\n");
        for (PlanItem item : plan.items()) {
            input.append(item.itemOrder()).append(". ").append(item.title()).append("：").append(item.description());
            input.append("；风险=").append(item.riskLevel());
            if (item.requiresApproval()) {
                input.append("；涉及风险操作时必须走工具审批");
            }
            input.append("\n");
        }
        if (!plan.assumptions().isEmpty()) {
            input.append("计划假设：").append(String.join("；", plan.assumptions())).append("\n");
        }
        if (!plan.validation().isEmpty()) {
            input.append("验收方式：").append(String.join("；", plan.validation())).append("\n");
        }
        input.append("执行要求：先处理第一个未完成 Todo，再逐步完成后续 Todo；每一步完成后更新 Todo 状态。");
        input.append("如果当前任务是方案输出类，可以输出方案正文，但必须按计划步骤组织，不能用一篇泛泛方案替代 Todo 执行。");
        input.append("如果需要扩大计划范围、高危命令、敏感路径或安装/删除操作，仍按工具审批流程处理。");
        return input.toString();
    }

    public Map<String, String> planMetadata(PlanDraft plan) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("plan.id", plan.id());
        metadata.put("plan.version", String.valueOf(plan.version()));
        metadata.put("plan.status", plan.status());
        return metadata;
    }

    private synchronized void savePlanTodos(PlanDraft plan, String taskId) {
        List<TodoItem> existingItems = todoStore.listTodoItems(plan.sessionId(), "", 1000).stream()
                .filter(item -> plan.id().equals(item.metadata().get("planId"))
                        && String.valueOf(plan.version()).equals(item.metadata().get("planVersion")))
                .toList();
        Set<Integer> existingOrders = new LinkedHashSet<>();
        existingItems.forEach(item -> existingOrders.add(item.itemOrder()));
        // 恢复同一版本时复用已有状态；如果上次只写入了一部分 Todo，只补缺失步骤，避免重复插入。
        List<PlanItem> missingItems = plan.items().stream()
                .filter(item -> !existingOrders.contains(item.itemOrder()))
                .toList();
        if (missingItems.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        List<TodoItem> items = missingItems.stream()
                .map(item -> {
                    Map<String, String> metadata = new LinkedHashMap<>();
                    metadata.put("planId", plan.id());
                    metadata.put("planVersion", String.valueOf(plan.version()));
                    metadata.put("planItemId", item.id());
                    metadata.put("source", "planned");
                    metadata.put("riskLevel", item.riskLevel());
                    metadata.put("requiresApproval", String.valueOf(item.requiresApproval()));
                    return new TodoItem(UUID.randomUUID().toString(), plan.sessionId(), taskId, item.itemOrder(),
                            item.title(), item.description(), "pending", metadata, now, now);
                })
                .toList();
        todoStore.saveTodoItems(items);
    }

    private Map<String, String> revisionDetails(PlanDraft previous, PlanDraft revised, String feedback) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("previousVersion", String.valueOf(previous.version()));
        details.put("version", String.valueOf(revised.version()));
        details.put("feedback", preview(feedback, 500));
        details.put("itemCountBefore", String.valueOf(previous.items().size()));
        details.put("itemCountAfter", String.valueOf(revised.items().size()));

        Map<Integer, PlanItem> before = itemsByOrder(previous.items());
        Map<Integer, PlanItem> after = itemsByOrder(revised.items());
        // 计划修订只记录摘要，详细计划仍从 PlanStore 读取，避免事件表塞入大块 JSON。
        details.put("addedItems", summarizeAdded(before, after));
        details.put("removedItems", summarizeRemoved(before, after));
        details.put("changedItems", summarizeChanged(before, after));
        return details;
    }

    private Map<String, String> applyTemplateMetadata(String templateId, Map<String, String> metadata) {
        Map<String, String> enriched = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (templateId == null || templateId.isBlank()) {
            return enriched;
        }
        findTemplate(templateId).ifPresent(template -> {
            // 模板只约束计划生成，不跳过用户确认、工具审批和运行时权限合并。
            enriched.put("plan.templateId", template.id());
            enriched.put("plan.templateTitle", template.title());
            enriched.put("plan.templateInstruction", template.instruction());
        });
        return enriched;
    }

    private Optional<PlanTemplate> findTemplate(String templateId) {
        String normalized = templateId == null ? "" : templateId.trim();
        return BUILTIN_TEMPLATES.stream()
                .filter(template -> template.id().equalsIgnoreCase(normalized))
                .findFirst();
    }

    private PlanRevisionSummaryView toRevisionSummary(AgentEvent event) {
        Map<String, String> details = event.details();
        return new PlanRevisionSummaryView(
                details.getOrDefault("planId", ""),
                parseInt(details.get("previousVersion")),
                parseInt(details.get("version")),
                details.getOrDefault("feedback", ""),
                parseInt(details.get("itemCountBefore")),
                parseInt(details.get("itemCountAfter")),
                details.getOrDefault("addedItems", "[]"),
                details.getOrDefault("removedItems", "[]"),
                details.getOrDefault("changedItems", "[]"),
                event.createdAt());
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Map<Integer, PlanItem> itemsByOrder(List<PlanItem> items) {
        Map<Integer, PlanItem> result = new LinkedHashMap<>();
        for (PlanItem item : items) {
            result.put(item.itemOrder(), item);
        }
        return result;
    }

    private String summarizeAdded(Map<Integer, PlanItem> before, Map<Integer, PlanItem> after) {
        return after.entrySet().stream()
                .filter(entry -> !before.containsKey(entry.getKey()))
                .map(entry -> entry.getKey() + "." + preview(entry.getValue().title(), 40))
                .toList()
                .toString();
    }

    private String summarizeRemoved(Map<Integer, PlanItem> before, Map<Integer, PlanItem> after) {
        return before.entrySet().stream()
                .filter(entry -> !after.containsKey(entry.getKey()))
                .map(entry -> entry.getKey() + "." + preview(entry.getValue().title(), 40))
                .toList()
                .toString();
    }

    private String summarizeChanged(Map<Integer, PlanItem> before, Map<Integer, PlanItem> after) {
        return after.entrySet().stream()
                .filter(entry -> before.containsKey(entry.getKey()))
                .filter(entry -> !samePlanItem(before.get(entry.getKey()), entry.getValue()))
                .map(entry -> entry.getKey() + "." + preview(entry.getValue().title(), 40))
                .toList()
                .toString();
    }

    private boolean samePlanItem(PlanItem left, PlanItem right) {
        return Objects.equals(left.title(), right.title())
                && Objects.equals(left.description(), right.description())
                && Objects.equals(left.expectedTools(), right.expectedTools())
                && Objects.equals(left.expectedFileChanges(), right.expectedFileChanges())
                && Objects.equals(left.riskLevel(), right.riskLevel())
                && left.requiresApproval() == right.requiresApproval();
    }

    private void ensureDraft(PlanDraft plan) {
        if (!"DRAFT".equalsIgnoreCase(plan.status()) && !"APPROVED".equalsIgnoreCase(plan.status())) {
            throw new IllegalStateException("计划已开始执行，不能再修改：" + plan.id());
        }
    }

    private PlanDraft autoApprove(PlanDraft plan) {
        if (plan == null || !"DRAFT".equalsIgnoreCase(plan.status())) {
            return plan;
        }
        return plan.withStatus("APPROVED", null, null);
    }

    private void saveEvent(PlanDraft plan, String type, String message, Map<String, String> details) {
        Map<String, String> eventDetails = new LinkedHashMap<>(details == null ? Map.of() : details);
        eventDetails.put("planId", plan.id());
        eventDetails.put("planVersion", String.valueOf(plan.version()));
        eventStore.saveEvent(new AgentEvent(UUID.randomUUID().toString(), plan.sessionId(), plan.taskId(), "info", type, message, eventDetails));
    }

    private String preview(String value, int maxLength) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private record PlanTemplate(String id, String title, String description, String mode, String instruction) {
        private String promptHint() {
            return instruction;
        }
    }
}
