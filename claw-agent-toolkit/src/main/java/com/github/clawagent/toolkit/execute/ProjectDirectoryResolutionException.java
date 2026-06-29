package com.github.clawagent.toolkit.execute;

import java.nio.file.Path;
import java.util.List;

/**
 * 项目目录无法安全确定时抛出；工具输出会保留这些结构化字段，前端和模型都能识别为需要用户确认。
 */
public class ProjectDirectoryResolutionException extends IllegalArgumentException {
    private final String code;
    private final Path requestedCwd;
    private final List<Path> candidateProjects;

    public ProjectDirectoryResolutionException(String code, Path requestedCwd, List<Path> candidateProjects, String message) {
        super(format(code, requestedCwd, candidateProjects, message));
        this.code = code;
        this.requestedCwd = requestedCwd;
        this.candidateProjects = candidateProjects == null ? List.of() : List.copyOf(candidateProjects);
    }

    public String code() {
        return code;
    }

    public Path requestedCwd() {
        return requestedCwd;
    }

    public List<Path> candidateProjects() {
        return candidateProjects;
    }

    private static String format(String code, Path requestedCwd, List<Path> candidateProjects, String message) {
        StringBuilder builder = new StringBuilder();
        builder.append("requiresProjectConfirmation: true\n");
        builder.append("reasonCode: ").append(code).append('\n');
        builder.append("requestedCwd: ").append(requestedCwd == null ? "" : requestedCwd).append('\n');
        builder.append("message: ").append(message).append('\n');
        builder.append("candidateProjects:\n");
        List<Path> candidates = candidateProjects == null ? List.of() : candidateProjects;
        if (candidates.isEmpty()) {
            builder.append("- \n");
        } else {
            for (Path candidate : candidates) {
                builder.append("- ").append(candidate).append('\n');
            }
        }
        builder.append("nextAction: 请向用户确认要操作的项目目录，并在下一次工具调用中传入明确 cwd 或更新当前项目目录。");
        return builder.toString();
    }
}
