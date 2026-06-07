package com.github.clawagent.runtime;

/**
 * 候选记忆异步处理配置。
 *
 * @param enabled 是否启用候选记忆提炼。
 * @param mode 处理策略：after-task-async 表示每轮任务后立即异步处理；batch 表示按定时或累计条数批处理。
 * @param intervalSeconds 定时批处理间隔秒数。
 * @param batchSize 单次最多处理的候选任务数量。
 */
public record MemoryCandidateProcessingOptions(
        boolean enabled,
        String mode,
        long intervalSeconds,
        int batchSize
) {
    /** 任务完成后立即异步处理。 */
    public static final String MODE_AFTER_TASK_ASYNC = "after-task-async";
    /** 定时或累计条数触发批处理。 */
    public static final String MODE_BATCH = "batch";

    /**
     * 构建默认配置：任务结束后入队并异步处理。
     *
     * @return 默认候选记忆处理配置。
     */
    public static MemoryCandidateProcessingOptions defaults() {
        return new MemoryCandidateProcessingOptions(true, MODE_AFTER_TASK_ASYNC, 60, 100);
    }

    /**
     * 判断是否为任务后异步处理策略。
     *
     * @return true 表示每轮任务结束后立即后台处理。
     */
    public boolean afterTaskAsyncMode() {
        return MODE_AFTER_TASK_ASYNC.equalsIgnoreCase(mode);
    }

    /**
     * 判断是否为定时/条数批处理策略。
     *
     * @return true 表示只在定时到达或队列达到批次大小时处理。
     */
    public boolean batchMode() {
        return MODE_BATCH.equalsIgnoreCase(mode);
    }

    /**
     * 对外部配置做边界修正，防止 0 或负数导致后台线程空转。
     *
     * @return 修正后的安全配置。
     */
    public MemoryCandidateProcessingOptions normalized() {
        String normalizedMode = MODE_BATCH.equalsIgnoreCase(mode) ? MODE_BATCH : MODE_AFTER_TASK_ASYNC;
        return new MemoryCandidateProcessingOptions(
                enabled,
                normalizedMode,
                Math.max(1, intervalSeconds),
                Math.max(1, batchSize));
    }
}
