package com.github.clawagent.toolkit.content;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地内容缓存。
 * 第一版不做向量化，只保存 raw/readable/summary/chunks，并提供关键词读取能力。
 */
public class ContentArtifactStore {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ContentArtifactProperties properties;

    public ContentArtifactStore(ContentArtifactProperties properties) {
        this.properties = properties == null ? new ContentArtifactProperties() : properties;
    }

    public ContentArtifact save(String kind, String source, String contentType, String rawText, String readableText) {
        try {
            String safeKind = safeKind(kind);
            String raw = rawText == null ? "" : rawText;
            String readable = normalize(readableText == null || readableText.isBlank() ? raw : readableText);
            String artifactId = safeKind + "_" + sha256(source == null ? readable : source).substring(0, 16);
            Path directory = properties.getPath().resolve(safeKind).resolve(artifactId);
            Path chunksDir = directory.resolve("chunks");
            Files.createDirectories(chunksDir);

            // 缓存完整原文和清洗正文，后续 ReAct 需要细节时直接读取本地内容，不重复请求 URL。
            Files.writeString(directory.resolve("raw.txt"), raw, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("readable.txt"), readable, StandardCharsets.UTF_8);

            List<String> chunks = splitChunks(readable, properties.getChunkChars());
            for (int i = 0; i < chunks.size(); i++) {
                Files.writeString(chunksDir.resolve(String.format("%04d.txt", i + 1)), chunks.get(i), StandardCharsets.UTF_8);
            }

            String summary = summarize(source, contentType, readable, chunks);
            Files.writeString(directory.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            writeMetadata(directory, artifactId, safeKind, source, contentType, raw, readable, summary, chunks.size());
            return new ContentArtifact(
                    artifactId,
                    safeKind,
                    source,
                    contentType,
                    directory,
                    raw.length(),
                    readable.length(),
                    summary.length(),
                    chunks.size(),
                    summary);
        } catch (Exception e) {
            throw new IllegalStateException("保存 content artifact 失败：" + e.getMessage(), e);
        }
    }

    public String read(String artifactId, Integer chunk, String query, int maxChars) {
        try {
            Path directory = artifactDirectory(artifactId);
            int limit = maxChars <= 0 ? properties.getReadMaxChars() : maxChars;
            if (chunk != null && chunk > 0) {
                return limit(readChunk(directory, chunk), limit);
            }
            if (query != null && !query.isBlank()) {
                return limit(searchChunks(directory, query), limit);
            }
            return limit(Files.readString(directory.resolve("summary.md"), StandardCharsets.UTF_8), limit);
        } catch (Exception e) {
            throw new IllegalStateException("读取 content artifact 失败：" + e.getMessage(), e);
        }
    }

    private void writeMetadata(Path directory, String artifactId, String kind, String source, String contentType,
                               String raw, String readable, String summary, int chunkCount) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("artifactId", artifactId);
        metadata.put("kind", kind);
        metadata.put("source", source);
        metadata.put("contentType", contentType);
        metadata.put("contentHash", sha256(readable));
        metadata.put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        metadata.put("originalChars", raw.length());
        metadata.put("readableChars", readable.length());
        metadata.put("summaryChars", summary.length());
        metadata.put("chunkCount", chunkCount);
        Files.writeString(directory.resolve("metadata.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metadata), StandardCharsets.UTF_8);
    }

    private String summarize(String source, String contentType, String readable, List<String> chunks) {
        StringBuilder builder = new StringBuilder();
        builder.append("# Content Summary\n\n");
        builder.append("## Source\n");
        builder.append("- source: ").append(source == null ? "" : source).append('\n');
        builder.append("- contentType: ").append(contentType == null ? "" : contentType).append('\n');
        builder.append("- readableChars: ").append(readable.length()).append('\n');
        builder.append("- chunkCount: ").append(chunks.size()).append("\n\n");
        builder.append("## Overview\n");
        builder.append(firstParagraph(readable)).append("\n\n");
        builder.append("## Key Sections\n");
        extractHighlights(readable).forEach(line -> builder.append("- ").append(line).append('\n'));
        if (builder.toString().endsWith("## Key Sections\n")) {
            builder.append("- 未识别到明确标题或配置段落，请按 chunk 读取原文。\n");
        }
        builder.append("\n## Suggested Reads\n");
        for (int i = 0; i < Math.min(chunks.size(), 8); i++) {
            builder.append("- chunk ").append(i + 1).append(": ")
                    .append(preview(chunks.get(i), 90)).append('\n');
        }
        return limit(builder.toString(), properties.getSummaryChars());
    }

    private List<String> extractHighlights(String text) {
        List<String> highlights = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isBlank()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            boolean heading = line.startsWith("#") || line.matches("^[0-9]+[.)、].+");
            boolean keyword = lower.contains("install")
                    || lower.contains("usage")
                    || lower.contains("quickstart")
                    || lower.contains("configuration")
                    || lower.contains("api key")
                    || lower.contains("mcp")
                    || lower.contains("skill")
                    || lower.contains("docker")
                    || lower.contains("安装")
                    || lower.contains("使用")
                    || lower.contains("配置")
                    || lower.contains("启动")
                    || lower.contains("环境变量");
            if (heading || keyword) {
                highlights.add(preview(line.replaceFirst("^#+\\s*", ""), 160));
            }
            if (highlights.size() >= 20) {
                break;
            }
        }
        return highlights;
    }

    private String searchChunks(Path directory, String query) throws IOException {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String[] terms = normalizedQuery.split("[\\s,;，；]+");
        List<ScoredChunk> chunks = new ArrayList<>();
        Path chunksDir = directory.resolve("chunks");
        if (!Files.isDirectory(chunksDir)) {
            return Files.readString(directory.resolve("summary.md"), StandardCharsets.UTF_8);
        }
        try (var stream = Files.list(chunksDir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".txt")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                int score = score(content.toLowerCase(Locale.ROOT), terms);
                if (score > 0) {
                    chunks.add(new ScoredChunk(file.getFileName().toString(), content, score));
                }
            }
        }
        if (chunks.isEmpty()) {
            return "未在缓存内容中找到 query=" + query + " 的匹配片段。\n\n"
                    + Files.readString(directory.resolve("summary.md"), StandardCharsets.UTF_8);
        }
        chunks.sort(Comparator.comparingInt(ScoredChunk::score).reversed());
        StringBuilder builder = new StringBuilder();
        builder.append("artifact query: ").append(query).append("\n\n");
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            ScoredChunk chunk = chunks.get(i);
            builder.append("## ").append(chunk.name()).append(" score=").append(chunk.score()).append("\n\n");
            builder.append(chunk.content()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private int score(String content, String[] terms) {
        int score = 0;
        for (String term : terms) {
            if (!term.isBlank() && content.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private String readChunk(Path directory, int chunk) throws IOException {
        Path file = directory.resolve("chunks").resolve(String.format("%04d.txt", chunk));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("chunk 不存在：" + chunk);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path artifactDirectory(String artifactId) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("非法 artifactId：" + artifactId);
        }
        String kind = artifactId.contains("_") ? artifactId.substring(0, artifactId.indexOf('_')) : "web";
        Path directory = properties.getPath().resolve(kind).resolve(artifactId).normalize();
        Path root = properties.getPath().toAbsolutePath().normalize();
        Path absolute = directory.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || !Files.isDirectory(absolute)) {
            throw new IllegalArgumentException("artifact 不存在：" + artifactId);
        }
        return absolute;
    }

    private List<String> splitChunks(String text, int chunkChars) {
        int size = chunkChars <= 0 ? 6000 : chunkChars;
        List<String> chunks = new ArrayList<>();
        String content = text == null ? "" : text;
        for (int start = 0; start < content.length(); start += size) {
            chunks.add(content.substring(start, Math.min(content.length(), start + size)));
        }
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }

    private String firstParagraph(String text) {
        for (String paragraph : text.split("\\R\\s*\\R")) {
            String value = paragraph.trim();
            if (!value.isBlank()) {
                return preview(value, 500);
            }
        }
        return "无可用正文。";
    }

    private String safeKind(String kind) {
        String value = kind == null || kind.isBlank() ? "web" : kind.trim().toLowerCase(Locale.ROOT);
        return value.replaceAll("[^a-z0-9_-]", "-");
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private String preview(String text, int maxChars) {
        return limit(text.replaceAll("\\s+", " ").trim(), maxChars);
    }

    private String limit(String text, int maxChars) {
        String value = text == null ? "" : text;
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n\n[内容已截断，使用 builtin.content.read 按 artifactId/chunk/query 读取缓存原文]";
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("sha256 计算失败", e);
        }
    }

    private record ScoredChunk(String name, String content, int score) {}
}
