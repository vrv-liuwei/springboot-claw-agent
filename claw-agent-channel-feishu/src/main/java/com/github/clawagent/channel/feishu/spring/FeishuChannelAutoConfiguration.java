package com.github.clawagent.channel.feishu.spring;

import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.channel.feishu.FeishuChannelAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * 飞书 Channel 模块自动注册。
 */
@AutoConfiguration
public class FeishuChannelAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "feishuChannelAdapter")
    public ChannelRuntimeAdapter feishuChannelAdapter(@Lazy ChannelRouter channelRouter) {
        return new FeishuChannelAdapter(channelRouter);
    }
}
