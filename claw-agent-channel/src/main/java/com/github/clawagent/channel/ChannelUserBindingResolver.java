package com.github.clawagent.channel;

import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.core.ChannelInboundMessage;

import java.util.Map;

/**
 * Channel 外部用户到本地身份的解析扩展点。
 * channel 模块只定义入口，具体绑定数据由 server 或企业 adapter 提供，避免通用路由反向依赖 server。
 */
@FunctionalInterface
public interface ChannelUserBindingResolver {
    Map<String, String> resolve(ChannelDefinition channel, ChannelInboundMessage message, Map<String, String> metadata);

    static ChannelUserBindingResolver none() {
        return (channel, message, metadata) -> metadata == null ? Map.of() : metadata;
    }
}
