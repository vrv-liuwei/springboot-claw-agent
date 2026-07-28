package com.github.clawagent.spi;

import java.util.List;

/**
 * API Token 存储边界。
 * 鉴权服务通过该接口读写 token，便于 JSON、SQLite 或外部密钥服务替换。
 */
public interface ApiTokenStore {
    List<ApiTokenRecord> read();

    void write(List<ApiTokenRecord> records);
}
