package com.github.clawagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 审批策略解析结果。
 * 当前先统一 metadata 里的策略来源、作用域和冲突解释，后续 Channel/User/Agent 合并规则继续扩展这里。
 */
public record ApprovalPolicyResolution(
        ApprovalPolicy policy,
        String source,
        String scope,
        String resolutionOrder,
        String overrideReason,
        List<String> conflictNotes
) {
    private static final String DEFAULT_RESOLUTION_ORDER =
            "local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement";

    public ApprovalPolicyResolution {
        source = normalizeText(source, "task.metadata");
        scope = normalizeText(scope, inferScope(source));
        resolutionOrder = normalizeText(resolutionOrder, DEFAULT_RESOLUTION_ORDER);
        overrideReason = overrideReason == null ? "" : overrideReason.trim();
        conflictNotes = conflictNotes == null ? List.of() : List.copyOf(conflictNotes);
    }

    public static ApprovalPolicyResolution fromTaskMetadata(Map<String, String> metadata) {
        Map<String, String> safeMetadata = metadata == null ? Map.of() : metadata;
        ApprovalPolicy policy = ApprovalPolicy.fromTaskMetadata(safeMetadata);
        String source = firstNonBlank(
                safeMetadata.get("policy.approval.source"),
                inferSource(safeMetadata));
        String scope = firstNonBlank(
                safeMetadata.get("policy.approval.scope"),
                inferScope(source));
        List<String> conflicts = detectConflicts(safeMetadata);
        return new ApprovalPolicyResolution(
                policy,
                source,
                scope,
                safeMetadata.get("policy.resolutionOrder"),
                safeMetadata.get("policy.overrideReason"),
                conflicts);
    }

    private static List<String> detectConflicts(Map<String, String> metadata) {
        List<String> conflicts = new ArrayList<>();
        String toolPermissionMode = normalizeMode(metadata.get("toolPermissionMode"));
        String approvalMode = normalizeMode(metadata.get("approvalMode"));
        if (!toolPermissionMode.isBlank() && !approvalMode.isBlank() && !toolPermissionMode.equals(approvalMode)) {
            // 运行时一直优先使用 toolPermissionMode，这里把覆盖关系显式暴露给页面和审计。
            conflicts.add("toolPermissionMode=" + toolPermissionMode
                    + " 覆盖 approvalMode=" + approvalMode);
        }
        if ("read-only".equalsIgnoreCase(metadata.get("agent.isolation"))
                && !normalizeText(metadata.get("approvedToolIds"), "").isBlank()) {
            conflicts.add("只读子 Agent 不应携带 approvedToolIds，非 low 风险仍会被 Guard 拦截");
        }
        return conflicts;
    }

    private static String inferSource(Map<String, String> metadata) {
        String channelId = normalizeText(metadata.get("channel.id"), "");
        if (!channelId.isBlank()) {
            return "channel:" + channelId;
        }
        String isolation = normalizeText(metadata.get("agent.isolation"), "");
        if (!isolation.isBlank()) {
            return "agent-isolation:" + isolation;
        }
        return "task.metadata";
    }

    private static String inferScope(String source) {
        String normalized = normalizeText(source, "").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("channel:")) {
            return "channel";
        }
        if (normalized.startsWith("user:")) {
            return "user";
        }
        if (normalized.startsWith("api-token:")) {
            return "api-token";
        }
        if (normalized.startsWith("device:")) {
            return "device";
        }
        if (normalized.startsWith("agent-isolation:")
                || normalized.startsWith("agent-role:")
                || normalized.startsWith("agent:")) {
            return "agent";
        }
        if (normalized.startsWith("local.")) {
            return "local";
        }
        return "task";
    }

    private static String normalizeMode(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first.trim();
    }
}
