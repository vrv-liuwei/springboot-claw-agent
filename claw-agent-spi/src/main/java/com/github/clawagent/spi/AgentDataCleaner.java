package com.github.clawagent.spi;

/**
 * AgentDataCleaner 用于开发控制台清空本地会话数据。
 * 不放到单个 Store 接口里，避免 SQLite 这种多接口合一实现被重复清理。
 */
public interface AgentDataCleaner {
    void clearAllAgentData();
}
