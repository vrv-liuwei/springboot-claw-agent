package com.github.clawagent.server.service;

import com.github.clawagent.server.dto.FileChangeView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件审查列表的服务端归一化逻辑。
 * 前端可以再做一次兜底去重，但任务详情、历史会话和开发摘要都应优先依赖这里的权威结果。
 */
public final class FileChangeReviewSupport {
    private FileChangeReviewSupport() {
    }

    public static List<FileChangeView> latestFileChanges(List<FileChangeView> changes) {
        Map<String, List<FileChangeView>> changesByPath = new LinkedHashMap<>();
        changes.stream()
                .filter(change -> change != null && !normalizeFileChangePath(change.path()).isBlank())
                .sorted(Comparator.comparing(FileChangeView::createdAt))
                .forEach(change -> {
                    // 同一个任务可能多次修改同一文件；按路径聚合后只暴露最终版本，同时保留被折叠的历史次数。
                    String key = normalizeFileChangePath(change.path());
                    changesByPath.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change);
                });
        return changesByPath.values().stream()
                .map(group -> withReviewState(group.get(group.size() - 1), reviewStatus(group.get(group.size() - 1)), group.size() - 1))
                .sorted(Comparator.comparing(FileChangeView::createdAt).reversed())
                .toList();
    }

    public static String reviewStatus(FileChangeView change) {
        String changeType = nullToEmpty(change.changeType()).toLowerCase(Locale.ROOT);
        if ("failed".equals(changeType)) {
            return "failed";
        }
        if ("rollback".equals(changeType)) {
            return "rolled-back";
        }
        return "latest";
    }

    public static String normalizeFileChangePath(String path) {
        return nullToEmpty(path).replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static FileChangeView withReviewState(FileChangeView change, String reviewStatus, int supersededCount) {
        return new FileChangeView(
                change.id(),
                change.taskId(),
                change.stepId(),
                change.toolId(),
                change.changeType(),
                change.path(),
                change.backupPath(),
                change.diff(),
                change.addedLines(),
                change.deletedLines(),
                change.todoId(),
                change.todoOrder(),
                change.todoTitle(),
                change.createdAt(),
                reviewStatus,
                Math.max(0, supersededCount));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
