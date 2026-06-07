package com.github.clawagent.runtime;

import com.github.clawagent.core.AgentEvent;
import com.github.clawagent.core.AgentMessage;
import com.github.clawagent.core.AgentSession;
import com.github.clawagent.core.AgentTask;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.MemoryCandidateProcessor;
import com.github.clawagent.spi.MemoryExtractor;
import com.github.clawagent.spi.MemoryProvider;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 默认候选记忆批处理器。
 * 它把“是否值得记忆”的 LLM 判断从聊天主链路移到后台，避免每轮回复后同步等待记忆模型。
 */
public class DefaultMemoryCandidateProcessor implements MemoryCandidateProcessor, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryCandidateProcessor.class);
    /** 传给记忆提炼器的最近会话消息数量，保持和 Runtime 会话上下文窗口一致。 */
    private static final int SESSION_CONTEXT_MESSAGE_LIMIT = 20;

    /** 会话存储，用于按 sessionId 找到候选记忆所属会话。 */
    private final SessionStore sessionStore;
    /** 消息存储，用于给提炼器提供最近对话上下文。 */
    private final SessionMessageStore messageStore;
    /** 事件存储，用于记录后台候选提炼成功、失败和队列溢出。 */
    private final AgentEventStore eventStore;
    /** 记忆提炼器列表，通常包含 LLM 意图分类和候选构造逻辑。 */
    private final List<MemoryExtractor> memoryExtractors;
    /** 记忆持久化 provider，负责写入 DB、FTS、向量索引和兼容文件。 */
    private final MemoryProvider memoryProvider;
    /** 处理策略配置，控制任务后立即异步或定时/条数批处理。 */
    private final MemoryCandidateProcessingOptions options;
    /** 候选任务队列，只保存轻量 task 引用和最终回复，不缓存大段历史上下文。 */
    private final BlockingQueue<MemoryCandidateJob> queue;
    /** 任务 ID 去重表，防止同一个任务在异步触发和定时触发之间重复入队。 */
    private final ConcurrentMap<String, Boolean> queuedTaskIds = new ConcurrentHashMap<>();
    /** 后台批处理线程，真正调用记忆模型和 provider。 */
    private final ExecutorService worker;
    /** 定时兜底线程，用于每 N 秒处理积压队列。 */
    private final ScheduledExecutorService scheduler;
    /** 防止多个触发源同时 drain，保证同一批队列只被一个 worker 消费。 */
    private final AtomicBoolean draining = new AtomicBoolean(false);

    /**
     * 创建默认候选记忆批处理器。
     *
     * @param sessionStore 会话存储。
     * @param messageStore 消息存储。
     * @param eventStore 任务事件存储。
     * @param memoryExtractors 记忆提炼器列表。
     * @param memoryProvider 记忆持久化 provider。
     * @param options 异步批处理配置。
     */
    public DefaultMemoryCandidateProcessor(SessionStore sessionStore,
                                           SessionMessageStore messageStore,
                                           AgentEventStore eventStore,
                                           List<MemoryExtractor> memoryExtractors,
                                           MemoryProvider memoryProvider,
                                           MemoryCandidateProcessingOptions options) {
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.eventStore = eventStore;
        this.memoryExtractors = memoryExtractors == null ? List.of() : List.copyOf(memoryExtractors);
        this.memoryProvider = memoryProvider;
        this.options = (options == null ? MemoryCandidateProcessingOptions.defaults() : options).normalized();
        this.queue = new LinkedBlockingQueue<>();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clawagent-memory-candidate-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "clawagent-memory-candidate-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        if (this.options.enabled() && this.options.batchMode()) {
            // 批处理策略下，定时任务只消费队列增量，不主动扫描全部历史，避免重复处理和内存增长。
            scheduler.scheduleWithFixedDelay(this::submitDrain, this.options.intervalSeconds(),
                    this.options.intervalSeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void onTaskCompleted(AgentTask task, String answer) {
        if (!options.enabled() || task == null || memoryProvider == null || memoryExtractors.isEmpty()) {
            return;
        }
        if (queuedTaskIds.putIfAbsent(task.id(), Boolean.TRUE) != null) {
            return;
        }
        MemoryCandidateJob job = new MemoryCandidateJob(task, answer == null ? "" : answer);
        queue.offer(job);
        if (options.afterTaskAsyncMode() || (options.batchMode() && queue.size() >= options.batchSize())) {
            // 二选一策略：任务后异步立即触发；批处理只在累计条数达标时立即触发。
            submitDrain();
        }
    }

    /**
     * 提交后台批处理任务。
     * 多个触发源同时进入时只允许一个 worker 真正 drain，避免重复调用模型。
     */
    private void submitDrain() {
        if (!options.enabled() || queue.isEmpty() || !draining.compareAndSet(false, true)) {
            return;
        }
        worker.submit(() -> {
            try {
                drainBatch();
            } finally {
                draining.set(false);
                if (!queue.isEmpty()
                        && (options.afterTaskAsyncMode() || (options.batchMode() && queue.size() >= options.batchSize()))) {
                    submitDrain();
                }
            }
        });
    }

    /**
     * 从队列中取出一个批次并逐条提炼候选记忆。
     * 这里只保留命中的候选结果，不缓存历史消息全文。
     */
    private void drainBatch() {
        int processed = 0;
        while (processed < options.batchSize()) {
            MemoryCandidateJob job = queue.poll();
            if (job == null) {
                return;
            }
            queuedTaskIds.remove(job.task().id());
            processed++;
            processOne(job);
        }
    }

    /**
     * 处理单个任务的候选记忆提炼。
     *
     * @param job 候选记忆后台任务。
     */
    private void processOne(MemoryCandidateJob job) {
        AgentTask task = job.task();
        try {
            AgentSession session = sessionStore.findSession(task.sessionId()).orElse(null);
            List<AgentMessage> messages = messageStore.findMessages(task.sessionId(), SESSION_CONTEXT_MESSAGE_LIMIT);
            int saved = 0;
            for (MemoryExtractor extractor : memoryExtractors) {
                // task 只作为候选来源，Extractor 必须把长期 scope 落到 global/channel/session。
                List<MemoryItem> candidates = extractor.extract(task, session, messages, job.answer());
                for (MemoryItem candidate : candidates == null ? List.<MemoryItem>of() : candidates) {
                    memoryProvider.save(candidate);
                    saved++;
                }
            }
            if (saved > 0) {
                saveEvent(task, "INFO", "memory.candidate.created", "已生成候选记忆", Map.of("count", String.valueOf(saved)));
                log.info("agent memory candidates created async taskId={} count={}", task.id(), saved);
            } else {
                log.debug("agent memory candidates skipped async taskId={}", task.id());
            }
        } catch (RuntimeException e) {
            // 候选记忆提炼属于质量维护能力，失败不能影响本轮任务完成。
            saveEvent(task, "WARN", "memory.candidate.failed", "候选记忆提炼失败", Map.of("error", nullToEmpty(e.getMessage())));
            log.warn("agent memory candidate extraction failed async taskId={} error={}", task.id(), e.getMessage());
        }
    }

    /**
     * 保存后台处理事件，方便会话/任务日志定位候选记忆处理结果。
     */
    private void saveEvent(AgentTask task, String level, String type, String message, Map<String, String> details) {
        if (eventStore == null || task == null) {
            return;
        }
        eventStore.saveEvent(new AgentEvent(
                UUID.randomUUID().toString(),
                task.sessionId(),
                task.id(),
                level,
                type,
                message,
                details == null ? Map.of() : details,
                Instant.now()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /**
     * 队列中的候选记忆任务。
     *
     * @param task 已完成的 Agent 任务。
     * @param answer 本轮助手最终回复。
     */
    private record MemoryCandidateJob(AgentTask task, String answer) {
    }
}
