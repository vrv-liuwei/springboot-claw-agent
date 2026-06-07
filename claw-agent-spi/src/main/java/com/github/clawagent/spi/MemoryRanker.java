package com.github.clawagent.spi;

import com.github.clawagent.core.MemorySearchHit;

import java.util.List;

/**
 * 记忆排序器。
 * <p>
 * 本地 provider 默认用 RRF，外部 provider 可在这里统一叠加重要性、时间和 scope 匹配度。
 * </p>
 */
public interface MemoryRanker {
    /**
     * 对检索命中结果排序。
     *
     * @param hits 候选命中结果。
     * @param topK 返回条数。
     * @return 排序后的结果。
     */
    List<MemorySearchHit> rank(List<MemorySearchHit> hits, int topK);
}
