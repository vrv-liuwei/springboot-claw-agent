package com.github.clawagent.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 本地系统日志查询服务。
 *
 * <p>只做按需读取和过滤，不缓存完整日志内容；历史 .gz 文件通过流式解压逐行处理，避免大日志查询撑爆内存。</p>
 */
@Component
public class SystemLogQueryService {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;
    private static final int MAX_RAW_LENGTH = 20_000;
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+)\\s+\\[([^]]+)]\\s+traceId=([^ ]*)\\s+sessionId=([^ ]*)\\s+taskId=([^ ]*)\\s+userId=([^ ]*)\\s+channelId=([^ ]*)\\s+([^ ]+)\\s+-\\s?(.*)$");
    private static final Pattern GZ_DATE_PATTERN = Pattern.compile("^clawagent\\.log\\.(\\d{4}-\\d{2}-\\d{2})\\.\\d+\\.gz$");

    private final Path currentLogPath;

    public SystemLogQueryService(@Value("${logging.file.name:logs/clawagent.log}") String logFileName) {
        this.currentLogPath = Path.of(logFileName).toAbsolutePath().normalize();
    }

    public List<SystemLogLine> query(SystemLogQuery query) throws IOException {
        LocalDate today = LocalDate.now();
        LocalDate from = parseDate(query.from(), today);
        LocalDate to = parseDate(query.to(), from);
        if (to.isBefore(from)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        int limit = Math.max(1, Math.min(query.limit() == null ? DEFAULT_LIMIT : query.limit(), MAX_LIMIT));
        ArrayDeque<SystemLogLine> matches = new ArrayDeque<>(limit);
        for (LogSource source : resolveSources(from, to, today)) {
            readSource(source, query, matches, limit);
        }
        return new ArrayList<>(matches);
    }

    public List<SystemLogSource> sources() throws IOException {
        List<SystemLogSource> sources = new ArrayList<>();
        if (Files.exists(currentLogPath)) {
            sources.add(new SystemLogSource(currentLogPath.getFileName().toString(), null, Files.size(currentLogPath), false));
        }
        Path dir = currentLogPath.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return sources;
        }
        try (var stream = Files.list(dir)) {
            stream
                    .filter(path -> GZ_DATE_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            Matcher matcher = GZ_DATE_PATTERN.matcher(path.getFileName().toString());
                            String date = matcher.matches() ? matcher.group(1) : null;
                            sources.add(new SystemLogSource(path.getFileName().toString(), date, Files.size(path), true));
                        } catch (IOException ignored) {
                            // 文件可能在轮转中被删除，忽略该 source 即可。
                        }
                    });
        }
        return sources;
    }

    private List<LogSource> resolveSources(LocalDate from, LocalDate to, LocalDate today) throws IOException {
        List<LogSource> sources = new ArrayList<>();
        Path dir = currentLogPath.getParent();
        if (dir != null && Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                stream
                        .filter(path -> {
                            Matcher matcher = GZ_DATE_PATTERN.matcher(path.getFileName().toString());
                            if (!matcher.matches()) {
                                return false;
                            }
                            LocalDate date = LocalDate.parse(matcher.group(1));
                            return !date.isBefore(from) && !date.isAfter(to);
                        })
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            Matcher matcher = GZ_DATE_PATTERN.matcher(path.getFileName().toString());
                            matcher.matches();
                            sources.add(new LogSource(path, LocalDate.parse(matcher.group(1)), true));
                        });
            }
        }
        if (!today.isBefore(from) && !today.isAfter(to) && Files.exists(currentLogPath)) {
            sources.add(new LogSource(currentLogPath, today, false));
        }
        return sources;
    }

    private void readSource(LogSource source, SystemLogQuery query, ArrayDeque<SystemLogLine> matches, int limit) throws IOException {
        // try-with-resources 确保 .gz 解压流在本次查询结束后释放，不保留解压后的内存数据。
        try (BufferedReader reader = source.compressed()
                ? new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(source.path())), StandardCharsets.UTF_8))
                : Files.newBufferedReader(source.path(), StandardCharsets.UTF_8)) {
            PendingLog pending = null;
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LOG_PATTERN.matcher(line);
                if (matcher.matches()) {
                    appendIfMatched(pending, query, matches, limit);
                    pending = PendingLog.from(matcher, source);
                } else if (pending != null) {
                    pending.appendContinuation(line);
                }
            }
            appendIfMatched(pending, query, matches, limit);
        }
    }

    private void appendIfMatched(PendingLog pending, SystemLogQuery query, ArrayDeque<SystemLogLine> matches, int limit) {
        if (pending == null) {
            return;
        }
        SystemLogLine line = pending.toLine();
        if (!matches(line, query)) {
            return;
        }
        if (matches.size() == limit) {
            matches.removeFirst();
        }
        matches.addLast(line);
    }

    private boolean matches(SystemLogLine line, SystemLogQuery query) {
        return equalsIfPresent(query.level(), line.level())
                && containsIfPresent(line.logger(), query.logger())
                && containsIfPresent(line.rawLine(), query.keyword())
                && equalsIfPresent(query.userId(), line.userId())
                && equalsIfPresent(query.sessionId(), line.sessionId())
                && equalsIfPresent(query.taskId(), line.taskId());
    }

    private boolean equalsIfPresent(String expected, String actual) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return expected.trim().equalsIgnoreCase(actual == null ? "" : actual.trim());
    }

    private boolean containsIfPresent(String text, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return (text == null ? "" : text).toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT));
    }

    private LocalDate parseDate(String value, LocalDate defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim();
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException ignored) {
            return defaultValue;
        }
    }

    public record SystemLogQuery(String from,
                                 String to,
                                 String level,
                                 String keyword,
                                 String logger,
                                 String userId,
                                 String sessionId,
                                 String taskId,
                                 Integer limit) {
    }

    public record SystemLogLine(String time,
                                String level,
                                String thread,
                                String traceId,
                                String sessionId,
                                String taskId,
                                String userId,
                                String channelId,
                                String logger,
                                String message,
                                String rawLine,
                                String sourceFile,
                                boolean compressed) {
    }

    public record SystemLogSource(String name, String date, long size, boolean compressed) {
    }

    private record LogSource(Path path, LocalDate date, boolean compressed) {
    }

    private static final class PendingLog {
        private final LocalDateTime time;
        private final String level;
        private final String thread;
        private final String traceId;
        private final String sessionId;
        private final String taskId;
        private final String userId;
        private final String channelId;
        private final String logger;
        private final StringBuilder rawLine;
        private final String sourceFile;
        private final boolean compressed;
        private String message;
        private boolean truncated;

        private PendingLog(LocalDateTime time,
                           String level,
                           String thread,
                           String traceId,
                           String sessionId,
                           String taskId,
                           String userId,
                           String channelId,
                           String logger,
                           String message,
                           String rawLine,
                           String sourceFile,
                           boolean compressed) {
            this.time = time;
            this.level = level;
            this.thread = thread;
            this.traceId = traceId;
            this.sessionId = blankToNull(sessionId);
            this.taskId = blankToNull(taskId);
            this.userId = blankToNull(userId);
            this.channelId = blankToNull(channelId);
            this.logger = logger;
            this.message = message;
            this.rawLine = new StringBuilder(rawLine);
            this.sourceFile = sourceFile;
            this.compressed = compressed;
        }

        private static PendingLog from(Matcher matcher, LogSource source) {
            return new PendingLog(
                    LocalDateTime.parse(matcher.group(1), LOG_TIME_FORMATTER),
                    matcher.group(2),
                    matcher.group(3),
                    blankToNull(matcher.group(4)),
                    matcher.group(5),
                    matcher.group(6),
                    matcher.group(7),
                    matcher.group(8),
                    matcher.group(9),
                    matcher.group(10),
                    matcher.group(0),
                    source.path().getFileName().toString(),
                    source.compressed());
        }

        private void appendContinuation(String line) {
            if (rawLine.length() >= MAX_RAW_LENGTH) {
                truncated = true;
                return;
            }
            rawLine.append('\n').append(line);
            message = message + "\n" + line;
            if (rawLine.length() > MAX_RAW_LENGTH) {
                rawLine.setLength(MAX_RAW_LENGTH);
                truncated = true;
            }
        }

        private SystemLogLine toLine() {
            String raw = rawLine.toString();
            if (truncated) {
                raw = raw + "\n...[truncated]";
            }
            return new SystemLogLine(
                    time.format(LOG_TIME_FORMATTER),
                    level,
                    thread,
                    traceId,
                    sessionId,
                    taskId,
                    userId,
                    channelId,
                    logger,
                    message,
                    raw,
                    sourceFile,
                    compressed);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
