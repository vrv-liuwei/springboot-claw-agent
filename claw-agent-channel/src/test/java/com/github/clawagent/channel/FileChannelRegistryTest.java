package com.github.clawagent.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.clawagent.core.ChannelDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileChannelRegistryTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void listIncludesBuiltinChannelsForWebApiAndImTemplates() {
        FileChannelRegistry registry = new FileChannelRegistry(tempDir.resolve("channels.json"));

        assertTrue(registry.find("webui").isPresent());
        assertTrue(registry.find("api").isPresent());
        assertEquals("builtin", registry.find("feishu").orElseThrow().metadata().get("adapter"));
        assertEquals("official-http", registry.find("feishu").orElseThrow().metadata().get("adapterLevel"));
        assertEquals("FEISHU_VERIFICATION_TOKEN", registry.find("feishu").orElseThrow().metadata().get("verificationTokenEnv"));
        assertEquals("builtin", registry.find("dingtalk").orElseThrow().metadata().get("adapter"));
        assertEquals("official-http", registry.find("dingtalk").orElseThrow().metadata().get("adapterLevel"));
        assertEquals("DINGTALK_WEBHOOK_URL", registry.find("dingtalk").orElseThrow().metadata().get("webhookUrlEnv"));
    }

    @Test
    void saveNormalizesAndPersistsCustomChannel() {
        Path storePath = tempDir.resolve("config").resolve("channels.json");
        FileChannelRegistry registry = new FileChannelRegistry(storePath);

        ChannelDefinition saved = registry.save(new ChannelDefinition(
                "  WORKCHAT  ",
                "  工作群  ",
                "",
                true,
                "invalid-mode",
                List.of(" builtin.execute.command ", "", "builtin.execute.command"),
                "",
                Map.of("provider", "custom"),
                null,
                null));

        assertEquals("workchat", saved.id());
        assertEquals("工作群", saved.name());
        assertEquals("workchat", saved.type());
        assertEquals("ask", saved.approvalMode());
        assertEquals(List.of("builtin.execute.command"), saved.approvedToolIds());
        assertEquals("/api/v1/channels/workchat/inbound", saved.inboundPath());

        FileChannelRegistry reloaded = new FileChannelRegistry(storePath);
        assertEquals("custom", reloaded.find("workchat").orElseThrow().metadata().get("provider"));
    }

    @Test
    void listGivesApplicationYamlConfiguredChannelsHighestPriority() throws Exception {
        Path storePath = tempDir.resolve("channels.json");
        Files.writeString(storePath, """
                [
                  {
                    "id": "workchat",
                    "name": "管理台覆盖 IM",
                    "type": "workchat",
                    "enabled": false,
                    "approvalMode": "custom",
                    "approvedToolIds": [],
                    "inboundPath": "/saved/workchat",
                    "metadata": {"source": "channels-json"}
                  }
                ]
                """, StandardCharsets.UTF_8);
        FileChannelRegistry registry = new FileChannelRegistry(storePath, ChannelAdapterRegistry.builtin(null), List.of(
                new ChannelDefinition("api", "配置覆盖 API", "api", true,
                        "auto", List.of("builtin.execute.command"), "/configured/api", Map.of("source", "application-yml"), null, null),
                new ChannelDefinition("workchat", "企业 IM", "workchat", true,
                        "ask", List.of(), "/workchat/inbound", Map.of("source", "application-yml"), null, null)
        ));

        ChannelDefinition api = registry.find("api").orElseThrow();
        ChannelDefinition workchat = registry.find("workchat").orElseThrow();

        assertEquals("配置覆盖 API", api.name());
        assertEquals("auto", api.approvalMode());
        assertEquals("application-yml", api.metadata().get("source"));
        assertEquals("yaml", api.metadata().get("channel.source"));
        assertEquals("true", api.metadata().get("channel.readOnly"));
        assertEquals("企业 IM", workchat.name());
        assertEquals("application-yml", workchat.metadata().get("source"));
    }

    @Test
    void rejectsSavingYamlManagedChannelToAvoidSilentOverride() {
        FileChannelRegistry registry = new FileChannelRegistry(tempDir.resolve("channels.json"), ChannelAdapterRegistry.builtin(null), List.of(
                new ChannelDefinition("feishu-main", "主助手", "feishu", true,
                        "ask", List.of(), "/api/v1/channels/feishu-main/inbound", Map.of(), null, null)
        ));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                registry.save(new ChannelDefinition("feishu-main", "页面修改", "feishu", false,
                        "ask", List.of(), "/api/v1/channels/feishu-main/inbound", Map.of(), null, null)));

        assertTrue(error.getMessage().contains("YAML"));
        assertEquals("主助手", registry.find("feishu-main").orElseThrow().name());
    }

    @Test
    void deleteOnlyRemovesSavedChannels() {
        FileChannelRegistry registry = new FileChannelRegistry(tempDir.resolve("channels.json"));
        registry.save(new ChannelDefinition("workchat", "工作群", "custom", true,
                "ask", List.of(), "/inbound", Map.of(), null, null));

        assertTrue(registry.delete("workchat"));
        assertFalse(registry.find("workchat").isPresent());
        assertFalse(registry.delete("webui"));
        assertTrue(registry.find("webui").isPresent());
    }

    @Test
    void readsOpenClawStyleObjectAccounts() throws Exception {
        Path storePath = tempDir.resolve("channels.json");
        Files.writeString(storePath, """
                {
                  "channels": {
                    "feishu": {
                      "defaultAccount": "main",
                      "dmPolicy": "open",
                      "accounts": {
                        "main": {
                          "name": "主助手 - 飞书机器人",
                          "appId": "cli_main",
                          "appSecretEnv": "FEISHU_MAIN_SECRET",
                          "enabled": true
                        },
                        "coder": {
                          "name": "代码助手 - 飞书机器人",
                          "appId": "cli_coder",
                          "enabled": false
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        FileChannelRegistry registry = new FileChannelRegistry(storePath);
        ChannelDefinition main = registry.find("feishu").orElseThrow();
        ChannelDefinition coder = registry.find("feishu-coder").orElseThrow();

        assertEquals("feishu", main.type());
        assertTrue(main.enabled());
        assertEquals("主助手 - 飞书机器人", main.name());
        assertEquals("cli_main", main.metadata().get("appId"));
        assertEquals("FEISHU_MAIN_SECRET", main.metadata().get("appSecretEnv"));
        assertEquals("open", main.metadata().get("dmPolicy"));
        assertEquals("true", main.metadata().get("channel.isDefaultAccount"));
        assertFalse(coder.enabled());
        assertEquals("false", coder.metadata().get("channel.isDefaultAccount"));
    }

    @Test
    void parsesApplicationYamlStyleAccountConfigMap() {
        List<ChannelDefinition> channels = FileChannelRegistry.fromAccountStyleConfig(Map.of(
                "feishu", Map.of(
                        "defaultAccount", "main",
                        "dmPolicy", "open",
                        "accounts", Map.of(
                                "main", Map.of(
                                        "name", "主助手",
                                        "appId", "cli_main",
                                        "appSecret", "yaml-secret",
                                        "appSecretEnv", "FEISHU_MAIN_APP_SECRET",
                                        "enabled", true),
                                "coder", Map.of(
                                        "name", "代码助手",
                                        "appId", "cli_coder",
                                        "enabled", false)
                        )
                )
        ));

        ChannelDefinition main = channels.stream().filter(channel -> channel.id().equals("feishu")).findFirst().orElseThrow();
        ChannelDefinition coder = channels.stream().filter(channel -> channel.id().equals("feishu-coder")).findFirst().orElseThrow();

        assertTrue(main.enabled());
        assertFalse(coder.enabled());
        assertEquals("yaml-secret", main.metadata().get("appSecret"));
        assertEquals("FEISHU_MAIN_APP_SECRET", main.metadata().get("appSecretEnv"));
        assertEquals("open", main.metadata().get("dmPolicy"));
        assertEquals("true", main.metadata().get("channel.isDefaultAccount"));
    }

    @Test
    void readsOpenClawStyleArrayAccounts() throws Exception {
        Path storePath = tempDir.resolve("channels.json");
        Files.writeString(storePath, """
                {
                  "channels": {
                    "ddio": {
                      "accounts": [
                        {
                          "accountId": "show",
                          "name": "DDIO Bot",
                          "enabled": false,
                          "appId": "app-show",
                          "allowFrom": ["*"]
                        },
                        {
                          "accountId": "prod",
                          "name": "DDIO Prod",
                          "enabled": true,
                          "appId": "app-prod",
                          "baseUrl": "http://127.0.0.1:19790"
                        }
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        FileChannelRegistry registry = new FileChannelRegistry(storePath);
        ChannelDefinition show = registry.find("ddio-show").orElseThrow();
        ChannelDefinition prod = registry.find("ddio-prod").orElseThrow();

        assertEquals("ddio", show.type());
        assertFalse(show.enabled());
        assertEquals("app-show", show.metadata().get("appId"));
        assertEquals("[\"*\"]", show.metadata().get("allowFrom"));
        assertTrue(prod.enabled());
        assertEquals("http://127.0.0.1:19790", prod.metadata().get("baseUrl"));
    }

    @Test
    void savePreservesOpenClawGroupedAccounts() throws Exception {
        Path storePath = tempDir.resolve("channels.json");
        Files.writeString(storePath, """
                {
                  "channels": {
                    "feishu": {
                      "defaultAccount": "main",
                      "accounts": {
                        "main": {
                          "name": "主助手",
                          "enabled": true,
                          "appId": "cli_main"
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        FileChannelRegistry registry = new FileChannelRegistry(storePath);
        registry.save(new ChannelDefinition(
                "feishu-coder",
                "代码助手",
                "feishu",
                true,
                "auto",
                List.of("builtin.time"),
                "/api/v1/channels/feishu-coder/inbound",
                Map.of(
                        "channel.configStyle", "accounts",
                        "channel.accountId", "coder",
                        "channel.defaultAccount", "main",
                        "appId", "cli_coder",
                        "allowFrom", "[\"*\"]"),
                null,
                null));

        JsonNode root = objectMapper.readTree(Files.readString(storePath, StandardCharsets.UTF_8));
        assertTrue(root.path("channels").path("feishu").path("accounts").isObject());
        assertEquals("main", root.path("channels").path("feishu").path("defaultAccount").asText());
        assertEquals("cli_coder", root.path("channels").path("feishu").path("accounts").path("coder").path("appId").asText());
        assertTrue(root.path("channels").path("feishu").path("accounts").path("coder").path("allowFrom").isArray());
        assertTrue(new FileChannelRegistry(storePath).find("feishu-coder").isPresent());
    }

    @Test
    void deletePreservesOpenClawGroupedAccounts() throws Exception {
        Path storePath = tempDir.resolve("channels.json");
        Files.writeString(storePath, """
                {
                  "channels": {
                    "feishu": {
                      "defaultAccount": "main",
                      "accounts": {
                        "main": {
                          "name": "主助手",
                          "enabled": true,
                          "appId": "cli_main"
                        },
                        "coder": {
                          "name": "代码助手",
                          "enabled": true,
                          "appId": "cli_coder"
                        }
                      }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        FileChannelRegistry registry = new FileChannelRegistry(storePath);
        assertTrue(registry.delete("feishu-coder"));

        JsonNode root = objectMapper.readTree(Files.readString(storePath, StandardCharsets.UTF_8));
        assertTrue(root.path("channels").path("feishu").path("accounts").isObject());
        assertTrue(root.path("channels").path("feishu").path("accounts").has("main"));
        assertFalse(root.path("channels").path("feishu").path("accounts").has("coder"));
        assertFalse(new FileChannelRegistry(storePath).find("feishu-coder").isPresent());
    }
}
