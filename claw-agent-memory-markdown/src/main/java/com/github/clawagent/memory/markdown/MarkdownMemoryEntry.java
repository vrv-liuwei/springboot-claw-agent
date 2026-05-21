package com.github.clawagent.memory.markdown;

public record MarkdownMemoryEntry(String path, String content) {
    public String preview() {
        String compact = content.replaceAll("\\s+", " ").trim();
        return compact.length() <= 180 ? compact : compact.substring(0, 180) + "...";
    }
}
