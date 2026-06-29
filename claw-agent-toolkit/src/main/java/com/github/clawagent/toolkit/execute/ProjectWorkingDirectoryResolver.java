package com.github.clawagent.toolkit.execute;

import com.github.clawagent.core.AgentContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 项目级命令的工作目录解析。
 * DEFAULT_CWD 只是工具兜底目录；启动、构建、测试命令必须尽量落到真实项目目录，避免误跑聚合根目录。
 */
public class ProjectWorkingDirectoryResolver {
    private final ExecuteToolkitProperties properties;

    public ProjectWorkingDirectoryResolver(ExecuteToolkitProperties properties) {
        this.properties = properties == null ? new ExecuteToolkitProperties() : properties;
    }

    public Path resolve(String rawCwd, String command, List<String> args, AgentContext context) {
        Path cwd = baseCwd(rawCwd);
        ensureAllowed(cwd);
        if (!isProjectCommand(command, args)) {
            createDefaultCwdIfNeeded(rawCwd, cwd);
            return cwd;
        }
        Optional<Path> metadataProject = projectPathFromMetadata(context);
        if (metadataProject.isPresent()) {
            return resolveProjectDirectory(metadataProject.get(), command, "metadata");
        }
        return resolveProjectDirectory(cwd, command, "cwd");
    }

    private Path resolveProjectDirectory(Path cwd, String command, String source) {
        ensureAllowed(cwd);
        if (!Files.isDirectory(cwd)) {
            throw new ProjectDirectoryResolutionException(
                    "project-directory-not-found",
                    cwd,
                    List.of(),
                    "当前项目目录不存在，不能继续执行项目级命令。");
        }
        if (isRunnableProject(cwd, command)) {
            return cwd;
        }
        List<Path> candidates = runnableProjectsUnder(cwd, command);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            throw new ProjectDirectoryResolutionException(
                    "multiple-project-candidates",
                    cwd,
                    candidates,
                    "当前" + source + "目录不是唯一可运行项目，发现多个候选项目。");
        }
        throw new ProjectDirectoryResolutionException(
                "project-directory-not-runnable",
                cwd,
                List.of(),
                "当前" + source + "目录不是可运行项目目录，也未在其一级子目录找到可运行项目。");
    }

    private Path baseCwd(String rawCwd) {
        boolean useDefaultCwd = rawCwd == null || rawCwd.isBlank();
        Path cwd = useDefaultCwd ? Path.of(properties.getDefaultCwd()) : Path.of(rawCwd.trim());
        return cwd.toAbsolutePath().normalize();
    }

    private void ensureAllowed(Path path) {
        boolean allowed = properties.allowedRootPaths().stream().anyMatch(path::startsWith);
        if (!allowed) {
            throw new IllegalArgumentException("cwd 不在 execute allowed roots 内：" + path);
        }
    }

    private void createDefaultCwdIfNeeded(String rawCwd, Path cwd) {
        if (rawCwd != null && !rawCwd.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(cwd);
        } catch (Exception e) {
            throw new IllegalStateException("创建 execute 默认工作目录失败：" + cwd, e);
        }
    }

    private Optional<Path> projectPathFromMetadata(AgentContext context) {
        if (context == null || context.task() == null || context.task().metadata() == null) {
            return Optional.empty();
        }
        for (String key : List.of("activeProjectPath", "projectPath", "workspace.projectPath", "cwd")) {
            String value = context.task().metadata().get(key);
            if (value != null && !value.isBlank()) {
                return Optional.of(Path.of(value.trim()).toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private boolean isProjectCommand(String command, List<String> args) {
        String executable = commandName(command);
        String commandLine = (executable + " " + String.join(" ", args == null ? List.of() : args)).toLowerCase(Locale.ROOT);
        return executable.equals("mvn")
                || executable.equals("mvnw")
                || executable.equals("gradle")
                || executable.equals("gradlew")
                || executable.equals("npm")
                || executable.equals("pnpm")
                || executable.equals("yarn")
                || commandLine.contains("spring-boot:run")
                || commandLine.contains("npm run")
                || commandLine.contains("pnpm run")
                || commandLine.contains("yarn ");
    }

    private boolean isRunnableProject(Path dir, String command) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        String executable = commandName(command);
        if (executable.equals("mvn") || executable.equals("mvnw")) {
            return isMavenApplication(dir);
        }
        if (executable.equals("npm") || executable.equals("pnpm") || executable.equals("yarn")) {
            return Files.isRegularFile(dir.resolve("package.json"));
        }
        if (executable.equals("gradle") || executable.equals("gradlew")) {
            return Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts"));
        }
        return isMavenApplication(dir) || Files.isRegularFile(dir.resolve("package.json"))
                || Files.isRegularFile(dir.resolve("build.gradle")) || Files.isRegularFile(dir.resolve("build.gradle.kts"));
    }

    private boolean isMavenApplication(Path dir) {
        if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
            return false;
        }
        Path mainJava = dir.resolve("src/main/java");
        if (!Files.isDirectory(mainJava)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(mainJava, 8)) {
            return stream.anyMatch(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("Application.java"));
        } catch (IOException e) {
            return false;
        }
    }

    private List<Path> runnableProjectsUnder(Path dir, String command) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> isRunnableProject(path, command))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private String commandName(String command) {
        String executable = command == null ? "" : Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        if (executable.endsWith(".cmd") || executable.endsWith(".exe") || executable.endsWith(".bat")) {
            executable = executable.substring(0, executable.lastIndexOf('.'));
        }
        return executable;
    }
}
