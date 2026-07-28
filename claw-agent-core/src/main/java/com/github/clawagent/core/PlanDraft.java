package com.github.clawagent.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PlanDraft 表示计划生成、修订和执行状态；计划生成后直接进入可执行状态，过程细节交给 Todo 和事件记录。
 *
 * @param id 计划 ID。
 * @param sessionId 会话 ID。
 * @param taskId 执行计划后关联的任务 ID。
 * @param status DRAFT（兼容旧数据）、APPROVED（待执行）、RUNNING、BLOCKED、DONE。
 * @param outcome completed、failed、cancelled 或空。
 * @param blockReason 阻塞原因，例如 approval_required。
 * @param version 计划版本号。
 * @param title 计划标题。
 * @param goal 用户目标。
 * @param summary 计划摘要。
 * @param items 主步骤列表。
 * @param assumptions 计划假设。
 * @param risks 风险说明。
 * @param validation 验证方式。
 * @param createdAt 创建时间。
 * @param updatedAt 更新时间。
 */
public record PlanDraft(
        String id,
        String sessionId,
        String taskId,
        String status,
        String outcome,
        String blockReason,
        int version,
        String title,
        String goal,
        String summary,
        List<PlanItem> items,
        List<String> assumptions,
        List<String> risks,
        List<String> validation,
        Instant createdAt,
        Instant updatedAt
) {
    public PlanDraft {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        status = status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase();
        version = Math.max(1, version);
        items = items == null ? List.of() : new ArrayList<>(items);
        assumptions = assumptions == null ? List.of() : new ArrayList<>(assumptions);
        risks = risks == null ? List.of() : new ArrayList<>(risks);
        validation = validation == null ? List.of() : new ArrayList<>(validation);
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public PlanDraft withStatus(String nextStatus, String nextOutcome, String nextBlockReason) {
        return new PlanDraft(id, sessionId, taskId, nextStatus, nextOutcome, nextBlockReason, version,
                title, goal, summary, items, assumptions, risks, validation, createdAt, Instant.now());
    }

    public PlanDraft withTaskId(String nextTaskId) {
        return new PlanDraft(id, sessionId, nextTaskId, status, outcome, blockReason, version,
                title, goal, summary, items, assumptions, risks, validation, createdAt, Instant.now());
    }

    public PlanDraft nextVersion(String nextTitle,
                                 String nextGoal,
                                 String nextSummary,
                                 List<PlanItem> nextItems,
                                 List<String> nextAssumptions,
                                 List<String> nextRisks,
                                 List<String> nextValidation) {
        return new PlanDraft(id, sessionId, taskId, "DRAFT", null, null, version + 1,
                nextTitle, nextGoal, nextSummary, nextItems, nextAssumptions, nextRisks, nextValidation,
                createdAt, Instant.now());
    }
}
