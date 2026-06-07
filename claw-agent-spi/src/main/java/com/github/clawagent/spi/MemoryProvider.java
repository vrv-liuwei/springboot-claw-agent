package com.github.clawagent.spi;

import com.github.clawagent.core.MemoryHitLog;
import com.github.clawagent.core.MemoryItem;
import com.github.clawagent.core.MemorySearchHit;
import com.github.clawagent.core.MemorySearchRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆存储与检索 Provider。
 * <p>
 * Runtime、Controller 和管理台只依赖该接口，后续可替换为 Mem0、Zep 或企业级记忆服务。
 * </p>
 */
public interface MemoryProvider {
    /**
     * @return provider ID，例如 local、mem0、zep。
     */
    String id();

    /**
     * @return provider 能力声明，供管理台展示和后续 provider 切换判断。
     */
    Map<String, Object> capabilities();

    /**
     * 保存或更新记忆条目。
     *
     * @param item 记忆条目。
     * @return 保存后的记忆条目。
     */
    MemoryItem save(MemoryItem item);

    /**
     * 查询记忆详情。
     *
     * @param userId 用户 ID。
     * @param itemId 记忆 ID。
     * @return 当前用户可见的记忆条目。
     */
    Optional<MemoryItem> find(String userId, String itemId);

    /**
     * 查询记忆列表。
     *
     * @param userId 用户 ID。
     * @param scopeType 记忆范围，可为空。
     * @param status 记忆状态，可为空。
     * @param limit 最大返回条数。
     * @return 记忆列表。
     */
    List<MemoryItem> list(String userId, String scopeType, String status, int limit);

    /**
     * 检索记忆。
     *
     * @param request 检索请求。
     * @return 命中的记忆片段。
     */
    List<MemorySearchHit> search(MemorySearchRequest request);

    /**
     * 更新记忆状态。
     *
     * @param userId 用户 ID。
     * @param itemId 记忆 ID。
     * @param status 新状态。
     * @return 更新后的记忆条目。
     */
    MemoryItem updateStatus(String userId, String itemId, String status);

    /**
     * 删除记忆条目和对应索引。
     *
     * @param userId 用户 ID。
     * @param itemId 记忆 ID。
     */
    void delete(String userId, String itemId);

    /**
     * 记录一次模型上下文命中的记忆。
     *
     * @param log 命中审计记录。
     */
    void recordHit(MemoryHitLog log);

    /**
     * 查询命中记录。
     *
     * @param userId 用户 ID。
     * @param sessionId 会话 ID，可为空。
     * @param taskId 任务 ID，可为空。
     * @param limit 最大返回条数。
     * @return 命中审计记录。
     */
    List<MemoryHitLog> hits(String userId, String sessionId, String taskId, int limit);
}
