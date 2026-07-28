package com.github.clawagent.server.service;

import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatOptions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanDraftPlannerTest {

    @Test
    void modelRequestFailureIsDifferentFromInvalidModelResponse() {
        PlanDraftPlanner planner = new PlanDraftPlanner(
                (messages, options) -> {
                    throw new IllegalStateException("model unavailable");
                },
                new ChatOptions("test", 0.0, 1),
                new AgentToolRegistry(List.of()));

        PlanDraft draft = planner.createPlan("session-1", "定位启动失败", "plan", null);

        assertEquals("模型计划请求失败，已按需求类型生成可执行计划。", draft.summary());
        assertTrue(draft.items().size() >= 4);
        assertTrue(draft.items().stream().anyMatch(item -> item.title().contains("定位根因")));
        assertTrue(draft.items().stream().allMatch(item -> item.expectedTools().isEmpty()));
    }

    @Test
    void invalidModelResponseDoesNotClaimThatTheModelRequestFailed() {
        PlanDraftPlanner planner = new PlanDraftPlanner(
                (messages, options) -> "{\"title\":\"空计划\",\"items\":[]}",
                new ChatOptions("test", 0.0, 1),
                new AgentToolRegistry(List.of()));

        PlanDraft draft = planner.createPlan("session-1", "定位启动失败", "plan", null);

        assertEquals("模型计划返回无法解析，已按需求类型生成可执行计划。", draft.summary());
        assertTrue(draft.items().size() >= 4);
        assertTrue(draft.items().stream().allMatch(item -> item.expectedTools().isEmpty()));
    }

    @Test
    void fallbackPlanForDesignRequestLooksLikeARealTaskPlan() {
        PlanDraftPlanner planner = new PlanDraftPlanner(
                (messages, options) -> {
                    throw new IllegalStateException("model unavailable");
                },
                new ChatOptions("test", 0.0, 1),
                new AgentToolRegistry(List.of()));

        PlanDraft draft = planner.createPlan("session-1", "设计一个 IM 端到端加密通讯方案", "plan", null);

        assertTrue(draft.items().stream().anyMatch(item -> item.title().contains("核心业务场景")));
        assertTrue(draft.items().stream().anyMatch(item -> item.title().contains("安全机制")));
        assertTrue(draft.validation().get(0).contains("计划步骤"));
    }

    @Test
    void missingProjectPathMetadataDoesNotBreakModelPlanRequest() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("activeProjectPath", null);
        metadata.put("projectPath", null);
        metadata.put("workspace.projectPath", null);
        metadata.put("approvalMode", null);

        PlanDraftPlanner planner = new PlanDraftPlanner(
                (messages, options) -> """
                        {"title":"计划：测试","goal":"测试目标","summary":"模型正常返回","items":[{"title":"梳理范围","description":"确认目标和边界","riskLevel":"low","requiresApproval":false}],"assumptions":[],"risks":[],"validation":["检查计划"]}
                        """,
                new ChatOptions("test", 0.0, 1),
                new AgentToolRegistry(List.of()));

        PlanDraft draft = planner.createPlan("session-1", "设计方案", "grounded", metadata);

        assertEquals("模型正常返回", draft.summary());
        assertEquals("梳理范围", draft.items().get(0).title());
    }
}
