package com.github.clawagent.server.config;

import com.github.clawagent.server.service.ApiTokenService;
import com.github.clawagent.server.service.DeviceRegistryService;
import com.github.clawagent.server.security.ApiTokenAuthInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Server 层认证配置。
 * 这里不下沉到 starter，避免 starter 反向依赖 server 的管理 DTO 和控制台服务。
 */
@Configuration
@EnableConfigurationProperties(ServerAuthProperties.class)
public class AuthServerConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ApiTokenService apiTokenService() {
        return new ApiTokenService(runtimePath(".clawagent/auth/api-tokens.json"));
    }

    @Bean
    @ConditionalOnMissingBean
    public DeviceRegistryService deviceRegistryService() {
        return new DeviceRegistryService(runtimePath(".clawagent/auth/devices.json"));
    }

    @Bean
    public WebMvcConfigurer apiTokenAuthWebMvcConfigurer(ServerAuthProperties authProperties, ApiTokenService apiTokenService) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ApiTokenAuthInterceptor(authProperties, apiTokenService))
                        .addPathPatterns(authProperties.getProtectedPathPatterns())
                        .excludePathPatterns(authProperties.getExcludedPathPatterns());
            }
        };
    }

    private Path runtimePath(String path) {
        return Path.of(System.getProperty("user.dir")).resolve(path).toAbsolutePath().normalize();
    }
}
