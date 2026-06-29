package com.github.clawagent.toolkit.filesystem;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolDefinition;
import com.github.clawagent.core.ToolResult;
import com.github.clawagent.spi.AgentTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 内置文本内容搜索工具。
 */
public class FilesystemSearchTextTool implements AgentTool {
    private static final int DEFAULT_MAX_DEPTH = 8;
    private static final int DEFAULT_LIMIT = 50;
    private static final long DEFAULT_TIMEOUT_MS = 10000;
    private static final Pattern RG_LINE = Pattern.compile("^(.+):(\\d+):(.*)$");

    private final FilesystemAccess access;

    public FilesystemSearchTextTool(FilesystemAccess access) {
        this.access = access;
    }

    @Override
    public ToolDefinition definition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", ToolDefinition.stringProperty("搜索根目录或文件路径，必须位于 allowed-roots 内"));
        properties.put("query", ToolDefinition.stringProperty("要搜索的文本内容"));
        properties.put("glob", ToolDefinition.stringProperty("可选 glob，例如 **/*.java，默认 **/*"));
        properties.put("engine", ToolDefinition.stringProperty("可选搜索引擎：java 或 powershell_rg，默认 java"));
        properties.put("regex", ToolDefinition.integerProperty("可选，1 表示按正则搜索；默认 0 表示文本包含搜索"));
        properties.put("ignoreCase", ToolDefinition.integerProperty("可选，1 表示忽略大小写；默认 0"));
        properties.put("maxDepth", ToolDefinition.integerProperty("可选最大搜索深度，默认 8"));
        properties.put("limit", ToolDefinition.integerProperty("可选最大返回条数，默认 50"));
        properties.put("timeoutMs", ToolDefinition.integerProperty("powershell_rg 引擎可选超时时间，默认 10000ms"));
        return ToolDefinition.low(
                "builtin.filesystem.search_text",
                "Search Text",
                "在 allowed-roots 内搜索文件内容，返回 path:line:content。支持 Java 搜索和 PowerShell 调用 rg。",
                ToolDefinition.objectSchema(properties, false, List.of("path", "query")));
    }

    @Override
    public ToolResult execute(ToolCall call, AgentContext context) {
        try {
            Path root = access.resolveReadable(call.arguments().get("path"));
            String query = required(call, "query");
            String engine = call.arguments().getOrDefault("engine", "java").trim().toLowerCase(Locale.ROOT);
            int limit = intArg(call, "limit", DEFAULT_LIMIT);
            return switch (engine) {
                case "java" -> ToolResult.success(searchWithJava(call, root, query, limit));
                case "rg", "powershell_rg", "powershell_command" -> ToolResult.success(searchWithPowerShellRg(call, root, query, limit));
                default -> ToolResult.error("不支持的搜索引擎：" + engine + "，可选 java/powershell_rg");
            };
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    private String searchWithJava(ToolCall call, Path root, String query, int limit) throws Exception {
        String glob = call.arguments().getOrDefault("glob", "**/*").trim();
        boolean regex = boolArg(call, "regex");
        boolean ignoreCase = boolArg(call, "ignoreCase");
        int maxDepth = intArg(call, "maxDepth", DEFAULT_MAX_DEPTH);
        Charset charset = charset(call.arguments().get("charset"));
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        Pattern pattern = regex ? Pattern.compile(query, ignoreCase ? Pattern.CASE_INSENSITIVE : 0) : null;
        List<String> matches = new ArrayList<>();
        Path walkRoot = Files.isRegularFile(root) ? root.getParent() : root;

        // Java 引擎不依赖外部命令，适合作为默认兜底；每个文件仍走访问边界和大小限制。
        try (var stream = Files.walk(walkRoot, Math.max(1, maxDepth))) {
            var files = Files.isRegularFile(root)
                    ? stream.filter(path -> path.equals(root))
                    : stream.filter(Files::isRegularFile)
                    .filter(path -> !access.isIgnored(path))
                    .filter(path -> matcher.matches(walkRoot.relativize(path)) || matcher.matches(path.getFileName()));
            for (Path file : files.toList()) {
                if (matches.size() >= limit) {
                    break;
                }
                searchFile(file, root, query, pattern, ignoreCase, charset, limit, matches);
            }
        }
        return formatResult(root, "java", matches, limit);
    }

    private void searchFile(Path file, Path root, String query, Pattern pattern, boolean ignoreCase,
                            Charset charset, int limit, List<String> matches) throws Exception {
        Path readable = access.resolveReadable(file.toString());
        access.checkReadSize(readable);
        try (BufferedReader reader = Files.newBufferedReader(readable, charset)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && matches.size() < limit) {
                lineNumber++;
                if (matches(line, query, pattern, ignoreCase)) {
                    matches.add(relative(root, readable) + ":" + lineNumber + ":" + line.stripTrailing());
                }
            }
        }
    }

    private boolean matches(String line, String query, Pattern pattern, boolean ignoreCase) {
        if (pattern != null) {
            return pattern.matcher(line).find();
        }
        if (ignoreCase) {
            return line.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
        }
        return line.contains(query);
    }

    private String searchWithPowerShellRg(ToolCall call, Path root, String query, int limit) throws Exception {
        String glob = call.arguments().getOrDefault("glob", "**/*").trim();
        boolean regex = boolArg(call, "regex");
        boolean ignoreCase = boolArg(call, "ignoreCase");
        long timeoutMs = longArg(call, "timeoutMs", DEFAULT_TIMEOUT_MS);
        String command = rgCommand(root, query, glob, regex, ignoreCase);
        Process process = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", command)
                .directory(root.toFile().isDirectory() ? root.toFile() : root.getParent().toFile())
                .redirectErrorStream(true)
                .start();
        List<String> matches = new ArrayList<>();
        CompletableFuture<Void> outputReader = CompletableFuture.runAsync(() -> drainRgOutput(root, process, matches, limit));
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
            throw new IllegalStateException("powershell_rg 搜索超时：" + Duration.ofMillis(timeoutMs));
        }
        outputReader.join();
        int exitCode = process.exitValue();
        if (exitCode > 1) {
            throw new IllegalStateException("rg 执行失败 exitCode=" + exitCode + "，请确认本机已安装 rg 并在 PATH 中");
        }
        return formatResult(root, "powershell_rg", matches, limit);
    }

    private void drainRgOutput(Path root, Process process, List<String> matches, int limit) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (matches.size() < limit) {
                    matches.add(normalizeRgLine(root, line));
                }
            }
        } catch (Exception ignored) {
            // 调用方会根据进程退出码或超时返回明确错误；读取线程只负责避免 stdout 管道阻塞。
        }
    }

    private String rgCommand(Path root, String query, String glob, boolean regex, boolean ignoreCase) {
        List<String> parts = new ArrayList<>();
        parts.add("rg");
        parts.add("--line-number");
        parts.add("--with-filename");
        parts.add("--color never");
        if (!regex) {
            parts.add("-F");
        }
        if (ignoreCase) {
            parts.add("-i");
        }
        parts.add("--glob " + quote(glob));
        access.properties().getIgnoredPatterns().forEach(pattern -> parts.add("--glob " + quote("!" + pattern)));
        parts.add("-- " + quote(query));
        parts.add(quote(root.toString()));
        return String.join(" ", parts);
    }

    private String normalizeRgLine(Path root, String line) {
        var matcher = RG_LINE.matcher(line);
        if (!matcher.matches()) {
            return line;
        }
        Path file = Path.of(matcher.group(1)).toAbsolutePath().normalize();
        return relative(root, file) + ":" + matcher.group(2) + ":" + matcher.group(3);
    }

    private String formatResult(Path root, String engine, List<String> matches, int limit) {
        StringBuilder output = new StringBuilder();
        output.append("root: ").append(root).append('\n');
        output.append("engine: ").append(engine).append('\n');
        output.append("count: ").append(matches.size()).append('\n');
        for (String match : matches) {
            output.append(match).append('\n');
        }
        if (matches.size() >= limit) {
            output.append("[结果已按 limit=").append(limit).append(" 截断]\n");
        }
        return output.toString().trim();
    }

    private String relative(Path root, Path file) {
        Path base = Files.isRegularFile(root) ? root.getParent() : root;
        try {
            return base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private Charset charset(String raw) {
        return raw == null || raw.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(raw.trim());
    }

    private String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    private int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
    }

    private long longArg(ToolCall call, String name, long defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value);
    }

    private boolean boolArg(ToolCall call, String name) {
        String value = call.arguments().get(name);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
