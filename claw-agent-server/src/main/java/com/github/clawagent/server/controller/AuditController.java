package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.runtime.AgentRuntime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 审计事件查询接口。
 * 这里只做查询条件适配，真实事件仍由 Runtime/EventStore 负责维护。
 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {
    private final AgentRuntime runtime;

    public AuditController(AgentRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/audit/events")
    public List<AgentEvent> auditEvents(
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestParam(name = "taskId", required = false) String taskId,
            @RequestParam(name = "userId", required = false) String userId,
            @RequestParam(name = "channelId", required = false) String channelId,
            @RequestParam(name = "toolId", required = false) String toolId,
            @RequestParam(name = "riskLevel", required = false) String riskLevel,
            @RequestParam(name = "detailKey", required = false) String detailKey,
            @RequestParam(name = "detailValue", required = false) String detailValue,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        Instant fromInstant = parseAuditInstant(from, false);
        Instant toInstant = parseAuditInstant(to, true);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        boolean detailFiltering = hasText(userId)
                || hasText(channelId)
                || hasText(toolId)
                || hasText(riskLevel)
                || hasText(detailKey)
                || hasText(detailValue)
                || hasText(q);
        int fetchLimit = detailFiltering ? 1000 : safeLimit;
        List<AgentEvent> events = runtime.queryEvents(fromInstant, toInstant, level, type, sessionId, taskId, fetchLimit);
        if (!detailFiltering) {
            return events;
        }
        // 详情字段目前以轻量 key/value 存在 AgentEvent.details 中；这里做只读过滤，不改存储结构。
        return events.stream()
                .filter(event -> matchesDetail(event, userId, "userId", "user.id", "localUserId", "channel.externalUserId"))
                .filter(event -> matchesDetail(event, channelId, "channelId", "channel.id"))
                .filter(event -> matchesDetail(event, toolId, "toolId", "tool.id"))
                .filter(event -> matchesDetail(event, riskLevel, "riskLevel", "tool.riskLevel"))
                .filter(event -> matchesDetailKeyValue(event, detailKey, detailValue))
                .filter(event -> matchesKeyword(event, q))
                .limit(safeLimit)
                .toList();
    }

    private boolean matchesDetail(AgentEvent event, String expected, String... keys) {
        if (!hasText(expected)) {
            return true;
        }
        String normalizedExpected = expected.trim();
        Map<String, String> details = event.details();
        for (String key : keys) {
            String actual = details.get(key);
            if (actual != null && actual.trim().equalsIgnoreCase(normalizedExpected)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDetailKeyValue(AgentEvent event, String detailKey, String detailValue) {
        if (!hasText(detailKey) && !hasText(detailValue)) {
            return true;
        }
        Map<String, String> details = event.details();
        if (hasText(detailKey)) {
            String actual = details.get(detailKey.trim());
            return !hasText(detailValue) || containsIgnoreCase(actual, detailValue);
        }
        return details.values().stream().anyMatch(value -> containsIgnoreCase(value, detailValue));
    }

    private boolean matchesKeyword(AgentEvent event, String q) {
        if (!hasText(q)) {
            return true;
        }
        return containsIgnoreCase(event.message(), q)
                || containsIgnoreCase(event.type(), q)
                || event.details().entrySet().stream()
                .anyMatch(entry -> containsIgnoreCase(entry.getKey(), q) || containsIgnoreCase(entry.getValue(), q));
    }

    private boolean containsIgnoreCase(String value, String expected) {
        if (!hasText(expected)) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Instant parseAuditInstant(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}")) {
            LocalDate date = LocalDate.parse(trimmed);
            // 管理台日期筛选按服务本地时区解释，避免 UTC 边界导致当天审计事件漏查。
            return (endOfDay ? date.plusDays(1).atStartOfDay() : date.atStartOfDay())
                    .atZone(ZoneId.systemDefault())
                    .toInstant();
        }
        return Instant.parse(trimmed);
    }
}
