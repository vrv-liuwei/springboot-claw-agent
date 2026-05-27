package com.github.clawagent.toolkit.content;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Content Artifact 本地缓存配置。
 */
public class ContentArtifactProperties {
    private Path path = Path.of(".clawagent", "artifacts");
    private int chunkChars = 6000;
    private int summaryChars = 2400;
    private int readMaxChars = 12000;

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path == null ? Path.of(".clawagent", "artifacts") : path;
    }

    public int getChunkChars() {
        return chunkChars;
    }

    public void setChunkChars(int chunkChars) {
        this.chunkChars = chunkChars;
    }

    public int getSummaryChars() {
        return summaryChars;
    }

    public void setSummaryChars(int summaryChars) {
        this.summaryChars = summaryChars;
    }

    public int getReadMaxChars() {
        return readMaxChars;
    }

    public void setReadMaxChars(int readMaxChars) {
        this.readMaxChars = readMaxChars;
    }

    public static ContentArtifactProperties fromEnv(Map<String, String> env) {
        ContentArtifactProperties properties = new ContentArtifactProperties();
        if (env == null || env.isEmpty()) {
            return properties;
        }
        Map<String, String> normalized = env.entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(
                        entry -> entry.getKey().trim().toUpperCase(Locale.ROOT).replace('-', '_'),
                        Map.Entry::getValue,
                        (left, right) -> right
                ));
        properties.setPath(pathValue(normalized.get("PATH"), properties.getPath()));
        properties.setChunkChars(intValue(normalized.get("CHUNK_CHARS"), properties.getChunkChars()));
        properties.setSummaryChars(intValue(normalized.get("SUMMARY_CHARS"), properties.getSummaryChars()));
        properties.setReadMaxChars(intValue(normalized.get("READ_MAX_CHARS"), properties.getReadMaxChars()));
        return properties;
    }

    private static Path pathValue(String value, Path fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Path.of(value.trim());
    }

    private static int intValue(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
