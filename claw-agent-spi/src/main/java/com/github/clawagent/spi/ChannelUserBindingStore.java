package com.github.clawagent.spi;

import java.util.List;

/**
 * Channel 用户绑定存储边界。
 * 入站通道只依赖解析结果，绑定数据可以由 JSON、SQLite 或企业身份目录提供。
 */
public interface ChannelUserBindingStore {
    List<ChannelUserBindingRecord> read();

    void write(List<ChannelUserBindingRecord> records);
}
