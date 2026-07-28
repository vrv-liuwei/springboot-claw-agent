package com.github.clawagent.runtime;

import com.github.clawagent.core.PlanDraft;
import com.github.clawagent.spi.AgentDataCleaner;
import com.github.clawagent.spi.PlanStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 PlanStore，用于非 SQLite 场景的兜底。
 */
public class InMemoryPlanStore implements PlanStore, AgentDataCleaner {
    private final Map<String, PlanDraft> plans = new LinkedHashMap<>();

    @Override
    public synchronized void savePlan(PlanDraft plan) {
        plans.put(plan.id(), plan);
    }

    @Override
    public synchronized Optional<PlanDraft> findPlan(String planId) {
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public synchronized List<PlanDraft> listPlans(String sessionId, int limit) {
        return plans.values().stream()
                .filter(plan -> sessionId == null || sessionId.isBlank() || sessionId.equals(plan.sessionId()))
                .sorted(Comparator.comparing(PlanDraft::updatedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized void clearAllAgentData() {
        plans.clear();
    }
}
