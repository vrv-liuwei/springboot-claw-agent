package com.github.clawagent.server.config;

import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.ChannelUserBindingService;
import com.github.clawagent.server.service.ChannelUserPolicyBindingResolver;
import com.github.clawagent.server.service.DeviceRegistryService;
import com.github.clawagent.server.service.LocalUserSessionService;
import com.github.clawagent.server.service.LocalUserService;
import com.github.clawagent.server.service.TaskPolicyEnrichmentService;
import com.github.clawagent.spi.ChannelRegistry;
import com.github.clawagent.channel.ChannelUserBindingResolver;
import com.github.clawagent.server.security.ApiTokenAuthInterceptor;
import com.github.clawagent.server.security.RateLimitInterceptor;
import com.github.clawagent.spi.ApiTokenStore;
import com.github.clawagent.spi.ChannelUserBindingStore;
import com.github.clawagent.spi.DeviceStore;
import com.github.clawagent.spi.LocalUserSessionStore;
import com.github.clawagent.spi.LocalUserStore;
import com.github.clawagent.spring.ClawAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Server 层认证配置。
 * 这里不下沉到 starter，避免 starter 反向依赖 server 的管理 DTO 和控制台服务。
 */
@Configuration
@EnableConfigurationProperties({ServerAuthProperties.class, ServerRateLimitProperties.class, ClawAgentProperties.class})
public class AuthServerConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ApiTokenService apiTokenService(ApiTokenStore apiTokenStore) {
        return new ApiTokenService(apiTokenStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeviceRegistryService deviceRegistryService(DeviceStore deviceStore) {
        return new DeviceRegistryService(deviceStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalUserService localUserService(LocalUserStore localUserStore) {
        return new LocalUserService(localUserStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public LocalUserSessionService localUserSessionService(LocalUserSessionStore localUserSessionStore,
                                                           LocalUserService localUserService) {
        return new LocalUserSessionService(localUserSessionStore, localUserService, null);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelUserBindingService channelUserBindingService(ChannelUserBindingStore channelUserBindingStore) {
        return new ChannelUserBindingService(channelUserBindingStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskPolicyEnrichmentService taskPolicyEnrichmentService(LocalUserService localUserService,
                                                                   DeviceRegistryService deviceRegistryService,
                                                                   ObjectProvider<ChannelRegistry> channelRegistry,
                                                                   ClawAgentProperties properties,
                                                                   ServerAuthProperties authProperties) {
        return new TaskPolicyEnrichmentService(localUserService, deviceRegistryService,
                channelRegistry.getIfAvailable(), properties, authProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelUserBindingResolver channelUserBindingResolver(ChannelUserBindingService channelUserBindingService,
                                                                 TaskPolicyEnrichmentService taskPolicyEnrichmentService) {
        return new ChannelUserPolicyBindingResolver(channelUserBindingService, taskPolicyEnrichmentService);
    }

    @Bean
    public WebMvcConfigurer apiTokenAuthWebMvcConfigurer(ServerAuthProperties authProperties,
                                                         ServerRateLimitProperties rateLimitProperties,
                                                         ApiTokenService apiTokenService,
                                                         LocalUserSessionService localUserSessionService,
                                                         DeviceRegistryService deviceRegistryService) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ApiTokenAuthInterceptor(authProperties, apiTokenService,
                                localUserSessionService, deviceRegistryService))
                        .addPathPatterns(authProperties.getProtectedPathPatterns())
                        .excludePathPatterns(authProperties.getExcludedPathPatterns());
                // 鉴权先执行，限流随后按 token/user/device 维度分桶；未开启鉴权时自动退回 IP 维度。
                registry.addInterceptor(new RateLimitInterceptor(rateLimitProperties))
                        .addPathPatterns(rateLimitProperties.getProtectedPathPatterns())
                        .excludePathPatterns(rateLimitProperties.getExcludedPathPatterns());
            }
        };
    }

}
