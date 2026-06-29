package com.github.clawagent.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyResolutionTest {
    @Test
    void recordsChannelSourceAndModeConflict() {
        ApprovalPolicyResolution resolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "channel.id", "feishu",
                "toolPermissionMode", "auto",
                "approvalMode", "ask"
        ));

        assertEquals("auto", resolution.policy().mode());
        assertEquals("channel:feishu", resolution.source());
        assertEquals("channel", resolution.scope());
        assertEquals("local>channel>task>agent-isolation>tool-enforcement", resolution.resolutionOrder());
        assertTrue(resolution.conflictNotes().contains("toolPermissionMode=auto 覆盖 approvalMode=ask"));
    }

    @Test
    void recordsReadOnlyAgentSourceAndApprovedToolConflict() {
        ApprovalPolicyResolution resolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "agent.isolation", "read-only",
                "approvedToolIds", "builtin.execute.command",
                "policy.overrideReason", "只读子 Agent 不继承父任务高危批准。"
        ));

        assertEquals("agent-isolation:read-only", resolution.source());
        assertEquals("agent", resolution.scope());
        assertEquals("只读子 Agent 不继承父任务高危批准。", resolution.overrideReason());
        assertTrue(resolution.conflictNotes().get(0).contains("只读子 Agent 不应携带 approvedToolIds"));
    }
}
