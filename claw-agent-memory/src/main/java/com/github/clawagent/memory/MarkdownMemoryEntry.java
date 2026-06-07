package com.github.clawagent.memory;

/**
 * Markdown 记忆文件读取结果。
 *
 * @param path 相对记忆目录的 Markdown 文件路径。
 * @param content Markdown 文件内容。
 */
public record MarkdownMemoryEntry(String path, String content) {
}
