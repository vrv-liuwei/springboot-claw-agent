package com.github.clawagent.skill;

import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.core.ToolResult;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 脚本型 Skill 执行器。
 * 适合把稳定、重复、需要确定性执行的逻辑放进 Skill 的 scripts/ 目录。
 */
class ScriptSkillExecutor implements SkillExecutor {
    @Override
    public ToolResult execute(SkillExecutionContext context, ToolCall call, AgentContext agentContext) {
        requireShellPermission(context);
        Map<String, Object> config = context.executorConfig();
        String command = required(config, "command");
        List<String> args = readStringList(config.get("args"));
        int timeoutSeconds = intValue(config, "timeoutSeconds", 30);
        try {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(resolveCommand(context, render(command, call)));
            for (String arg : args) {
                commandLine.add(render(arg, call));
            }
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.directory(resolveCwd(context, stringValue(config, "cwd", "")));
            builder.environment().putAll(resolveEnv(config, call));
            Process process = builder.start();
            boolean finished = process.waitFor(Duration.ofSeconds(timeoutSeconds).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("Script Skill 执行超时 timeoutSeconds=" + timeoutSeconds);
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String output = "exitCode=" + process.exitValue() + "\nstdout:\n" + stdout + "\nstderr:\n" + stderr;
            return process.exitValue() == 0 ? ToolResult.success(output) : ToolResult.error(output);
        } catch (Exception e) {
            return ToolResult.error("Script Skill 执行失败：" + e.getMessage());
        }
    }

    private String resolveCommand(SkillExecutionContext context, String command) {
        Path root = context.skillDir().normalize();
        Path commandPath = Path.of(command);
        if (!commandPath.isAbsolute() && (command.contains("/") || command.contains("\\"))) {
            Path resolved = root.resolve(command).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException("Script command 越权：" + command);
            }
            return resolved.toString();
        }
        // python、node、powershell 等解释器命令允许从 PATH 查找；真正脚本文件应放在 args 中。
        return command;
    }

    private File resolveCwd(SkillExecutionContext context, String cwd) {
        Path root = context.skillDir().normalize();
        if (cwd == null || cwd.isBlank()) {
            return root.toFile();
        }
        Path resolved = root.resolve(cwd).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Script cwd 越权：" + cwd);
        }
        return resolved.toFile();
    }

    private Map<String, String> resolveEnv(Map<String, Object> config, ToolCall call) {
        Map<String, String> env = new LinkedHashMap<>();
        Object value = config.get("env");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> env.put(String.valueOf(key), render(String.valueOf(item), call)));
        }
        return env;
    }

    private void requireShellPermission(SkillExecutionContext context) {
        boolean allowed = context.manifest().permissions().stream()
                .map(item -> item == null ? "" : item.toLowerCase())
                .anyMatch(item -> item.contains("shell") || item.contains("script"));
        if (!allowed) {
            throw new IllegalStateException("Skill 未声明 shell/script 权限，不能执行 script executor");
        }
    }

    private List<String> readStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String render(String template, ToolCall call) {
        String result = template == null ? "" : template;
        for (Map.Entry<String, String> entry : call.arguments().entrySet()) {
            result = result.replace("${arg." + entry.getKey() + "}", entry.getValue());
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private String required(Map<String, Object> config, String key) {
        String value = stringValue(config, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Script Skill 缺少 executor." + key);
        }
        return value;
    }

    private String stringValue(Map<String, Object> config, String key, String fallback) {
        Object value = config.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private int intValue(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
