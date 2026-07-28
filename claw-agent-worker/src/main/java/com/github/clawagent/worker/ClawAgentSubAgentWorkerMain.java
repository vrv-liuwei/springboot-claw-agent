package com.github.clawagent.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 子 Agent 外部进程 worker 适配入口。
 * Server 通过 stdin 写入 CLAW_SUBAGENT_WORKER_V1 任务 JSON，本入口负责把任务转发给下游 Runtime 命令并回传标准结果。
 */
public final class ClawAgentSubAgentWorkerMain {
    static final String RESULT_MARKER = "CLAW_SUBAGENT_WORKER_RESULT_V1";
    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 97;
    private static final int EXIT_TIMEOUT = 124;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 1024 * 1024;

    private ClawAgentSubAgentWorkerMain() {
    }

    public static void main(String[] args) {
        try {
            SubAgentWorkerRequest request = parse(args);
            String payload = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
            SubAgentWorkerResult result = execute(request, payload);
            printResult(result);
            System.exit(result.exitCode());
        } catch (Exception e) {
            printResult(new SubAgentWorkerResult(EXIT_ERROR, "FAILED",
                    "子 Agent worker 适配器失败：" + e.getMessage(), "", "", false, false, 0));
            System.exit(EXIT_ERROR);
        }
    }

    private static SubAgentWorkerRequest parse(String[] args) {
        long timeoutMs = 300000;
        int maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        List<String> command = new ArrayList<>();
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--timeoutMs".equals(arg)) {
                timeoutMs = positiveLong(required(args, ++index, "--timeoutMs"), 300000);
            } else if ("--maxOutputBytes".equals(arg)) {
                maxOutputBytes = positiveInt(required(args, ++index, "--maxOutputBytes"), DEFAULT_MAX_OUTPUT_BYTES);
            } else if ("--".equals(arg)) {
                for (int commandIndex = index + 1; commandIndex < args.length; commandIndex++) {
                    command.add(args[commandIndex]);
                }
                break;
            } else {
                command.add(arg);
            }
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("缺少下游子 Agent Runtime 命令");
        }
        return new SubAgentWorkerRequest(timeoutMs, maxOutputBytes, command);
    }

    private static SubAgentWorkerResult execute(SubAgentWorkerRequest request, String payload) throws Exception {
        long started = System.nanoTime();
        Process process = new ProcessBuilder(request.command()).start();
        StreamCapture stdout = capture(process.getInputStream(), request.maxOutputBytes());
        StreamCapture stderr = capture(process.getErrorStream(), request.maxOutputBytes());
        // 任务 JSON 只通过 stdin 转发给下游 Runtime，避免把用户输入、metadata 暴露在命令行参数里。
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        boolean finished = process.waitFor(request.timeoutMs(), TimeUnit.MILLISECONDS);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (!finished) {
            killProcessTree(process);
            return new SubAgentWorkerResult(EXIT_TIMEOUT, "FAILED",
                    "子 Agent Runtime 执行超时：" + Duration.ofMillis(request.timeoutMs()), stdout.await(), stderr.await(),
                    stdout.truncated(), stderr.truncated(), elapsedMs);
        }
        String out = stdout.await();
        String err = stderr.await();
        if (process.exitValue() != 0) {
            return new SubAgentWorkerResult(process.exitValue(), "FAILED",
                    "子 Agent Runtime 退出码=" + process.exitValue() + " stderr=" + preview(err), out, err,
                    stdout.truncated(), stderr.truncated(), elapsedMs);
        }
        String delegated = delegatedResult(out);
        if (!delegated.isBlank()) {
            return new SubAgentWorkerResult(EXIT_OK, "DELEGATED", delegated, out, err,
                    stdout.truncated(), stderr.truncated(), elapsedMs);
        }
        return new SubAgentWorkerResult(EXIT_OK, "COMPLETED", normalizeAnswer(out, err), out, err,
                stdout.truncated(), stderr.truncated(), elapsedMs);
    }

    private static StreamCapture capture(InputStream input, int maxOutputBytes) {
        StreamCapture capture = new StreamCapture(input, maxOutputBytes <= 0 ? DEFAULT_MAX_OUTPUT_BYTES : maxOutputBytes);
        Thread thread = new Thread(capture, "sub-agent-runtime-stream");
        thread.setDaemon(true);
        capture.thread(thread);
        thread.start();
        return capture;
    }

    private static String delegatedResult(String stdout) {
        String output = stdout == null ? "" : stdout.trim();
        int marker = output.lastIndexOf(RESULT_MARKER);
        return marker < 0 ? "" : output.substring(marker);
    }

    private static void printResult(SubAgentWorkerResult result) {
        if ("DELEGATED".equals(result.status())) {
            System.out.println(result.answer());
            return;
        }
        System.out.println(RESULT_MARKER);
        System.out.println("{\"answer\":\"" + jsonEscape(result.answer()) + "\","
                + "\"status\":\"" + jsonEscape(result.status()) + "\","
                + "\"metadata\":{"
                + "\"worker.adapter\":\"claw-agent-worker\","
                + "\"worker.exitCode\":\"" + result.exitCode() + "\","
                + "\"worker.elapsedMs\":\"" + result.elapsedMs() + "\","
                + "\"worker.stdoutTruncated\":\"" + result.stdoutTruncated() + "\","
                + "\"worker.stderrTruncated\":\"" + result.stderrTruncated() + "\""
                + "}}");
    }

    private static String normalizeAnswer(String stdout, String stderr) {
        if (stdout != null && !stdout.isBlank()) {
            return stdout.trim();
        }
        if (stderr != null && !stderr.isBlank()) {
            return stderr.trim();
        }
        return "子 Agent Runtime 已完成，但没有输出内容。";
    }

    private static void killProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        // 超时场景先终止子孙进程，再终止根进程，避免下游 Runtime 留后台进程。
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

    private static String required(String[] args, int index, String option) {
        if (index >= args.length || args[index] == null || args[index].isBlank()) {
            throw new IllegalArgumentException("缺少参数值：" + option);
        }
        return args[index];
    }

    private static int positiveInt(String value, int fallback) {
        int parsed = Integer.parseInt(value.trim());
        return parsed <= 0 ? fallback : parsed;
    }

    private static long positiveLong(String value, long fallback) {
        long parsed = Long.parseLong(value.trim());
        return parsed <= 0 ? fallback : parsed;
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    private static String jsonEscape(String value) {
        String text = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record SubAgentWorkerRequest(long timeoutMs, int maxOutputBytes, List<String> command) {
    }

    private record SubAgentWorkerResult(int exitCode, String status, String answer, String stdout, String stderr,
                                        boolean stdoutTruncated, boolean stderrTruncated, long elapsedMs) {
    }

    private static class StreamCapture implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final AtomicBoolean truncated = new AtomicBoolean(false);
        private Thread thread;

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
                    // 超限后继续 drain，避免下游 Runtime 因输出管道写满而卡死。
                    int remaining = limit - buffer.size();
                    if (remaining > 0) {
                        buffer.write(chunk, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        truncated.set(true);
                    }
                }
            } catch (IOException ignored) {
                // 下游进程被终止时管道关闭是预期路径，保留已读内容即可。
            }
        }

        private String await() throws InterruptedException {
            if (thread != null) {
                thread.join(1000);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private boolean truncated() {
            return truncated.get();
        }
    }
}
