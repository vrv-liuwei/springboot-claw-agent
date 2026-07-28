package com.github.clawagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * PlanItem 是计划模式里的主步骤，后续执行时会转换成 Todo。
 *
 * @param id 步骤 ID。
 * @param itemOrder 步骤顺序。
 * @param title 步骤标题。
 * @param description 步骤说明。
 * @param expectedTools 预计会使用的工具 ID。
 * @param expectedFileChanges 预计会涉及的文件或目录。
 * @param riskLevel 风险等级，保持和工具风险 low/medium/high 对齐。
 * @param requiresApproval 是否预期需要用户审批。
 */
public record PlanItem(
        String id,
        int itemOrder,
        String title,
        String description,
        List<String> expectedTools,
        List<String> expectedFileChanges,
        String riskLevel,
        boolean requiresApproval
) {
    public PlanItem {
        expectedTools = expectedTools == null ? List.of() : new ArrayList<>(expectedTools);
        expectedFileChanges = expectedFileChanges == null ? List.of() : new ArrayList<>(expectedFileChanges);
        riskLevel = riskLevel == null || riskLevel.isBlank() ? "low" : riskLevel.trim().toLowerCase();
    }
}
