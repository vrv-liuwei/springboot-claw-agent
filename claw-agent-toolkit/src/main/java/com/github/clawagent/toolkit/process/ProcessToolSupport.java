package com.github.clawagent.toolkit.process;

import cn.hutool.json.JSONUtil;
import com.github.clawagent.core.AgentContext;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.toolkit.execute.ExecuteToolkitProperties;
import com.github.clawagent.toolkit.execute.ProjectWorkingDirectoryResolver;
import org.apache.commons.exec.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 后台进程工具共享逻辑。
 * 必须保持 public，否则 Spring Boot DevTools restart classloader 下 public 工具类继承包可见父类会触发 IllegalAccessError。
 */
public abstract class ProcessToolSupport {
    protected final ManagedProcessStore store;
    protected final ExecuteToolkitProperties properties;
    private final ProjectWorkingDirectoryResolver cwdResolver;

    protected ProcessToolSupport(ManagedProcessStore store, ExecuteToolkitProperties properties) {
        this.store = store;
        this.properties = properties == null ? new ExecuteToolkitProperties() : properties;
        this.cwdResolver = new ProjectWorkingDirectoryResolver(this.properties);
    }

    protected Path resolveCwd(String rawCwd) {
        boolean useDefaultCwd = rawCwd == null || rawCwd.isBlank();
        Path cwd = useDefaultCwd ? Path.of(properties.getDefaultCwd()) : Path.of(rawCwd.trim());
        Path resolved = cwd.toAbsolutePath().normalize();
        boolean allowed = properties.allowedRootPaths().stream().anyMatch(resolved::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("cwd 不在 execute allowed roots 内：" + resolved);
        }
        if (useDefaultCwd) {
            try {
                Files.createDirectories(resolved);
            } catch (Exception e) {
                throw new IllegalStateException("创建 execute 默认工作目录失败：" + resolved, e);
            }
        }
        return resolved;
    }

    protected Path resolveCwd(String rawCwd, CommandInvocation invocation, AgentContext context) {
        return cwdResolver.resolve(rawCwd, invocation.command(), invocation.args(), context);
    }

    protected CommandInvocation commandInvocation(ToolCall call) {
        String rawCommand = required(call, "command");
        List<String> explicitArgs = args(call);
        List<String> commandParts = parseCommandLine(rawCommand);
        String command = commandParts.isEmpty() ? rawCommand : commandParts.get(0);
        List<String> mergedArgs = new ArrayList<>();
        if (commandParts.size() > 1) {
            mergedArgs.addAll(commandParts.subList(1, commandParts.size()));
        }
        mergedArgs.addAll(explicitArgs);
        String executable = resolveExecutable(command).orElse(command);
        return new CommandInvocation(command, executable, List.copyOf(mergedArgs));
    }

    protected List<String> args(ToolCall call) {
        String raw = call.arguments().get("args");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return JSONUtil.parseArray(raw).stream().map(Object::toString).toList();
        } catch (RuntimeException e) {
            return parseCommandLine("placeholder " + raw).stream().skip(1).toList();
        }
    }

    protected List<String> parseCommandLine(String commandLine) {
        try {
            CommandLine parsed = CommandLine.parse(commandLine);
            List<String> parts = new ArrayList<>();
            parts.add(parsed.getExecutable());
            parts.addAll(Arrays.asList(parsed.getArguments()));
            return parts.stream().filter(part -> part != null && !part.isBlank()).toList();
        } catch (RuntimeException e) {
            return List.of(commandLine.trim());
        }
    }

    protected Optional<String> resolveExecutable(String command) {
        if (!isWindows() || command.contains("\\") || command.contains("/") || command.contains(".")) {
            return Optional.empty();
        }
        if ("cmd".equalsIgnoreCase(command)) {
            String comspec = System.getenv("COMSPEC");
            if (comspec != null && !comspec.isBlank() && Path.of(comspec).toFile().isFile()) {
                return Optional.of(Path.of(comspec).toAbsolutePath().normalize().toString());
            }
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot != null && !systemRoot.isBlank()) {
                Path candidate = Path.of(systemRoot, "System32", "cmd.exe").toAbsolutePath().normalize();
                if (candidate.toFile().isFile()) {
                    return Optional.of(candidate.toString());
                }
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String dir : path.split(isWindows() ? ";" : ":")) {
            for (String extension : executableExtensions()) {
                File candidate = Path.of(dir, command + extension).toFile();
                if (candidate.isFile()) {
                    return Optional.of(candidate.getAbsolutePath());
                }
            }
        }
        return Optional.empty();
    }

    private List<String> executableExtensions() {
        return isWindows() ? List.of(".exe", ".cmd", ".bat", "") : List.of("");
    }

    protected boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    protected String required(ToolCall call, String name) {
        String value = call.arguments().get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value.trim();
    }

    protected long longArg(ToolCall call, String name, long defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }

    protected int intArg(ToolCall call, String name, int defaultValue) {
        String value = call.arguments().get(name);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    protected record CommandInvocation(String command, String executable, List<String> args) {
        List<String> processCommand() {
            List<String> commandLine = new ArrayList<>();
            commandLine.add(executable);
            commandLine.addAll(args);
            return commandLine;
        }

        String commandLineText() {
            return command + (args.isEmpty() ? "" : " " + String.join(" ", args));
        }
    }
}
