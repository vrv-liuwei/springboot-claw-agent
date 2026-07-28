package com.github.clawagent.server.service;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.spring.ClawAgentProperties;
import com.github.clawagent.worker.ClawAgentSubAgentWorkerMain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSubAgentWorkerDispatcherTest {

    @Test
    void dispatchRunsExternalProcessAndParsesMarkedJsonResult() {
        ExternalSubAgentWorkerDispatcher dispatcher = new ExternalSubAgentWorkerDispatcher();
        ClawAgentProperties.SubAgentWorker worker = worker(FakeWorkerMain.class.getName());
        AgentTask task = new AgentTask("task-1",
                new AgentRequest("hello", "session-1", "webui", "console", Map.of("scope", "unit")));
        task.markStatus(TaskStatus.RUNNING);

        SubAgentWorkerDispatchResult result = dispatcher.dispatch(task, worker);

        assertEquals("worker ok", result.answer());
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("yes", result.metadata().get("worker.echo"));
        assertEquals("0", result.metadata().get("agent.worker.exitCode"));
        assertEquals("false", result.metadata().get("agent.worker.timedOut"));
        assertEquals("false", result.metadata().get("agent.worker.terminated"));
        assertEquals("8192", result.metadata().get("agent.worker.maxOutputBytes"));
        assertTrue(result.metadata().containsKey("agent.worker.pid"));
        assertTrue(result.metadata().containsKey("agent.worker.elapsedMs"));
        assertTrue(result.metadata().containsKey("agent.worker.stdoutBytes"));
        assertTrue(result.metadata().containsKey("agent.worker.stderrBytes"));
    }

    @Test
    void dispatchCanRunPackagedSubAgentWorkerAdapter() {
        ExternalSubAgentWorkerDispatcher dispatcher = new ExternalSubAgentWorkerDispatcher();
        ClawAgentProperties.SubAgentWorker worker = packagedAdapterWorker(PlainRuntimeMain.class.getName());
        AgentTask task = new AgentTask("task-2",
                new AgentRequest("hello", "session-1", "webui", "console", Map.of("scope", "adapter")));
        task.markStatus(TaskStatus.RUNNING);

        SubAgentWorkerDispatchResult result = dispatcher.dispatch(task, worker);

        assertEquals("adapter child ok", result.answer());
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals("claw-agent-worker", result.metadata().get("worker.adapter"));
    }

    @Test
    void failedWorkerStillReturnsAuditMetadata() {
        ExternalSubAgentWorkerDispatcher dispatcher = new ExternalSubAgentWorkerDispatcher();
        ClawAgentProperties.SubAgentWorker worker = worker(FailingWorkerMain.class.getName());
        AgentTask task = new AgentTask("task-3",
                new AgentRequest("hello", "session-1", "webui", "console", Map.of("scope", "failure")));
        task.markStatus(TaskStatus.RUNNING);

        SubAgentWorkerDispatchException error = assertThrows(SubAgentWorkerDispatchException.class,
                () -> dispatcher.dispatch(task, worker));

        assertTrue(error.getMessage().contains("退出码=7"));
        assertEquals("7", error.metadata().get("agent.worker.exitCode"));
        assertEquals("false", error.metadata().get("agent.worker.timedOut"));
        assertEquals("false", error.metadata().get("agent.worker.terminated"));
        assertTrue(error.metadata().containsKey("agent.worker.pid"));
        assertTrue(error.metadata().containsKey("agent.worker.elapsedMs"));
        assertTrue(Long.parseLong(error.metadata().get("agent.worker.stderrBytes")) > 0);
    }

    private ClawAgentProperties.SubAgentWorker worker(String mainClass) {
        ClawAgentProperties.SubAgentWorker worker = new ClawAgentProperties.SubAgentWorker();
        worker.setEnabled(true);
        worker.setMode("external-process");
        worker.setCommand(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        worker.setArgs(List.of("-cp", System.getProperty("java.class.path"), mainClass));
        worker.setTimeoutMs(5000);
        worker.setMaxOutputBytes(8192);
        return worker;
    }

    private ClawAgentProperties.SubAgentWorker packagedAdapterWorker(String childMainClass) {
        ClawAgentProperties.SubAgentWorker worker = new ClawAgentProperties.SubAgentWorker();
        worker.setEnabled(true);
        worker.setMode("external-process");
        worker.setCommand(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        worker.setArgs(List.of(
                "-cp", System.getProperty("java.class.path"),
                ClawAgentSubAgentWorkerMain.class.getName(),
                "--timeoutMs", "5000",
                "--maxOutputBytes", "8192",
                "--",
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                childMainClass));
        worker.setTimeoutMs(8000);
        worker.setMaxOutputBytes(8192);
        return worker;
    }

    public static class FakeWorkerMain {
        public static void main(String[] args) throws Exception {
            // 消费完整 stdin，模拟真实 worker 收到 server 下发的任务协议。
            System.in.readAllBytes();
            System.out.println("worker log");
            System.out.println(ExternalSubAgentWorkerDispatcher.RESULT_MARKER);
            System.out.println("{\"answer\":\"worker ok\",\"status\":\"COMPLETED\",\"metadata\":{\"worker.echo\":\"yes\"}}");
        }
    }

    public static class PlainRuntimeMain {
        public static void main(String[] args) throws Exception {
            // 真实子 Agent Runtime 可以消费 server 传入的任务协议；这里用最小进程证明 adapter 串联可用。
            System.in.readAllBytes();
            System.out.print("adapter child ok");
        }
    }

    public static class FailingWorkerMain {
        public static void main(String[] args) throws Exception {
            // 失败分支仍要消费 stdin，保证测试覆盖真实 worker 协议输入后再退出。
            System.in.readAllBytes();
            System.err.print("worker failed");
            System.exit(7);
        }
    }
}
