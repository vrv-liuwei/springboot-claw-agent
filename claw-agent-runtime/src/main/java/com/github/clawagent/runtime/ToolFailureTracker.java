package com.github.clawagent.runtime;

import com.github.clawagent.core.ToolCall;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ToolFailureTracker 记录单个任务内的工具失败模式。
 * 它负责阻断明显无意义的重复调用，把 token 消耗留给换方案而不是撞同一个错误。
 */
class ToolFailureTracker {
    private final Map<String, FailureRecord> failures = new LinkedHashMap<>();

    Optional<String> blockReason(ToolCall call) {
        FailureRecord record = failures.get(key(call));
        if (record == null) {
            return Optional.empty();
        }
        if (record.count >= retryLimit(record.category)) {
            return Optional.of(blockMessage(record));
        }
        return Optional.empty();
    }

    void recordFailure(ToolCall call, String error) {
        FailureCategory category = classify(error);
        failures.compute(key(call), (ignored, old) -> old == null
                ? new FailureRecord(category, 1, error)
                : new FailureRecord(category, old.count + 1, error));
    }

    Optional<String> recordSuccess(ToolCall call, String output) {
        if (classify(output) != FailureCategory.TRUNCATED) {
            return Optional.empty();
        }
        // 工具成功但输出已截断时，下一轮重复同参数只能拿到同样不完整的数据，必须换分页/增大限制/换 API。
        failures.put(key(call), new FailureRecord(FailureCategory.TRUNCATED, 1, output));
        return Optional.of(blockMessage(failures.get(key(call))));
    }

    private FailureCategory classify(String text) {
        String normalized = text == null ? "" : text.toLowerCase();
        if (normalized.contains("truncated: true")) {
            return FailureCategory.TRUNCATED;
        }
        if (normalized.contains("status=404") || normalized.contains("status= 404")
                || normalized.contains("status: 404") || normalized.contains("not found")
                || normalized.contains("不存在") || normalized.contains("不是普通文件")
                || normalized.contains("目标不是目录")) {
            return FailureCategory.NOT_FOUND;
        }
        if (normalized.contains("status=401") || normalized.contains("status: 401")
                || normalized.contains("status=403") || normalized.contains("status: 403")
                || normalized.contains("sign in") || normalized.contains("登录")) {
            return FailureCategory.AUTH_REQUIRED;
        }
        if (normalized.contains("timed out") || normalized.contains("timeout")
                || normalized.contains("超时")) {
            return FailureCategory.TIMEOUT;
        }
        if (normalized.contains("connection reset") || normalized.contains("unknownhost")
                || normalized.contains("ssl") || normalized.contains("dns")) {
            return FailureCategory.NETWORK_ERROR;
        }
        if (normalized.contains("缺少参数") || normalized.contains("invalid")
                || normalized.contains("bad request") || normalized.contains("参数")) {
            return FailureCategory.INVALID_ARGUMENT;
        }
        return FailureCategory.UNKNOWN;
    }

    private int retryLimit(FailureCategory category) {
        return switch (category) {
            case TIMEOUT, NETWORK_ERROR, UNKNOWN -> 2;
            case NOT_FOUND, AUTH_REQUIRED, INVALID_ARGUMENT, TRUNCATED -> 1;
        };
    }

    private String blockMessage(FailureRecord record) {
        return "工具调用被失败恢复策略阻断：category=" + record.category
                + ", failures=" + record.count
                + "。不要重复执行相同工具参数；请分析失败原因，换 API/路径/参数，或更新 Todo 为 failed/blocked 并说明阻塞条件。最近错误："
                + preview(record.lastMessage, 500);
    }

    private String key(ToolCall call) {
        Map<String, String> sortedArguments = new TreeMap<>(call.arguments());
        return call.toolId() + "|" + sortedArguments;
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }

    private enum FailureCategory {
        NOT_FOUND,
        TIMEOUT,
        AUTH_REQUIRED,
        TRUNCATED,
        NETWORK_ERROR,
        INVALID_ARGUMENT,
        UNKNOWN
    }

    private record FailureRecord(FailureCategory category, int count, String lastMessage) {
    }
}
