package com.github.clawagent.server.controller;

import com.github.clawagent.core.AgentStep;
import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;
import com.github.clawagent.core.AutomationScheduleType;
import com.github.clawagent.core.AutomationStatus;
import com.github.clawagent.core.StepType;
import com.github.clawagent.core.TokenUsageSummary;
import com.github.clawagent.server.dto.AutomationRunView;
import com.github.clawagent.server.dto.AutomationUpsertRequest;
import com.github.clawagent.spi.AutomationStore;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.spring.automation.AutomationSchedulerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 自动化任务管理接口。
 */
@RestController
@RequestMapping("/api/v1")
public class AutomationController {
    private static final Logger log = LoggerFactory.getLogger(AutomationController.class);

    private final com.github.clawagent.runtime.AgentRuntime runtime;
    private final AutomationStore automationStore;
    private final AutomationSchedulerService automationSchedulerService;
    private final ClawAgentProperties properties;

    public AutomationController(com.github.clawagent.runtime.AgentRuntime runtime,
                                @Qualifier("automationStore") AutomationStore automationStore,
                                AutomationSchedulerService automationSchedulerService,
                                ClawAgentProperties properties) {
        this.runtime = runtime;
        this.automationStore = automationStore;
        this.automationSchedulerService = automationSchedulerService;
        this.properties = properties;
    }

    /**
     * 分页列出自动化任务。
     */
    @GetMapping("/automations")
    public List<AutomationDefinition> automations(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return automationStore.listAutomations(limit);
    }

    /**
     * 获取单个自动化任务详情。
     */
    @GetMapping("/automations/{automationId}")
    public AutomationDefinition automation(@PathVariable("automationId") String automationId) {
        return automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
    }

    /**
     * 创建自动化任务并补齐首次调度时间。
     */
    @PostMapping("/automations")
    public AutomationDefinition createAutomation(@RequestBody AutomationUpsertRequest request) {
        AutomationDefinition automation = buildAutomation(UUID.randomUUID().toString(), request, null);
        log.info("automation create requested id={} name={} scheduleType={}",
                automation.id(), automation.name(), automation.scheduleType());
        automationStore.saveAutomation(automation);
        return automation;
    }

    /**
     * 更新自动化任务，保留原创建时间和最近运行时间。
     */
    @PutMapping("/automations/{automationId}")
    public AutomationDefinition updateAutomation(
            @PathVariable("automationId") String automationId,
            @RequestBody AutomationUpsertRequest request) {
        AutomationDefinition existing = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        AutomationDefinition automation = buildAutomation(automationId, request, existing);
        log.info("automation update requested id={} name={} scheduleType={} status={}",
                automation.id(), automation.name(), automation.scheduleType(), automation.status());
        automationStore.saveAutomation(automation);
        return automation;
    }

    /**
     * 删除自动化任务定义；运行历史由底层 store 决定是否保留。
     */
    @DeleteMapping("/automations/{automationId}")
    public Map<String, Object> deleteAutomation(@PathVariable("automationId") String automationId) {
        log.warn("automation delete requested id={}", automationId);
        automationStore.deleteAutomation(automationId);
        return Map.of("deleted", true, "automationId", automationId);
    }

    /**
     * 启用自动化任务并刷新下一次运行时间。
     */
    @PostMapping("/automations/{automationId}/enable")
    public AutomationDefinition enableAutomation(@PathVariable("automationId") String automationId) {
        AutomationDefinition automation = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        AutomationDefinition enabled = copyAutomationWithStatus(automation, AutomationStatus.ENABLED, Instant.now());
        log.info("automation enable requested id={}", automationId);
        AutomationDefinition refreshed = automationSchedulerService.refreshNextRun(enabled, Instant.now());
        automationStore.saveAutomation(refreshed);
        return refreshed;
    }

    /**
     * 暂停自动化任务，并清空下一次运行时间。
     */
    @PostMapping("/automations/{automationId}/pause")
    public AutomationDefinition pauseAutomation(@PathVariable("automationId") String automationId) {
        AutomationDefinition automation = automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        log.info("automation pause requested id={}", automationId);
        AutomationDefinition paused = copyAutomationWithNextRun(automation, AutomationStatus.PAUSED, null, Instant.now());
        automationStore.saveAutomation(paused);
        return paused;
    }

    /**
     * 立即触发一次自动化执行。
     */
    @PostMapping("/automations/{automationId}/run")
    public AutomationDefinition runAutomationNow(@PathVariable("automationId") String automationId) {
        log.info("automation run now requested id={}", automationId);
        automationSchedulerService.runNow(automationId);
        return automationStore.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
    }

    /**
     * 查询自动化运行历史，并附带关联任务的 token 和工具调用摘要。
     */
    @GetMapping("/automations/{automationId}/runs")
    public List<AutomationRunView> automationRuns(
            @PathVariable("automationId") String automationId,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return automationStore.listAutomationRuns(automationId, limit).stream()
                .map(this::toAutomationRunView)
                .toList();
    }

    private AutomationRunView toAutomationRunView(AutomationRun run) {
        Long elapsedMs = run.startedAt() == null || run.finishedAt() == null
                ? null
                : Duration.between(run.startedAt(), run.finishedAt()).toMillis();
        TokenUsageSummary tokenUsage = null;
        List<AgentStep> steps = List.of();
        if (run.taskId() != null && !run.taskId().isBlank()) {
            try {
                tokenUsage = runtime.getTaskTokenUsage(run.taskId());
                steps = runtime.getSteps(run.taskId());
            } catch (RuntimeException ex) {
                // 运行记录可能引用已清理的历史 task；摘要缺失时仍应允许用户打开自动化历史。
                log.debug("automation run summary skipped automationId={} runId={} taskId={} error={}",
                        run.automationId(), run.id(), run.taskId(), ex.getMessage());
            }
        }
        int toolCalls = (int) steps.stream().filter(step -> step.type() == StepType.TOOL_CALL).count();
        int failedToolCalls = (int) steps.stream()
                .filter(step -> step.type() == StepType.TOOL_CALL && step.status() == com.github.clawagent.core.StepStatus.FAILED)
                .count();
        return new AutomationRunView(
                run.id(),
                run.automationId(),
                run.taskId(),
                run.status() == null ? null : run.status().name(),
                run.startedAt(),
                run.finishedAt(),
                run.error(),
                elapsedMs,
                tokenUsage == null ? null : tokenUsage.callCount(),
                tokenUsage == null ? null : tokenUsage.promptTokens(),
                tokenUsage == null ? null : tokenUsage.completionTokens(),
                tokenUsage == null ? null : tokenUsage.totalTokens(),
                toolCalls,
                failedToolCalls);
    }

    private AutomationDefinition buildAutomation(String id, AutomationUpsertRequest request, AutomationDefinition existing) {
        Instant now = Instant.now();
        AutomationScheduleType scheduleType = parseScheduleType(firstNonBlank(
                request.scheduleType(),
                existing == null ? AutomationScheduleType.INTERVAL.name() : existing.scheduleType().name()));
        AutomationStatus status = parseAutomationStatus(firstNonBlank(
                request.status(),
                existing == null ? AutomationStatus.ENABLED.name() : existing.status().name()));
        String prompt = firstNonBlank(request.prompt(), existing == null ? null : existing.prompt());
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("自动化任务缺少 prompt");
        }
        String sessionId = firstNonBlank(request.sessionId(), existing == null ? null : existing.sessionId());
        if (sessionId == null || sessionId.isBlank()) {
            // 新建自动化时只预分配会话 ID，不落盘会话内容；首次执行时 Runtime 会用该 ID 创建持久化会话。
            sessionId = runtime.createSessionId();
        }

        AutomationDefinition automation = new AutomationDefinition(
                id,
                firstNonBlank(request.name(), existing == null ? "自动化任务" : existing.name()),
                prompt,
                sessionId,
                firstNonBlank(request.channelId(), existing == null ? properties.getAutomation().getDefaultChannelId() : existing.channelId()),
                firstNonBlank(request.userId(), existing == null ? properties.getAutomation().getDefaultUserId() : existing.userId()),
                scheduleType,
                firstNonBlank(request.cronExpression(), existing == null ? null : existing.cronExpression()),
                request.intervalSeconds() == null ? existing == null ? null : existing.intervalSeconds() : request.intervalSeconds(),
                firstNonBlank(request.timezone(), existing == null ? "Asia/Shanghai" : existing.timezone()),
                request.nextRunAt() == null ? existing == null ? null : existing.nextRunAt() : request.nextRunAt(),
                existing == null ? null : existing.lastRunAt(),
                status,
                request.metadata() == null ? existing == null ? Map.of() : existing.metadata() : request.metadata(),
                existing == null ? now : existing.createdAt(),
                now);
        // 保存前补齐 nextRunAt，调度器只需要扫描到期任务，不再猜测用户意图。
        return status == AutomationStatus.ENABLED && automation.nextRunAt() == null
                ? automationSchedulerService.refreshNextRun(automation, now)
                : automation;
    }

    private AutomationDefinition copyAutomationWithStatus(AutomationDefinition automation, AutomationStatus status, Instant updatedAt) {
        return copyAutomationWithNextRun(automation, status, automation.nextRunAt(), updatedAt);
    }

    private AutomationDefinition copyAutomationWithNextRun(
            AutomationDefinition automation,
            AutomationStatus status,
            Instant nextRunAt,
            Instant updatedAt) {
        return new AutomationDefinition(
                automation.id(),
                automation.name(),
                automation.prompt(),
                automation.sessionId(),
                automation.channelId(),
                automation.userId(),
                automation.scheduleType(),
                automation.cronExpression(),
                automation.intervalSeconds(),
                automation.timezone(),
                nextRunAt,
                automation.lastRunAt(),
                status,
                automation.metadata(),
                automation.createdAt(),
                updatedAt);
    }

    private AutomationScheduleType parseScheduleType(String value) {
        try {
            return AutomationScheduleType.valueOf(firstNonBlank(value, AutomationScheduleType.INTERVAL.name()).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的自动化调度类型：" + value, ex);
        }
    }

    private AutomationStatus parseAutomationStatus(String value) {
        try {
            return AutomationStatus.valueOf(firstNonBlank(value, AutomationStatus.ENABLED.name()).toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不支持的自动化状态：" + value, ex);
        }
    }

    private String firstNonBlank(String candidate, String fallback) {
        return candidate == null || candidate.isBlank() ? fallback : candidate.trim();
    }
}
