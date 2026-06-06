package com.github.clawagent.spi;

import com.github.clawagent.core.AutomationDefinition;
import com.github.clawagent.core.AutomationRun;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 自动化任务定义与运行记录存储。
 */
public interface AutomationStore {
    void saveAutomation(AutomationDefinition automation);

    Optional<AutomationDefinition> findAutomation(String id);

    List<AutomationDefinition> listAutomations(int limit);

    List<AutomationDefinition> listDueAutomations(Instant now, int limit);

    void deleteAutomation(String id);

    void saveAutomationRun(AutomationRun run);

    List<AutomationRun> listAutomationRuns(String automationId, int limit);
}
