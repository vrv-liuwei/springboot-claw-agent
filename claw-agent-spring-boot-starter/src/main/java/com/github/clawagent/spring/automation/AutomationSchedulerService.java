package com.github.clawagent.spring.automation;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;
import com.github.clawagent.core.AutomationRunStatus;
import com.github.clawagent.core.AutomationScheduleType;
import com.github.clawagent.core.AutomationStatus;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.spi.AutomationStore;
import com.github.clawagent.spring.ClawAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 智能体自动化调度服务。
 * 负责扫描到期自动化任务，异步提交给 AgentRuntime 执行，并记录运行结果。
 */
public class AutomationSchedulerService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(AutomationSchedulerService.class);
    private static final String RETRY_CURRENT_ATTEMPT = "retry.currentAttempt";
    private static final String RETRY_MAX_ATTEMPTS = "retry.maxAttempts";
    private static final String RETRY_BACKOFF_SECONDS = "retry.backoffSeconds";
    private static final String RETRY_PAUSE_AFTER_EXHAUSTED = "retry.pauseAfterExhausted";
    private static final String RETRY_LAST_ERROR = "retry.lastError";
    private static final String RETRY_LAST_RUN_ID = "retry.lastRunId";
    private static final String RETRY_EXHAUSTED_AT = "retry.exhaustedAt";

    private final AutomationStore store;
    private final AgentRuntime runtime;
    private final ClawAgentProperties properties;
    private final Set<String> activeAutomationIds = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private ScheduledExecutorService scheduler;
    private ExecutorService workers;

    public AutomationSchedulerService(AutomationStore store, AgentRuntime runtime, ClawAgentProperties properties) {
        this.store = store;
        this.runtime = runtime;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.getAutomation().isEnabled()) {
            log.info("automation scheduler skipped because clawagent.automation.enabled=false");
            return;
        }
        running = true;
        ensureExecutors();
        int intervalSeconds = Math.max(1, properties.getAutomation().getPollIntervalSeconds());
        scheduler.scheduleWithFixedDelay(this::tickSafely, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("automation scheduler started pollIntervalSeconds={}", intervalSeconds);
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        activeAutomationIds.clear();
        log.info("automation scheduler stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 手动触发一次自动化任务；用于管理台“立即执行”。
     */
    public void runNow(String automationId) {
        AutomationDefinition automation = store.findAutomation(automationId)
                .orElseThrow(() -> new IllegalArgumentException("自动化任务不存在：" + automationId));
        ensureExecutors();
        workers.submit(() -> runAutomation(automation, true));
    }

    /**
     * 计算并刷新下一次执行时间；创建或启用自动化时复用该逻辑。
     */
    public AutomationDefinition refreshNextRun(AutomationDefinition automation, Instant now) {
        Instant nextRunAt = computeNextRun(automation, now);
        return copyAutomation(automation, automation.status(), automation.lastRunAt(), nextRunAt, now);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException ex) {
            log.warn("automation scheduler tick failed error={}", ex.getMessage(), ex);
        }
    }

    private void tick() {
        if (!running) {
            return;
        }
        int batchSize = Math.max(1, properties.getAutomation().getDueBatchSize());
        // 每轮只取小批量到期任务，避免自动化任务过多时压垮 Runtime。
        for (AutomationDefinition automation : store.listDueAutomations(Instant.now(), batchSize)) {
            workers.submit(() -> runAutomation(automation, false));
        }
    }

    private void runAutomation(AutomationDefinition automation, boolean manual) {
        if (!activeAutomationIds.add(automation.id())) {
            log.info("automation skipped because previous run is active automationId={}", automation.id());
            return;
        }

        String runId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        store.saveAutomationRun(new AutomationRun(runId, automation.id(), null, AutomationRunStatus.RUNNING, startedAt, null, null));
        log.info("automation run started automationId={} runId={} manual={}", automation.id(), runId, manual);

        try {
            AgentResult result = runtime.submit(toAgentRequest(automation, manual));
            Instant finishedAt = Instant.now();
            AutomationDefinition executedAutomation = bindRuntimeSessionIfMissing(automation, result.sessionId(), finishedAt);
            if (result.status() != TaskStatus.COMPLETED) {
                String error = "自动化任务执行未完成，状态：" + result.status();
                store.saveAutomationRun(new AutomationRun(
                        runId,
                        automation.id(),
                        result.taskId(),
                        AutomationRunStatus.FAILED,
                        startedAt,
                        finishedAt,
                        error));
                updateAutomationAfterFailure(executedAutomation, finishedAt, runId, error);
                log.warn("automation run failed automationId={} runId={} taskId={} status={}",
                        automation.id(), runId, result.taskId(), result.status());
                return;
            }
            store.saveAutomationRun(new AutomationRun(
                    runId,
                    automation.id(),
                    result.taskId(),
                    AutomationRunStatus.COMPLETED,
                    startedAt,
                    finishedAt,
                    null));
            updateAutomationAfterSuccess(executedAutomation, finishedAt);
            log.info("automation run completed automationId={} runId={} taskId={}", automation.id(), runId, result.taskId());
        } catch (RuntimeException ex) {
            Instant finishedAt = Instant.now();
            store.saveAutomationRun(new AutomationRun(
                    runId,
                    automation.id(),
                    null,
                    AutomationRunStatus.FAILED,
                    startedAt,
                    finishedAt,
                    ex.getMessage()));
            updateAutomationAfterFailure(automation, finishedAt, runId, ex.getMessage());
            log.warn("automation run failed automationId={} runId={} error={}", automation.id(), runId, ex.getMessage(), ex);
        } finally {
            activeAutomationIds.remove(automation.id());
        }
    }

    private AgentRequest toAgentRequest(AutomationDefinition automation, boolean manual) {
        Map<String, String> metadata = new java.util.LinkedHashMap<>(automation.metadata());
        metadata.put("automationId", automation.id());
        metadata.put("automationName", automation.name());
        metadata.put("automationManualRun", String.valueOf(manual));
        // 自动化任务复用普通 AgentRuntime 链路，便于日志、消息、Token 和 Todo 继续关联到同一个 session。
        return new AgentRequest(
                automation.prompt(),
                automation.sessionId(),
                firstNonBlank(automation.channelId(), properties.getAutomation().getDefaultChannelId()),
                firstNonBlank(automation.userId(), properties.getAutomation().getDefaultUserId()),
                metadata);
    }

    private void updateAutomationAfterSuccess(AutomationDefinition automation, Instant finishedAt) {
        AutomationStatus nextStatus = automation.scheduleType() == AutomationScheduleType.ONCE
                ? AutomationStatus.PAUSED
                : automation.status();
        Instant nextRunAt = nextStatus == AutomationStatus.ENABLED ? computeNextRun(automation, finishedAt) : null;
        store.saveAutomation(copyAutomation(automation, nextStatus, finishedAt, nextRunAt, finishedAt, retryClearedMetadata(automation)));
    }

    /**
     * 根据全局配置或单任务 metadata 处理失败重试。
     * retry.currentAttempt 只记录连续失败次数，成功后会清零。
     */
    private void updateAutomationAfterFailure(AutomationDefinition automation, Instant finishedAt, String runId, String error) {
        int maxAttempts = metadataInt(automation, RETRY_MAX_ATTEMPTS, properties.getAutomation().getMaxRetryAttempts());
        int currentAttempt = metadataInt(automation, RETRY_CURRENT_ATTEMPT, 0) + 1;
        int backoffSeconds = Math.max(1, metadataInt(automation, RETRY_BACKOFF_SECONDS, properties.getAutomation().getRetryBackoffSeconds()));
        boolean pauseAfterExhausted = metadataBoolean(
                automation,
                RETRY_PAUSE_AFTER_EXHAUSTED,
                properties.getAutomation().isPauseAfterRetriesExhausted());
        Map<String, String> metadata = new java.util.LinkedHashMap<>(automation.metadata());
        metadata.put(RETRY_CURRENT_ATTEMPT, String.valueOf(currentAttempt));
        metadata.put(RETRY_LAST_RUN_ID, runId);
        metadata.put(RETRY_LAST_ERROR, error == null ? "" : error);

        if (maxAttempts > 0 && currentAttempt <= maxAttempts && automation.status() == AutomationStatus.ENABLED) {
            long delaySeconds = Math.min(86_400L, (long) backoffSeconds * currentAttempt);
            Instant retryAt = finishedAt.plusSeconds(delaySeconds);
            // 失败重试通过 nextRunAt 重新入调度队列，不额外创建隐藏任务。
            store.saveAutomation(copyAutomation(automation, AutomationStatus.ENABLED, finishedAt, retryAt, finishedAt, metadata));
            log.info("automation retry scheduled automationId={} attempt={}/{} retryAt={}",
                    automation.id(), currentAttempt, maxAttempts, retryAt);
            return;
        }

        if (pauseAfterExhausted && automation.status() == AutomationStatus.ENABLED) {
            metadata.put(RETRY_EXHAUSTED_AT, finishedAt.toString());
            store.saveAutomation(copyAutomation(automation, AutomationStatus.PAUSED, finishedAt, null, finishedAt, metadata));
            log.warn("automation paused after retries exhausted automationId={} attempts={}", automation.id(), currentAttempt);
            return;
        }

        AutomationStatus nextStatus = automation.scheduleType() == AutomationScheduleType.ONCE
                ? AutomationStatus.PAUSED
                : automation.status();
        Instant nextRunAt = nextStatus == AutomationStatus.ENABLED ? computeNextRun(automation, finishedAt) : null;
        store.saveAutomation(copyAutomation(automation, nextStatus, finishedAt, nextRunAt, finishedAt, metadata));
    }

    private AutomationDefinition bindRuntimeSessionIfMissing(AutomationDefinition automation, String runtimeSessionId, Instant updatedAt) {
        if (automation.sessionId() != null && !automation.sessionId().isBlank()) {
            return automation;
        }
        if (runtimeSessionId == null || runtimeSessionId.isBlank()) {
            return automation;
        }
        // 兼容旧数据：历史自动化定义没有 sessionId 时，首次成功运行后绑定 Runtime 创建的真实会话。
        return copyAutomation(automation, automation.status(), automation.lastRunAt(), automation.nextRunAt(), updatedAt, runtimeSessionId);
    }

    private Instant computeNextRun(AutomationDefinition automation, Instant baseTime) {
        if (automation.status() != AutomationStatus.ENABLED) {
            return null;
        }
        return switch (automation.scheduleType()) {
            case ONCE -> computeOnceNextRun(automation, baseTime);
            case INTERVAL -> baseTime.plusSeconds(Math.max(1L, automation.intervalSeconds() == null ? 60L : automation.intervalSeconds()));
            case CRON -> computeCronNextRun(automation, baseTime);
        };
    }

    private Instant computeOnceNextRun(AutomationDefinition automation, Instant baseTime) {
        if (automation.nextRunAt() != null && automation.nextRunAt().isAfter(baseTime)) {
            return automation.nextRunAt();
        }
        return baseTime;
    }

    private Instant computeCronNextRun(AutomationDefinition automation, Instant baseTime) {
        if (automation.cronExpression() == null || automation.cronExpression().isBlank()) {
            throw new IllegalArgumentException("CRON 自动化任务缺少 cronExpression");
        }
        ZoneId zoneId = ZoneId.of(firstNonBlank(automation.timezone(), "Asia/Shanghai"));
        ZonedDateTime next = CronExpression.parse(automation.cronExpression()).next(ZonedDateTime.ofInstant(baseTime, zoneId));
        if (next == null) {
            throw new IllegalArgumentException("无法计算下一次 CRON 执行时间：" + automation.cronExpression());
        }
        return next.toInstant();
    }

    private AutomationDefinition copyAutomation(
            AutomationDefinition source,
            AutomationStatus status,
            Instant lastRunAt,
            Instant nextRunAt,
            Instant updatedAt) {
        return copyAutomation(source, status, lastRunAt, nextRunAt, updatedAt, source.sessionId(), source.metadata());
    }

    private AutomationDefinition copyAutomation(
            AutomationDefinition source,
            AutomationStatus status,
            Instant lastRunAt,
            Instant nextRunAt,
            Instant updatedAt,
            String sessionId) {
        return copyAutomation(source, status, lastRunAt, nextRunAt, updatedAt, sessionId, source.metadata());
    }

    private AutomationDefinition copyAutomation(
            AutomationDefinition source,
            AutomationStatus status,
            Instant lastRunAt,
            Instant nextRunAt,
            Instant updatedAt,
            Map<String, String> metadata) {
        return copyAutomation(source, status, lastRunAt, nextRunAt, updatedAt, source.sessionId(), metadata);
    }

    private AutomationDefinition copyAutomation(
            AutomationDefinition source,
            AutomationStatus status,
            Instant lastRunAt,
            Instant nextRunAt,
            Instant updatedAt,
            String sessionId,
            Map<String, String> metadata) {
        return new AutomationDefinition(
                source.id(),
                source.name(),
                source.prompt(),
                sessionId,
                source.channelId(),
                source.userId(),
                source.scheduleType(),
                source.cronExpression(),
                source.intervalSeconds(),
                source.timezone(),
                nextRunAt,
                lastRunAt,
                status,
                metadata == null ? Map.of() : metadata,
                source.createdAt(),
                updatedAt);
    }

    private Map<String, String> retryClearedMetadata(AutomationDefinition automation) {
        Map<String, String> metadata = new java.util.LinkedHashMap<>(automation.metadata());
        metadata.remove(RETRY_CURRENT_ATTEMPT);
        metadata.remove(RETRY_LAST_ERROR);
        metadata.remove(RETRY_LAST_RUN_ID);
        metadata.remove(RETRY_EXHAUSTED_AT);
        return metadata;
    }

    private int metadataInt(AutomationDefinition automation, String key, int fallback) {
        try {
            String value = automation.metadata().get(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean metadataBoolean(AutomationDefinition automation, String key, boolean fallback) {
        String value = automation.metadata().get(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private synchronized void ensureExecutors() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "clawagent-automation-scheduler"));
        }
        if (workers == null || workers.isShutdown()) {
            workers = Executors.newFixedThreadPool(2, runnable -> new Thread(runnable, "clawagent-automation-worker"));
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first.trim();
    }
}
