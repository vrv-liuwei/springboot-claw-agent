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
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        Instant fromInstant = parseAuditInstant(from, false);
        Instant toInstant = parseAuditInstant(to, true);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return runtime.queryEvents(fromInstant, toInstant, level, type, sessionId, taskId, safeLimit);
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
