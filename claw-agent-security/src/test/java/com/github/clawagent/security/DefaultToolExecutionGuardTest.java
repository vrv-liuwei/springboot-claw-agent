package com.github.clawagent.security;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultToolExecutionGuardTest {
    private final DefaultToolExecutionGuard guard = new DefaultToolExecutionGuard();

    @Test
    void allowsLowRiskCallsWithoutApproval() {
        AgentTool tool = new StaticRiskTool("low");

        assertDoesNotThrow(() -> guard.check(task(Map.of()), tool, call()));
    }

    @Test
    void blocksHighRiskCallsWithoutApproval() {
        AgentTool tool = new StaticRiskTool("high");

        assertThrows(IllegalStateException.class, () -> guard.check(task(Map.of()), tool, call()));
    }

    @Test
    void allowsHighRiskCallsWhenToolIsExplicitlyApproved() {
        AgentTool tool = new StaticRiskTool("high");

        assertDoesNotThrow(() -> guard.check(task(Map.of("approvedToolIds", "builtin.execute.command")), tool, call()));
    }

    @Test
    void allowsHighRiskCallsWhenToolIsApprovedByJsonArrayMetadata() {
        AgentTool tool = new StaticRiskTool("high");

        assertDoesNotThrow(() -> guard.check(task(Map.of("approvedToolIds", "[\"builtin.execute.command\"]")), tool, call()));
    }

    @Test
    void allowsHighRiskCallsInAutoPermissionMode() {
        AgentTool tool = new StaticRiskTool("high");

        assertDoesNotThrow(() -> guard.check(task(Map.of("toolPermissionMode", "auto")), tool, call()));
    }

    @Test
    void keepsLegacyAllowHighRiskCompatibilityWhenNoModeIsSet() {
        AgentTool tool = new StaticRiskTool("high");

        assertDoesNotThrow(() -> guard.check(task(Map.of("allowHighRiskTools", "true")), tool, call()));
    }

    @Test
    void asksUserForMediumRiskCallsInAutoPermissionMode() {
        AgentTool tool = new StaticRiskTool("medium");

        assertThrows(IllegalStateException.class, () -> guard.check(task(Map.of("toolPermissionMode", "auto")), tool, call()));
    }

    @Test
    void allowsMediumRiskCallsInFullPermissionMode() {
        AgentTool tool = new StaticRiskTool("medium");

        assertDoesNotThrow(() -> guard.check(task(Map.of("toolPermissionMode", "full")), tool, call()));
    }

    @Test
    void allowsMediumRiskCallsInFullAccessAliasMode() {
        AgentTool tool = new StaticRiskTool("medium");

        assertDoesNotThrow(() -> guard.check(task(Map.of("toolPermissionMode", "full-access")), tool, call()));
    }

    @Test
    void promptInjectionSuspicionForcesConfirmationInAutoMode() {
        AgentTool tool = new StaticRiskTool("high");

        assertThrows(IllegalStateException.class, () -> guard.check(task(Map.of(
                "toolPermissionMode", "auto",
                "security.promptInjectionSuspected", "true",
                "security.promptInjectionReason", "ignore-instructions: ignore previous instructions"
        )), tool, call()));
    }

    @Test
    void promptInjectionSuspicionForcesConfirmationInFullMode() {
        AgentTool tool = new StaticRiskTool("high");

        assertThrows(IllegalStateException.class, () -> guard.check(task(Map.of(
                "toolPermissionMode", "full",
                "security.promptInjectionSuspected", "true"
        )), tool, call()));
    }

    @Test
    void promptInjectionSuspicionStillAllowsLowRiskQueries() {
        AgentTool tool = new StaticRiskTool("low");

        assertDoesNotThrow(() -> guard.check(task(Map.of(
                "toolPermissionMode", "auto",
                "security.promptInjectionSuspected", "true"
        )), tool, call()));
    }

    @Test
    void readOnlySubAgentBlocksHighRiskEvenInFullPermissionMode() {
        AgentTool tool = new StaticRiskTool("high");

        assertThrows(IllegalStateException.class, () -> guard.check(task(Map.of(
                "agent.kind", "subagent",
                "agent.isolation", "read-only",
                "toolPermissionMode", "full"
        )), tool, call()));
    }

    @Test
    void readOnlySubAgentAllowsLowRiskQueries() {
        AgentTool tool = new StaticRiskTool("low");

        assertDoesNotThrow(() -> guard.check(task(Map.of(
                "agent.kind", "subagent",
                "agent.isolation", "read-only",
                "toolPermissionMode", "full"
        )), tool, call()));
    }

    private AgentTask task(Map<String, String> metadata) {
        return new AgentTask(
                "task-1",
                "input",
                "session-1",
                "webui",
                "console",
                new LinkedHashMap<>(metadata),
                Instant.now(),
                Instant.now(),
                TaskStatus.RUNNING,
                null);
    }

    private ToolCall call() {
        return new ToolCall("builtin.execute.command", Map.of("command", "npm", "args", "[\"install\"]"));
    }

    private static class StaticRiskTool implements AgentTool {
        private final String riskLevel;

        private StaticRiskTool(String riskLevel) {
            this.riskLevel = riskLevel;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("builtin.execute.command", "Execute Command", "test", riskLevel);
        }

        @Override
        public ToolResult execute(ToolCall call, AgentContext context) {
            return ToolResult.success("ok");
        }
    }
}
