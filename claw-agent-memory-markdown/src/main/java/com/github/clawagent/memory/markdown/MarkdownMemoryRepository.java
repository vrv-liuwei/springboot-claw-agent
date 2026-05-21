package com.github.clawagent.memory.markdown;

import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * MarkdownMemoryRepository 把 Markdown 作为长期记忆源数据读取。
 * 当前实现只做轻量关键词检索；后续 VectorStore 可以基于同一批 Markdown 重建语义索引。
 */
public class MarkdownMemoryRepository {
    private final Path memoryPath;

    public MarkdownMemoryRepository(Path memoryPath) {
        this.memoryPath = memoryPath;
    }

    public Path memoryPath() {
        return memoryPath;
    }

    public void initialize() {
        try {
            Files.createDirectories(memoryPath);
            Path readme = memoryPath.resolve("README.md");
            if (Files.notExists(readme)) {
                Files.writeString(readme, "# ClawAgent Memory\n\n这里保存长期 Markdown 记忆。\n", StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("初始化 Markdown 记忆目录失败：" + memoryPath, e);
        }
    }

    public List<MarkdownMemoryEntry> search(String keyword, int limit) {
        initialize();
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase();
        try (Stream<Path> paths = Files.walk(memoryPath)) {
            return paths.filter(path -> path.toString().endsWith(".md"))
                    .map(this::readEntry)
                    .filter(entry -> normalized.isEmpty() || entry.content().toLowerCase().contains(normalized)
                            || entry.path().toLowerCase().contains(normalized))
                    .sorted(Comparator.comparing(MarkdownMemoryEntry::path))
                    .limit(limit)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("检索 Markdown 记忆失败", e);
        }
    }

    public Path saveSessionSummary(AgentSession session, List<AgentMessage> messages) {
        initialize();
        try {
            Path sessionsDir = memoryPath.resolve("sessions");
            Files.createDirectories(sessionsDir);
            Path summaryPath = sessionsDir.resolve(safeFileName(session.id()) + ".md");
            Files.writeString(summaryPath, buildSessionSummaryMarkdown(session, messages), StandardCharsets.UTF_8);
            return summaryPath;
        } catch (IOException e) {
            throw new IllegalStateException("保存会话摘要 Markdown 记忆失败：" + session.id(), e);
        }
    }

    private MarkdownMemoryEntry readEntry(Path path) {
        try {
            String relative = memoryPath.relativize(path).toString().replace('\\', '/');
            return new MarkdownMemoryEntry(relative, Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 Markdown 记忆失败：" + path, e);
        }
    }

    private String buildSessionSummaryMarkdown(AgentSession session, List<AgentMessage> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("---\n");
        builder.append("type: session-summary\n");
        builder.append("sessionId: ").append(session.id()).append('\n');
        builder.append("title: ").append(escapeFrontMatter(session.title())).append('\n');
        builder.append("channelId: ").append(escapeFrontMatter(session.channelId())).append('\n');
        builder.append("userId: ").append(escapeFrontMatter(session.userId())).append('\n');
        builder.append("updatedAt: ").append(Instant.now()).append('\n');
        builder.append("---\n\n");
        builder.append("# ").append(session.title() == null || session.title().isBlank() ? session.id() : session.title()).append("\n\n");
        builder.append("## Summary\n\n");
        builder.append(session.summary() == null || session.summary().isBlank() ? "暂无摘要。" : session.summary()).append("\n\n");
        builder.append("## Recent Messages\n\n");
        int start = Math.max(0, messages.size() - 10);
        for (int i = start; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            // 只保存最近消息的短摘要，避免把完整会话原文写入长期记忆。
            builder.append("- **").append(message.role()).append("**: ")
                    .append(preview(message.content(), 300))
                    .append("\n");
        }
        return builder.toString();
    }

    private String safeFileName(String value) {
        return value == null || value.isBlank()
                ? "unknown"
                : value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private String escapeFrontMatter(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r", " ").replace("\n", " ");
    }

    private String preview(String text, int limit) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "...";
    }
}
