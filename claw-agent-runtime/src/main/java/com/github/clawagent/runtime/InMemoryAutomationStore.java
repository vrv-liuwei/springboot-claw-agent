package com.github.clawagent.runtime;

import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;
import com.github.clawagent.core.AutomationStatus;
import com.github.clawagent.spi.AutomationStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版 AutomationStore，用于非 SQLite 场景的兜底。
 */
public class InMemoryAutomationStore implements AutomationStore {
    private final Map<String, AutomationDefinition> automations = new LinkedHashMap<>();
    private final Map<String, AutomationRun> runs = new LinkedHashMap<>();

    @Override
    public synchronized void saveAutomation(AutomationDefinition automation) {
        automations.put(automation.id(), automation);
    }

    @Override
    public synchronized Optional<AutomationDefinition> findAutomation(String id) {
        return Optional.ofNullable(automations.get(id));
    }

    @Override
    public synchronized List<AutomationDefinition> listAutomations(int limit) {
        return automations.values().stream()
                .sorted(Comparator.comparing(AutomationDefinition::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized List<AutomationDefinition> listDueAutomations(Instant now, int limit) {
        return automations.values().stream()
                // 调度器只拉取已启用且到期的定义，避免后台反复检查暂停任务。
                .filter(item -> item.status() == AutomationStatus.ENABLED)
                .filter(item -> item.nextRunAt() != null && !item.nextRunAt().isAfter(now))
                .sorted(Comparator.comparing(AutomationDefinition::nextRunAt))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized void deleteAutomation(String id) {
        automations.remove(id);
        // 删除定义时同步清理运行记录，避免页面留下孤立数据。
        new ArrayList<>(runs.values()).stream()
                .filter(run -> id.equals(run.automationId()))
                .map(AutomationRun::id)
                .forEach(runs::remove);
    }

    @Override
    public synchronized void saveAutomationRun(AutomationRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public synchronized List<AutomationRun> listAutomationRuns(String automationId, int limit) {
        return runs.values().stream()
                .filter(run -> automationId == null || automationId.isBlank() || automationId.equals(run.automationId()))
                .sorted(Comparator.comparing(AutomationRun::startedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }
}
