package com.github.clawagent.spring;

import com.github.clawagent.core.ChannelDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClawAgentAutoConfigurationChannelConfigTest {

    @Test
    void yamlChannelConfigsBindAndExpandToChannelDefinitions() throws Exception {
        ClawAgentProperties properties = bindYaml("""
                clawagent:
                  channels:
                    configs:
                      feishu:
                        defaultAccount: main
                        dmPolicy: open
                        accounts:
                          main:
                            name: 主助手 - 飞书机器人
                            enabled: true
                            approvalMode: ask
                            appId: cli_yaml
                            appSecret: yaml-secret
                            appSecretEnv: FEISHU_MAIN_APP_SECRET
                      ddio:
                        accounts:
                          main:
                            name: DDIO Bot
                            enabled: true
                            appId: "4611686027040362507"
                            baseUrl: "https://192.168.0.180:10443"
                """);

        List<ChannelDefinition> channels = new ClawAgentAutoConfiguration().configuredChannels(properties);
        ChannelDefinition feishu = find(channels, "feishu");
        ChannelDefinition ddio = find(channels, "ddio-main");

        assertEquals("feishu", feishu.type());
        assertTrue(feishu.enabled());
        assertEquals("main", feishu.metadata().get("channel.accountId"));
        assertEquals("true", feishu.metadata().get("channel.isDefaultAccount"));
        assertEquals("cli_yaml", feishu.metadata().get("appId"));
        assertEquals("yaml-secret", feishu.metadata().get("appSecret"));
        assertEquals("FEISHU_MAIN_APP_SECRET", feishu.metadata().get("appSecretEnv"));
        assertEquals("open", feishu.metadata().get("dmPolicy"));
        assertEquals("ddio", ddio.type());
        assertEquals("https://192.168.0.180:10443", ddio.metadata().get("baseUrl"));
    }

    private ClawAgentProperties bindYaml(String yaml) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load(
                "clawagent-channel-test",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)))) {
            sources.addFirst(source);
        }
        return Binder.get(environment)
                .bind("clawagent", Bindable.of(ClawAgentProperties.class))
                .orElseThrow(() -> new IllegalStateException("clawagent YAML 配置绑定失败"));
    }

    private ChannelDefinition find(List<ChannelDefinition> channels, String channelId) {
        return channels.stream()
                .filter(channel -> channel.id().equals(channelId))
                .findFirst()
                .orElseThrow();
    }
}
