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
        assertEquals("local>channel>user>api-token>device>task>agent-role>agent-metadata>agent-isolation>tool-enforcement", resolution.resolutionOrder());
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

    @Test
    void infersUserApiTokenAndDevicePolicyScopesFromSource() {
        ApprovalPolicyResolution userResolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "policy.approval.source", "user:alice",
                "toolPermissionMode", "ask"
        ));
        ApprovalPolicyResolution apiTokenResolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "policy.approval.source", "api-token:ci-token",
                "toolPermissionMode", "custom"
        ));
        ApprovalPolicyResolution deviceResolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "policy.approval.source", "device:desktop-1",
                "toolPermissionMode", "custom"
        ));

        assertEquals("user", userResolution.scope());
        assertEquals("api-token", apiTokenResolution.scope());
        assertEquals("device", deviceResolution.scope());
    }

    @Test
    void infersAgentRoleAndAgentMetadataScopesFromSource() {
        ApprovalPolicyResolution roleResolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "policy.approval.source", "agent-role:coder",
                "toolPermissionMode", "custom"
        ));
        ApprovalPolicyResolution metadataResolution = ApprovalPolicyResolution.fromTaskMetadata(Map.of(
                "policy.approval.source", "agent:metadata",
                "toolPermissionMode", "ask"
        ));

        assertEquals("agent", roleResolution.scope());
        assertEquals("agent", metadataResolution.scope());
    }
}
