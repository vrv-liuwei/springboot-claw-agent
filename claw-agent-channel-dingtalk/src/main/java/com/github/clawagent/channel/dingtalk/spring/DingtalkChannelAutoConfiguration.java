package com.github.clawagent.channel.dingtalk.spring;

import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.channel.dingtalk.DingtalkChannelAdapter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/**
 * 钉钉 Channel 模块自动注册。
 */
@AutoConfiguration
public class DingtalkChannelAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "dingtalkChannelAdapter")
    public ChannelRuntimeAdapter dingtalkChannelAdapter(@Lazy ChannelRouter channelRouter) {
        return new DingtalkChannelAdapter(channelRouter);
    }
}
