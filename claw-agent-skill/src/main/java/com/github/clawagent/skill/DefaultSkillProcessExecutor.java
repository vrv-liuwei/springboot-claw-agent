package com.github.clawagent.skill;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Script Skill 的默认本地执行器。
 * 非 Spring 或未启用 worker 的场景继续保持轻量直接执行。
 */
class DefaultSkillProcessExecutor implements SkillProcessExecutor {
    @Override
    public Result execute(List<String> commandLine, File cwd, Map<String, String> env, long timeoutMs) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(commandLine);
        builder.directory(cwd);
        builder.environment().putAll(env);
        long started = System.nanoTime();
        Process process = builder.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutReader = streamReader(process.getInputStream(), stdout);
        Thread stderrReader = streamReader(process.getErrorStream(), stderr);
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            // 直接执行只作为兼容路径，超时时仍强杀整棵进程树，避免脚本子进程遗留。
            stopProcessTree(process.toHandle());
        }
        stdoutReader.join(1000);
        stderrReader.join(1000);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return new Result(
                finished ? process.exitValue() : 124,
                !finished,
                elapsedMs,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8),
                false,
                false,
                false,
                false,
                "",
                0,
                0,
                0,
                0);
    }

    private Thread streamReader(java.io.InputStream input, ByteArrayOutputStream output) {
        Thread thread = new Thread(() -> {
            try (input; output) {
                input.transferTo(output);
            } catch (Exception ignored) {
                // 输出读取失败时按已读取内容返回，不覆盖脚本进程真实退出状态。
            }
        }, "claw-agent-skill-process-stream-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void stopProcessTree(ProcessHandle root) {
        root.descendants().forEach(ProcessHandle::destroyForcibly);
        root.destroyForcibly();
    }
}
