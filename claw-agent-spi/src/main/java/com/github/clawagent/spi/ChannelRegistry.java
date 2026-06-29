package com.github.clawagent.spi;

import com.github.clawagent.core.ChannelDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Channel 注册表接口。
 * 实现可以来自本地 JSON、数据库或企业配置中心，server 层只依赖该抽象。
 */
public interface ChannelRegistry {
    List<ChannelDefinition> list();

    Optional<ChannelDefinition> find(String channelId);

    ChannelDefinition save(ChannelDefinition request);

    boolean delete(String channelId);
}
