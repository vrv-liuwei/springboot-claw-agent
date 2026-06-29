package com.github.clawagent.server;

import com.github.clawagent.server.dto.FileChangeView;
import com.github.clawagent.server.service.FileChangeReviewSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileChangeReviewSupportTest {
    @Test
    void keepsOnlyLatestChangeForSamePath() {
        List<FileChangeView> latest = FileChangeReviewSupport.latestFileChanges(List.of(
                change("1", "modified", "D:\\workspace\\demo\\src\\App.java", "2026-06-12T00:00:00Z"),
                change("2", "modified", "D:/workspace/demo/src/App.java", "2026-06-12T00:01:00Z"),
                change("3", "created", "D:/workspace/demo/src/Other.java", "2026-06-12T00:02:00Z")
        ));

        assertEquals(2, latest.size());
        assertEquals("3", latest.get(0).id());
        assertEquals("2", latest.get(1).id());
        assertEquals("latest", latest.get(1).reviewStatus());
        assertEquals(1, latest.get(1).supersededCount());
    }

    @Test
    void marksRollbackAndFailedAsReviewState() {
        List<FileChangeView> latest = FileChangeReviewSupport.latestFileChanges(List.of(
                change("1", "modified", "D:/workspace/demo/src/App.java", "2026-06-12T00:00:00Z"),
                change("2", "rollback", "D:/workspace/demo/src/App.java", "2026-06-12T00:01:00Z"),
                change("3", "failed", "D:/workspace/demo/src/Broken.java", "2026-06-12T00:02:00Z")
        ));

        assertEquals("failed", latest.get(0).reviewStatus());
        assertEquals("rolled-back", latest.get(1).reviewStatus());
        assertEquals(1, latest.get(1).supersededCount());
    }

    @Test
    void ignoresBlankPaths() {
        List<FileChangeView> latest = FileChangeReviewSupport.latestFileChanges(List.of(
                change("1", "modified", "", "2026-06-12T00:00:00Z"),
                change("2", "modified", "D:/workspace/demo/src/App.java", "2026-06-12T00:01:00Z")
        ));

        assertEquals(1, latest.size());
        assertEquals("2", latest.get(0).id());
    }

    @Test
    void limitsAfterLatestPathFolding() {
        List<FileChangeView> latest = FileChangeReviewSupport.latestFileChanges(List.of(
                change("1", "modified", "D:/workspace/demo/src/App.java", "2026-06-12T00:00:00Z"),
                change("2", "modified", "D:/workspace/demo/src/App.java", "2026-06-12T00:01:00Z"),
                change("3", "modified", "D:/workspace/demo/src/B.java", "2026-06-12T00:02:00Z"),
                change("4", "modified", "D:/workspace/demo/src/C.java", "2026-06-12T00:03:00Z")
        )).stream().limit(2).toList();

        assertEquals(2, latest.size());
        assertEquals("4", latest.get(0).id());
        assertEquals("3", latest.get(1).id());
    }

    @Test
    void longTaskKeepsLatestVersionForRepeatedFileChanges() {
        java.util.ArrayList<FileChangeView> changes = new java.util.ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            changes.add(change(
                    "app-" + i,
                    "modified",
                    i % 2 == 0 ? "D:\\workspace\\demo\\src\\App.java" : "D:/workspace/demo/src/App.java",
                    String.format("2026-06-12T00:%02d:%02dZ", (i / 60) % 60, i % 60)));
        }
        for (int i = 0; i < 50; i++) {
            changes.add(change(
                    "other-" + i,
                    "modified",
                    "D:/workspace/demo/src/Other" + i + ".java",
                    String.format("2026-06-12T01:%02d:00Z", i % 60)));
        }

        List<FileChangeView> latest = FileChangeReviewSupport.latestFileChanges(changes);

        FileChangeView app = latest.stream()
                .filter(change -> change.path().replace('\\', '/').endsWith("/App.java"))
                .findFirst()
                .orElseThrow();
        assertEquals("app-1199", app.id());
        assertEquals("latest", app.reviewStatus());
        assertEquals(1199, app.supersededCount());
        assertEquals(51, latest.size());
    }

    private FileChangeView change(String id, String changeType, String path, String createdAt) {
        return new FileChangeView(
                id,
                "task-1",
                "step-" + id,
                "builtin.filesystem.write_file",
                changeType,
                path,
                "backup-" + id,
                "",
                1,
                0,
                "todo-1",
                "1",
                "测试文件审查",
                Instant.parse(createdAt),
                null,
                0);
    }
}
