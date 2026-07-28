package com.github.clawagent.spi;

import java.util.List;

/**
 * 本地登录会话存储边界。
 * 后续迁移 SQLite 时只替换实现，保持登录、鉴权和审计流程稳定。
 */
public interface LocalUserSessionStore {
    List<LocalUserSessionRecord> read();

    void write(List<LocalUserSessionRecord> records);
}
