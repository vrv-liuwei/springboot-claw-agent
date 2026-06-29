package com.github.clawagent.toolkit.execute;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 本机命令风险分类器。
 * 规则保持保守：只把明确的查询类命令降为 low，写入、删除、脚本、安装依赖等动作统一归 high。
 */
public class ExecuteCommandRiskClassifier {
    private static final Set<String> QUERY_COMMANDS = Set.of(
            "dir", "ls", "pwd", "whoami", "hostname", "where", "where.exe",
            "type", "cat", "findstr", "select-string", "get-childitem", "get-content",
            "git", "java", "node", "npm", "pnpm", "yarn", "mvn", "gradle", "cmd", "powershell", "pwsh");
    private static final Set<String> HIGH_RISK_COMMANDS = Set.of(
            "del", "erase", "rm", "rmdir", "rd", "format", "shutdown", "restart-computer",
            "stop-process", "taskkill", "kill", "set-content", "add-content", "out-file",
            "new-item", "copy-item", "move-item", "rename-item", "remove-item",
            "reg", "sc", "netsh", "choco", "scoop", "winget", "curl", "wget");
    private static final Set<String> HIGH_RISK_TOKENS = Set.of(
            "delete", "remove", "overwrite", "format", "install", "uninstall", "upgrade",
            "clean", "reset", "rebase", "push", "commit", "apply", "script", "invoke-expression", "iex");
    private static final Set<String> GIT_QUERY_SUBCOMMANDS = Set.of(
            "status", "diff", "log", "show", "branch", "rev-parse", "ls-files");
    private static final Set<String> VERSION_ARGS = Set.of("-v", "--version", "-version", "version");

    public CommandRiskAssessment classify(String command, List<String> args) {
        return classify(command, args, List.of());
    }

    public CommandRiskAssessment classify(String command, List<String> args, List<String> sensitivePathPatterns) {
        String normalizedCommand = normalize(command);
        List<String> rawArgs = args == null ? List.of() : args;
        List<String> normalizedArgs = rawArgs.stream().map(this::normalize).toList();
        if (normalizedCommand.isBlank()) {
            return CommandRiskAssessment.high("invalid", "缺少命令名");
        }
        if (containsSensitivePath(command, rawArgs, sensitivePathPatterns)) {
            return CommandRiskAssessment.high("sensitive-path", "命令涉及敏感路径，需要审批");
        }
        if (looksLikeScript(normalizedCommand) || normalizedArgs.stream().anyMatch(this::looksLikeScript)) {
            return CommandRiskAssessment.high("script", "执行脚本文件需要审批");
        }
        if (HIGH_RISK_COMMANDS.contains(normalizedCommand) || normalizedArgs.stream().anyMatch(HIGH_RISK_TOKENS::contains)) {
            return CommandRiskAssessment.high("mutating-command", "命令包含写入、删除、安装或破坏性动作");
        }
        if ("git".equals(normalizedCommand)) {
            return classifyGit(normalizedArgs);
        }
        if ("cmd".equals(normalizedCommand) || "powershell".equals(normalizedCommand) || "pwsh".equals(normalizedCommand)) {
            return classifyShellWrapper(normalizedCommand, normalizedArgs);
        }
        if (isVersionQuery(normalizedArgs)) {
            return CommandRiskAssessment.low("query", "版本查询命令默认允许");
        }
        if (QUERY_COMMANDS.contains(normalizedCommand)) {
            return CommandRiskAssessment.low("query", "查询类命令默认允许");
        }
        return CommandRiskAssessment.medium("unknown-command", "未知命令按中风险记录，后续可在自定义策略中收紧");
    }

    private CommandRiskAssessment classifyGit(List<String> args) {
        String subcommand = args.stream()
                .filter(arg -> !arg.startsWith("-"))
                .findFirst()
                .orElse("");
        if (GIT_QUERY_SUBCOMMANDS.contains(subcommand)) {
            return CommandRiskAssessment.low("git-query", "Git 查询类命令默认允许");
        }
        return CommandRiskAssessment.high("git-mutating", "Git 写入仓库或远端操作需要审批");
    }

    private CommandRiskAssessment classifyShellWrapper(String command, List<String> args) {
        String joined = String.join(" ", args);
        if (joined.isBlank()) {
            return CommandRiskAssessment.low("query", command + " 空参数不执行业务动作");
        }
        if (args.stream().anyMatch(arg -> "-file".equals(arg) || "/c".equals(arg) || "-command".equals(arg))) {
            // shell 包装命令可能隐藏多条语句，只有明显查询语句降为 low，其余保持 high。
            if (looksReadOnlyShell(joined)) {
                return CommandRiskAssessment.low("shell-query", "Shell 包装的是查询命令");
            }
            return CommandRiskAssessment.high("shell-script", "Shell 包装命令需要审批");
        }
        return CommandRiskAssessment.medium("shell", "Shell 启动命令按中风险记录");
    }

    private boolean looksReadOnlyShell(String joined) {
        String normalized = normalize(joined);
        if (HIGH_RISK_TOKENS.stream().anyMatch(normalized::contains)) {
            return false;
        }
        return normalized.contains("dir")
                || normalized.contains("git status")
                || normalized.contains("git diff")
                || normalized.contains("get-childitem")
                || normalized.contains("get-content");
    }

    private boolean isVersionQuery(List<String> args) {
        return !args.isEmpty() && args.stream().allMatch(VERSION_ARGS::contains);
    }

    private boolean looksLikeScript(String value) {
        return value.endsWith(".ps1")
                || value.endsWith(".bat")
                || value.endsWith(".cmd")
                || value.endsWith(".sh")
                || value.endsWith(".js")
                || value.endsWith(".py");
    }

    private boolean containsSensitivePath(String command, List<String> args, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        StringBuilder joined = new StringBuilder(normalizePathText(command));
        for (String arg : args) {
            joined.append(' ').append(normalizePathText(arg));
        }
        String commandLine = joined.toString();
        // execute 只拿到命令文本，不能像 filesystem 一样做完整路径解析；这里用保守 token 命中把风险升为审批。
        for (String pattern : patterns) {
            String needle = sensitiveNeedle(pattern);
            if (!needle.isBlank() && commandLine.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String sensitiveNeedle(String pattern) {
        if (pattern == null) {
            return "";
        }
        String normalized = normalizePathText(pattern)
                .replace("**/", "")
                .replace("/**", "")
                .replace("*", "");
        return normalized.trim();
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String normalizePathText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
    }
}
