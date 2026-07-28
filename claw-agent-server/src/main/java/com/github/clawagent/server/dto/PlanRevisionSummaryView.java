package com.github.clawagent.server.dto;

import java.time.Instant;

/**
 * 计划修订差异摘要，用于前端展示“本次计划改了什么”。
 */
public record PlanRevisionSummaryView(
        String planId,
        int previousVersion,
        int version,
        String feedback,
        int itemCountBefore,
        int itemCountAfter,
        String addedItems,
        String removedItems,
        String changedItems,
        Instant updatedAt
) {
}
