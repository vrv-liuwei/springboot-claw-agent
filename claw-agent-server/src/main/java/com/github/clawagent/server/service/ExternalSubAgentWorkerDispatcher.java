package com.github.clawagent.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.spring.ClawAgentProperties;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 使用外部进程执行子 Agent Runtime。
 * 这个类只定义本地进程协议，不把具体 worker runtime 写死在 server 模块里。
 */
@Service
public class ExternalSubAgentWorkerDispatcher implements SubAgentWorkerDispatcher {
    public static final String PROTOCOL = "CLAW_SUBAGENT_WORKER_V1";
    public static final String RESULT_MARKER = "CLAW_SUBAGENT_WORKER_RESULT_V1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Integer, Semaphore> semaphores = new ConcurrentHashMap<>();

    @Override
    public boolean canDispatch(ClawAgentProperties.SubAgentWorker worker) {
        return worker != null
                && worker.isEnabled()
                && isProcessMode(worker.getMode())
                && worker.getCommand() != null
                && !worker.getCommand().isBlank();
    }

    @Override
    public SubAgentWorkerDispatchResult dispatch(AgentTask task, ClawAgentProperties.SubAgentWorker worker) {
        if (!canDispatch(worker)) {
            throw new IllegalStateException("子 Agent worker 未启用或未配置启动命令");
        }
        Semaphore semaphore = semaphores.computeIfAbsent(Math.max(worker.getMaxConcurrent(), 1), Semaphore::new);
        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(Math.max(worker.getAcquireTimeoutMs(), 1), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new IllegalStateException("等待子 Agent worker 槽位超时");
            }
            return runWorkerProcess(task, worker);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("子 Agent worker 调度被中断", e);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private SubAgentWorkerDispatchResult runWorkerProcess(AgentTask task, ClawAgentProperties.SubAgentWorker worker) {
        Process process = null;
        long startedAt = System.nanoTime();
        Map<String, String> audit = baseAuditMetadata(worker);
        try {
            List<String> command = buildCommand(worker);
            audit.put("agent.worker.command", command.isEmpty() ? "" : command.get(0));
            audit.put("agent.worker.argsCount", String.valueOf(Math.max(command.size() - 1, 0)));
            ProcessBuilder builder = new ProcessBuilder(command);
            process = builder.start();
            audit.put("agent.worker.pid", String.valueOf(process.pid()));
            StreamCapture stdout = capture(process.getInputStream(), worker.getMaxOutputBytes());
            StreamCapture stderr = capture(process.getErrorStream(), worker.getMaxOutputBytes());

            // stdin 是 server 和外部 worker 的唯一输入协议，避免通过命令行暴露任务内容。
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(objectMapper.writeValueAsBytes(payload(task)));
            }

            boolean finished = process.waitFor(Math.max(worker.getTimeoutMs(), 1), TimeUnit.MILLISECONDS);
            if (!finished) {
                killProcessTree(process);
                audit.put("agent.worker.elapsedMs", String.valueOf(elapsedMs(startedAt)));
                audit.put("agent.worker.timedOut", "true");
                audit.put("agent.worker.terminated", "true");
                throw new SubAgentWorkerDispatchException(
                        "子 Agent worker 执行超时：" + Duration.ofMillis(worker.getTimeoutMs()), audit);
            }

            String out = stdout.await();
            String err = stderr.await();
            audit.put("agent.worker.elapsedMs", String.valueOf(elapsedMs(startedAt)));
            audit.put("agent.worker.exitCode", String.valueOf(process.exitValue()));
            audit.putAll(stdout.metadata("stdout"));
            audit.putAll(stderr.metadata("stderr"));
            if (process.exitValue() != 0) {
                audit.put("agent.worker.timedOut", "false");
                audit.put("agent.worker.terminated", "false");
                throw new SubAgentWorkerDispatchException("子 Agent worker 退出码=" + process.exitValue()
                        + " stderr=" + preview(err) + " stdout=" + preview(out), audit);
            }
            SubAgentWorkerDispatchResult result = parseResult(out);
            Map<String, String> metadata = new LinkedHashMap<>(audit);
            if (result.metadata() != null) {
                metadata.putAll(result.metadata());
            }
            metadata.putIfAbsent("agent.worker.timedOut", "false");
            metadata.putIfAbsent("agent.worker.terminated", "false");
            return new SubAgentWorkerDispatchResult(result.answer(), result.status(), metadata);
        } catch (IOException e) {
            audit.put("agent.worker.elapsedMs", String.valueOf(elapsedMs(startedAt)));
            throw new SubAgentWorkerDispatchException("启动子 Agent worker 失败：" + e.getMessage(), e, audit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                killProcessTree(process);
            }
            audit.put("agent.worker.elapsedMs", String.valueOf(elapsedMs(startedAt)));
            audit.put("agent.worker.interrupted", "true");
            audit.put("agent.worker.terminated", String.valueOf(process != null));
            throw new SubAgentWorkerDispatchException("等待子 Agent worker 被中断", e, audit);
        }
    }

    private Map<String, String> baseAuditMetadata(ClawAgentProperties.SubAgentWorker worker) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("agent.worker.timeoutMs", String.valueOf(worker.getTimeoutMs()));
        metadata.put("agent.worker.maxOutputBytes", String.valueOf(worker.getMaxOutputBytes()));
        metadata.put("agent.worker.maxConcurrent", String.valueOf(worker.getMaxConcurrent()));
        metadata.put("agent.worker.acquireTimeoutMs", String.valueOf(worker.getAcquireTimeoutMs()));
        return metadata;
    }

    private long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private Map<String, Object> payload(AgentTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("protocol", PROTOCOL);
        payload.put("taskId", task.id());
        payload.put("input", task.input());
        payload.put("sessionId", task.sessionId());
        payload.put("channelId", task.channelId());
        payload.put("userId", task.userId());
        payload.put("metadata", task.metadata());
        return payload;
    }

    private List<String> buildCommand(ClawAgentProperties.SubAgentWorker worker) {
        List<String> command = new ArrayList<>();
        command.add(worker.getCommand());
        if (worker.getArgs() != null) {
            command.addAll(worker.getArgs());
        }
        return command;
    }

    private StreamCapture capture(InputStream input, int maxOutputBytes) {
        int limit = maxOutputBytes <= 0 ? 1024 * 1024 : maxOutputBytes;
        StreamCapture capture = new StreamCapture(input, limit);
        Thread thread = new Thread(capture, "sub-agent-worker-stream");
        thread.setDaemon(true);
        capture.thread(thread);
        thread.start();
        return capture;
    }

    private SubAgentWorkerDispatchResult parseResult(String stdout) throws JsonProcessingException {
        String json = resultJson(stdout);
        JsonNode root = objectMapper.readTree(json);
        String answer = text(root, "answer", "");
        TaskStatus status = parseStatus(text(root, "status", TaskStatus.COMPLETED.name()));
        Map<String, String> metadata = Map.of();
        JsonNode metadataNode = root.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            metadata = objectMapper.convertValue(metadataNode, new TypeReference<>() {});
        }
        return new SubAgentWorkerDispatchResult(answer, status, metadata);
    }

    private String resultJson(String stdout) {
        String output = stdout == null ? "" : stdout.trim();
        int marker = output.lastIndexOf(RESULT_MARKER);
        if (marker >= 0) {
            return output.substring(marker + RESULT_MARKER.length()).trim();
        }
        int objectStart = output.indexOf('{');
        if (objectStart >= 0) {
            return output.substring(objectStart).trim();
        }
        throw new IllegalStateException("子 Agent worker 未输出结果 JSON");
    }

    private TaskStatus parseStatus(String status) {
        try {
            return TaskStatus.valueOf(status == null ? TaskStatus.COMPLETED.name() : status.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return TaskStatus.COMPLETED;
        }
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private void killProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        // 先杀子进程再杀根进程，避免 shell 留下后台 worker。
        handle.descendants().forEach(child -> {
            child.destroy();
            try {
                child.onExit().get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                child.destroyForcibly();
            }
        });
        process.destroy();
        try {
            process.waitFor(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private boolean isProcessMode(String mode) {
        String value = mode == null || mode.isBlank() ? "external-process" : mode.trim().toLowerCase(Locale.ROOT);
        return "external-process".equals(value)
                || "process".equals(value)
                || "process-worker".equals(value)
                || "isolated-worker".equals(value);
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static class StreamCapture implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Thread thread;
        private long totalBytes;

        private StreamCapture(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        private void thread(Thread thread) {
            this.thread = thread;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            int read;
            try {
                while ((read = input.read(chunk)) >= 0) {
                    totalBytes += read;
                    // 超限后仍继续读取丢弃，防止 worker 因 stdout/stderr 管道写满而卡死。
                    int remaining = limit - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(read, remaining));
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道关闭是预期情况，调用方只需要已经捕获到的输出片段。
            }
        }

        private String await() throws InterruptedException {
            if (thread != null) {
                thread.join(1000);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private Map<String, String> metadata(String streamName) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("agent.worker." + streamName + "Bytes", String.valueOf(totalBytes));
            metadata.put("agent.worker." + streamName + "CapturedBytes", String.valueOf(buffer.size()));
            metadata.put("agent.worker." + streamName + "Truncated", String.valueOf(totalBytes > buffer.size()));
            return metadata;
        }
    }
}
