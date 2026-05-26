package com.github.clawagent.runtime;

import com.github.clawagent.core.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailureTrackerTest {

    @Test
    void blocksRepeatedNotFoundCallAfterFirstFailure() {
        ToolFailureTracker tracker = new ToolFailureTracker();
        ToolCall call = new ToolCall("builtin.web.fetch", Map.of("url", "https://example.com/missing"));

        tracker.recordFailure(call, "HTTP 请求失败 status=404 body=Not Found");

        assertTrue(tracker.blockReason(call).isPresent());
    }

    @Test
    void allowsTimeoutRetryOnceThenBlocksSameCall() {
        ToolFailureTracker tracker = new ToolFailureTracker();
        ToolCall call = new ToolCall("builtin.web.fetch", Map.of("url", "https://example.com/slow"));

        tracker.recordFailure(call, "SocketTimeoutException: Connect timed out");
        assertFalse(tracker.blockReason(call).isPresent());

        tracker.recordFailure(call, "SocketTimeoutException: Connect timed out");
        assertTrue(tracker.blockReason(call).isPresent());
    }

    @Test
    void blocksRepeatedTruncatedSuccessWithSameArguments() {
        ToolFailureTracker tracker = new ToolFailureTracker();
        ToolCall call = new ToolCall("builtin.web.fetch", Map.of("url", "https://example.com/big", "maxOutputChars", "12000"));

        tracker.recordSuccess(call, "url: https://example.com/big\ntruncated: true\nbody...");

        assertTrue(tracker.blockReason(call).isPresent());
    }
}
