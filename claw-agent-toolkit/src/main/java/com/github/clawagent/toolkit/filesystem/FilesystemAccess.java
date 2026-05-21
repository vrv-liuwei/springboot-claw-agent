package com.github.clawagent.toolkit.filesystem;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件系统访问边界。
 * 所有内置 filesystem 工具都必须先经过这里校验，避免模型绕过 allowed-roots 访问任意路径。
 */
public class FilesystemAccess {
    private final FilesystemToolkitProperties properties;
    private final List<Path> allowedRoots;
    private final List<PathMatcher> blockedMatchers;

    public FilesystemAccess(FilesystemToolkitProperties properties) {
        this.properties = properties;
        this.allowedRoots = properties.getAllowedRoots().stream()
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
        this.blockedMatchers = properties.getBlockedPatterns().stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    public FilesystemToolkitProperties properties() {
        return properties;
    }

    public Path resolveReadable(String rawPath) {
        Path path = resolve(rawPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("路径不存在：" + path);
        }
        return path;
    }

    public Path resolveWritable(String rawPath) {
        if (properties.isReadonly()) {
            throw new IllegalStateException("文件系统工具当前是只读模式，禁止写入");
        }
        return resolve(rawPath);
    }

    public List<String> allowedRootTexts() {
        List<String> roots = new ArrayList<>();
        for (Path root : allowedRoots) {
            roots.add(root.toString());
        }
        return roots;
    }

    private Path resolve(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("缺少参数：path");
        }
        Path input = Path.of(rawPath.trim());
        Path path = (input.isAbsolute() ? input : Path.of(".").resolve(input)).toAbsolutePath().normalize();
        // 先限制根目录，再检查屏蔽规则，避免路径穿越或敏感文件读取。
        if (allowedRoots.stream().noneMatch(path::startsWith)) {
            throw new IllegalArgumentException("路径不在 allowed-roots 内：" + path + "，allowedRoots=" + allowedRootTexts());
        }
        if (isBlocked(path)) {
            throw new IllegalArgumentException("路径命中 blocked-patterns，已拒绝访问：" + path);
        }
        return path;
    }

    private boolean isBlocked(Path path) {
        for (PathMatcher matcher : blockedMatchers) {
            if (matcher.matches(path) || matcher.matches(path.getFileName())) {
                return true;
            }
            for (Path root : allowedRoots) {
                if (path.startsWith(root) && matcher.matches(root.relativize(path))) {
                    return true;
                }
            }
        }
        return false;
    }

    public void checkReadSize(Path path) throws IOException {
        if (Files.isRegularFile(path) && Files.size(path) > properties.getMaxReadBytes()) {
            throw new IllegalArgumentException("文件超过最大读取限制 maxReadBytes=" + properties.getMaxReadBytes() + "：" + path);
        }
    }
}
