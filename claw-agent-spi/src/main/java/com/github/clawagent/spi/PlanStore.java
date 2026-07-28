package com.github.clawagent.spi;

import com.github.clawagent.core.PlanDraft;

import java.util.List;
import java.util.Optional;

/**
 * PlanStore 保存计划模式的草稿、版本和执行状态。
 */
public interface PlanStore {
    void savePlan(PlanDraft plan);

    Optional<PlanDraft> findPlan(String planId);

    List<PlanDraft> listPlans(String sessionId, int limit);
}
