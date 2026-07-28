package com.github.clawagent.server.service;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.core.PlanItem;
import com.github.clawagent.runtime.InMemoryAgentEventStore;
import com.github.clawagent.runtime.InMemoryPlanStore;
import com.github.clawagent.runtime.InMemoryTodoStore;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatOptions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanServiceTest {
    @Test
    void reviseRecordsPlanDiffSummaryInEvent() {
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        PlanService service = new PlanService(planStore, new InMemoryTodoStore(), eventStore, new TestPlanDraftPlanner());

        PlanDraft created = service.create("session-1", "开发计划模式", "plan", Map.of());
        PlanDraft revised = service.revise(created.id(), "第二步要增加校验");

        List<AgentEvent> events = eventStore.findEventsBySession("session-1", 10);
        AgentEvent updated = events.stream()
                .filter(event -> "plan.updated".equals(event.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(2, revised.version());
        assertEquals("1", updated.details().get("previousVersion"));
        assertEquals("2", updated.details().get("version"));
        assertEquals("2", updated.details().get("itemCountBefore"));
        assertEquals("3", updated.details().get("itemCountAfter"));
        assertTrue(updated.details().get("feedback").contains("第二步"));
        assertTrue(updated.details().get("addedItems").contains("3.补充验证"));
        assertTrue(updated.details().get("changedItems").contains("2.执行开发"));
        assertTrue(service.latestRevisionSummary(created.id()).orElseThrow().changedItems().contains("2.执行开发"));
    }

    @Test
    void createAppliesTemplateMetadataWithoutChangingExecutionRules() {
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        TestPlanDraftPlanner planner = new TestPlanDraftPlanner();
        PlanService service = new PlanService(new InMemoryPlanStore(), new InMemoryTodoStore(), eventStore, planner);

        PlanDraft created = service.create("session-1", "修复登录问题", "plan", "bugfix", Map.of("approvalMode", "ask"));

        // 模板只作为生成约束进入 Planner，计划生成后自动进入可执行状态，高危工具仍单独审批。
        assertEquals("bugfix", planner.lastMetadata.get("plan.templateId"));
        assertTrue(planner.lastMetadata.get("plan.templateInstruction").contains("定位根因"));
        assertFalse(service.templates().isEmpty());
        assertTrue(eventStore.findEventsBySession("session-1", 10).stream()
                .anyMatch(event -> "plan.created".equals(event.type())
                        && "bugfix".equals(event.details().get("templateId"))));
        assertEquals("APPROVED", created.status());
    }

    @Test
    void rerunningTheSamePlanVersionDoesNotCreateDuplicateTodos() {
        InMemoryPlanStore planStore = new InMemoryPlanStore();
        InMemoryTodoStore todoStore = new InMemoryTodoStore();
        PlanService service = new PlanService(planStore, todoStore, new InMemoryAgentEventStore(), new TestPlanDraftPlanner());

        PlanDraft plan = service.create("session-1", "修复登录问题", "plan", Map.of());
        service.approve(plan.id());
        service.markRunning(plan.id(), "task-1");
        service.markRunning(plan.id(), "task-2");

        // 恢复同一版本只切换执行任务，不应把同一组 Todo 再插入一次。
        assertEquals(2, todoStore.listTodoItems("session-1", "", 100).size());
        assertEquals("task-2", service.get(plan.id()).taskId());
    }

    @Test
    void executionInputTreatsPlanAsAlreadyAcceptedAndDoesNotRecreatePlan() {
        PlanService service = new PlanService(new InMemoryPlanStore(), new InMemoryTodoStore(), new InMemoryAgentEventStore(), new TestPlanDraftPlanner());

        PlanDraft plan = service.create("session-1", "设计 IM 加密方案", "plan", Map.of());
        String input = service.executionInput(plan);

        assertTrue(input.contains("计划已默认同意"));
        assertTrue(input.contains("禁止调用 builtin.todo.create_plan"));
        assertTrue(input.contains("按现有 Todo 顺序推进"));
    }

    private static final class TestPlanDraftPlanner extends PlanDraftPlanner {
        private Map<String, String> lastMetadata = Map.of();

        private TestPlanDraftPlanner() {
            super((messages, options) -> "", new ChatOptions("test", 0.0, 1), new AgentToolRegistry(List.of()));
        }

        @Override
        public PlanDraft createPlan(String sessionId, String input, String mode, Map<String, String> metadata) {
            lastMetadata = metadata;
            return plan(sessionId, 1, List.of(
                    item(1, "梳理需求", "确认范围", "low"),
                    item(2, "执行开发", "实现代码", "medium")
            ));
        }

        @Override
        public PlanDraft revisePlan(PlanDraft current, String feedback) {
            // 测试只关心 PlanService 是否记录修订摘要，Planner 结果用固定计划避免依赖模型。
            return current.nextVersion("测试计划", current.goal(), "已根据反馈调整",
                    List.of(
                            item(1, "梳理需求", "确认范围", "low"),
                            item(2, "执行开发", "实现代码并增加校验", "medium"),
                            item(3, "补充验证", "运行测试", "low")
                    ),
                    List.of(), List.of(), List.of("测试通过"));
        }

        private PlanDraft plan(String sessionId, int version, List<PlanItem> items) {
            Instant now = Instant.now();
            return new PlanDraft("plan-1", sessionId, "", "DRAFT", null, null, version,
                    "测试计划", "开发计划模式", "", items, List.of(), List.of(), List.of(), now, now);
        }

        private PlanItem item(int order, String title, String description, String riskLevel) {
            return new PlanItem("item-" + order, order, title, description, List.of(), List.of(), riskLevel, false);
        }
    }
}
