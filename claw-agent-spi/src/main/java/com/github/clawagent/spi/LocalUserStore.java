package com.github.clawagent.spi;

import java.util.List;

/**
 * 本地用户存储边界。
 * JSON、SQLite、企业身份源都实现这一接口，避免上层 Auth 服务绑定具体存储。
 */
public interface LocalUserStore {
    List<LocalUserRecord> read();

    void write(List<LocalUserRecord> records);
}
