package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentRequest;
import com.github.clawagent.core.AgentResult;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.TaskStatus;
import com.github.clawagent.core.ToolCall;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.AgentToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentRuntimeCancellationTest {

    @Test
    void cancelTaskInterruptsRunningPlannerThread() throws Exception {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemorySessionMessageStore messageStore = new InMemorySessionMessageStore();
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();
        BlockingPlanner planner = new BlockingPlanner();
        DefaultAgentRuntime runtime = new DefaultAgentRuntime(
                planner,
                (task, steps) -> "should-not-complete",
                new AgentToolRegistry(List.of()),
                taskStore,
                sessionStore,
                messageStore,
                (session, messages) -> "",
                List.of(),
                eventStore,
                List.of(),
                List.of());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AgentResult> future = executor.submit(() -> runtime.submit(AgentRequest.userMessage("阻塞任务")));

            assertTrue(planner.started.await(2, TimeUnit.SECONDS));
            AgentTask runningTask = taskStore.searchTasks("", "RUNNING", "", "", "", 10).get(0);

            runtime.cancelTask(runningTask.id());

            assertTrue(planner.interrupted.await(2, TimeUnit.SECONDS));
            AgentResult result = future.get(2, TimeUnit.SECONDS);
            assertEquals(TaskStatus.CANCELLED, result.status());
            assertEquals(TaskStatus.CANCELLED, runtime.getTask(runningTask.id()).status());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class BlockingPlanner implements AgentPlanner {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);

        @Override
        public List<ToolCall> plan(AgentTask task) {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                // 取消必须能打断阻塞中的模型/规划线程；恢复中断标记交给 Runtime 统一落 CANCELLED 状态。
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }
}
