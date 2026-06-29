package com.github.clawagent.core;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具审批策略。
 * 这是对现有 permission-mode、approvalMode、approvedToolIds metadata 的正式领域化表达。
 *
 * @param mode 审批模式：ask、auto、full、custom。
 * @param approvedToolIds custom 或单次审批放行的工具 ID 集合。
 * @param legacyAllowHighRisk 兼容早期 allowHighRiskTools metadata。
 */
public record ApprovalPolicy(
        String mode,
        Set<String> approvedToolIds,
        boolean legacyAllowHighRisk
) {
    public static final String MODE_ASK = "ask";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_FULL = "full";
    public static final String MODE_CUSTOM = "custom";

    public static ApprovalPolicy fromTaskMetadata(Map<String, String> metadata) {
        Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
        String mode = firstPresent(safeMetadata.get("toolPermissionMode"), safeMetadata.get("approvalMode"));
        Set<String> approved = parseToolIds(safeMetadata.get("approvedToolIds"));
        boolean legacyAllowHighRisk = isTruthy(safeMetadata.get("allowHighRiskTools")) && normalizeMode(mode).isBlank();
        return new ApprovalPolicy(normalizeMode(mode), approved, legacyAllowHighRisk);
    }

    public boolean isFullAccess() {
        return MODE_FULL.equals(mode) || "full-access".equals(mode);
    }

    public boolean isAutoApprove() {
        return MODE_AUTO.equals(mode);
    }

    public boolean isExplicitlyApproved(String toolId) {
        return toolId != null && approvedToolIds.contains(toolId.trim().toLowerCase(Locale.ROOT));
    }

    public boolean allowsHighRiskAutomatically() {
        return isAutoApprove() || legacyAllowHighRisk;
    }

    private static String normalizeMode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> parseToolIds(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String normalized = value
                .replace('[', ' ')
                .replace(']', ' ')
                .replace('"', ' ')
                .replace('\'', ' ');
        Set<String> ids = new LinkedHashSet<>();
        Arrays.stream(normalized.split("[,;\\s]+"))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .filter(item -> !item.isBlank())
                .forEach(ids::add);
        return Set.copyOf(ids);
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized)
                || "1".equals(normalized)
                || "yes".equals(normalized)
                || "y".equals(normalized)
                || "on".equals(normalized);
    }

    private static String firstPresent(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
