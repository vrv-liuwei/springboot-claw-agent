package com.github.clawagent.spring;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.clawagent.mcp.FileMcpRegistry;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.memory.markdown.MarkdownMemoryPromoter;
import com.github.clawagent.memory.markdown.MarkdownMemoryRepository;
import com.github.clawagent.model.LlmAgentPlanner;
import com.github.clawagent.model.LlmSessionSummarizer;
import com.github.clawagent.model.OpenAiCompatibleEmbeddingClient;
import com.github.clawagent.model.OpenAiCompatibleModelClient;
import com.github.clawagent.model.ReActAgentPlanner;
import com.github.clawagent.model.SpringAiChatClientModelClient;
import com.github.clawagent.model.StreamingLlmResponseGenerator;
import com.github.clawagent.model.ToolCallingAgentPlanner;
import com.github.clawagent.persistence.sqlite.SqliteTaskStore;
import com.github.clawagent.runtime.AgentRuntime;
import com.github.clawagent.runtime.DefaultAgentRuntime;
import com.github.clawagent.runtime.InMemoryAgentEventStore;
import com.github.clawagent.runtime.InMemorySessionMessageStore;
import com.github.clawagent.runtime.InMemorySessionStore;
import com.github.clawagent.runtime.InMemoryTaskStore;
import com.github.clawagent.runtime.InMemoryTodoStore;
import com.github.clawagent.runtime.RuleBasedPlanner;
import com.github.clawagent.runtime.SimpleSessionSummarizer;
import com.github.clawagent.runtime.ToolOutputResponseGenerator;
import com.github.clawagent.security.DefaultToolExecutionGuard;
import com.github.clawagent.skill.ExternalSkillInstallTool;
import com.github.clawagent.skill.FileSkillRegistry;
import com.github.clawagent.skill.SkillRegistry;
import com.github.clawagent.spi.AgentCallback;
import com.github.clawagent.spi.AgentEventStore;
import com.github.clawagent.spi.AgentPlanner;
import com.github.clawagent.spi.AgentResponseGenerator;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.MemoryPromoter;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.SessionSummarizer;
import com.github.clawagent.spi.TaskStore;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spi.ToolExecutionGuard;
import com.github.clawagent.toolkit.ToolkitProperties;
import com.github.clawagent.toolkit.ToolkitRegistry;
import com.github.clawagent.toolkit.ToolkitToolProperties;

/**
 * Spring Boot 自动配置入口。
 * 业务应用引入 starter 后，如果没有自定义 Bean，就会得到一套可运行的本地 ClawAgent Runtime。
 */
@Configuration
@EnableConfigurationProperties(ClawAgentProperties.class)
@ConditionalOnProperty(prefix = "clawagent", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ClawAgentAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ClawAgentAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ChatOptions chatOptions(ClawAgentProperties properties) {
        ClawAgentProperties.ModelConfig config = defaultModelConfig(properties);
        return new ChatOptions(config.getModel(), config.getTemperature(), config.getTimeoutSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelClient modelClient(ClawAgentProperties properties, ApplicationContext applicationContext) {
        ClawAgentProperties.ModelConfig config = defaultModelConfig(properties);
        if ("spring-ai".equalsIgnoreCase(properties.getModel().getClient())) {
            Object builder = findSpringAiChatClientBuilder(applicationContext);
            // Spring AI 模式下由业务应用自己的 Spring AI starter/model 配置决定底层模型供应商。
            return new SpringAiChatClientModelClient(builder);
        }
        return new OpenAiCompatibleModelClient(config.getBaseUrl(), resolveApiKey(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingOptions embeddingOptions(ClawAgentProperties properties) {
        ClawAgentProperties.Embedding config = properties.getMemory().getVector().getEmbedding();
        return new EmbeddingOptions(config.getModel(), config.getDimensions(), config.getTimeoutSeconds());
    }

    @Bean
    @ConditionalOnMissingBean
    public EmbeddingClient embeddingClient(ClawAgentProperties properties) {
        ClawAgentProperties.Embedding config = properties.getMemory().getVector().getEmbedding();
        return new OpenAiCompatibleEmbeddingClient(config.getBaseUrl(), resolveApiKey(config.getApiKey(), config.getApiKeyEnv()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentPlanner agentPlanner(
            ClawAgentProperties properties,
            ModelClient modelClient,
            ChatOptions chatOptions,
            AgentToolRegistry toolRegistry,
            ToolkitRegistry toolkitRegistry) {
        if ("rule".equalsIgnoreCase(properties.getModel().getMode())) {
            return new RuleBasedPlanner();
        }
        if ("react".equalsIgnoreCase(properties.getModel().getPlanner())) {
            return new ReActAgentPlanner(modelClient, chatOptions, toolRegistry);
        }
        if ("tool-calling".equalsIgnoreCase(properties.getModel().getPlanner())
                || "toolcalling".equalsIgnoreCase(properties.getModel().getPlanner())) {
            return new ToolCallingAgentPlanner(modelClient, chatOptions, toolRegistry);
        }
        // toolkitRegistry 参数用于保证系统工具先注册到 AgentToolRegistry，再构建 LLM Planner。
        return new LlmAgentPlanner(modelClient, chatOptions, toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentResponseGenerator agentResponseGenerator(ClawAgentProperties properties, ModelClient modelClient, ChatOptions chatOptions) {
        if ("rule".equalsIgnoreCase(properties.getModel().getMode())) {
            return new ToolOutputResponseGenerator();
        }
        return new StreamingLlmResponseGenerator(modelClient, chatOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionSummarizer sessionSummarizer(ClawAgentProperties properties, ModelClient modelClient, ChatOptions chatOptions) {
        if ("rule".equalsIgnoreCase(properties.getModel().getMode())) {
            return new SimpleSessionSummarizer();
        }
        return new LlmSessionSummarizer(modelClient, chatOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public TaskStore taskStore(ClawAgentProperties properties) {
        if ("sqlite".equalsIgnoreCase(properties.getPersistence().getType())) {
            return new SqliteTaskStore(Path.of(properties.getPersistence().getSqlite().getPath()));
        }
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore(TaskStore taskStore) {
        if (taskStore instanceof SessionStore sessionStore) {
            return sessionStore;
        }
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionMessageStore sessionMessageStore(TaskStore taskStore) {
        if (taskStore instanceof SessionMessageStore messageStore) {
            return messageStore;
        }
        return new InMemorySessionMessageStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEventStore agentEventStore(TaskStore taskStore) {
        if (taskStore instanceof AgentEventStore eventStore) {
            return eventStore;
        }
        return new InMemoryAgentEventStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TodoStore todoStore(TaskStore taskStore) {
        if (taskStore instanceof TodoStore todoStore) {
            return todoStore;
        }
        return new InMemoryTodoStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownMemoryRepository markdownMemoryRepository(ClawAgentProperties properties) {
        MarkdownMemoryRepository repository = new MarkdownMemoryRepository(Path.of(properties.getMemory().getMarkdown().getPath()));
        repository.initialize();
        return repository;
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryPromoter markdownMemoryPromoter(MarkdownMemoryRepository repository, ClawAgentProperties properties) {
        if (!properties.getMemory().getMarkdown().isEnabled()
                || !properties.getMemory().getMarkdown().isAutoPromoteSessionSummary()) {
            return (session, messages) -> log.debug("markdown memory promote skipped sessionId={}", session.id());
        }
        return new MarkdownMemoryPromoter(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentToolRegistry agentToolRegistry(List<AgentTool> tools) {
        return new AgentToolRegistry(tools);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolkitRegistry toolkitRegistry(AgentToolRegistry toolRegistry, ClawAgentProperties properties, TodoStore todoStore) {
        ToolkitRegistry registry = new ToolkitRegistry(toolRegistry, toolkitProperties(properties), todoStore);
        // starter 只初始化 toolkit 注册器，不再逐个实例化具体工具。
        registry.load();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public McpRegistry mcpRegistry(AgentToolRegistry toolRegistry, ClawAgentProperties properties) {
        return new FileMcpRegistry(toolRegistry, properties.getMcp().getPath().stream().map(Path::of).toList());
    }

    @Bean
    @ConditionalOnMissingBean(name = "clawAgentMcpAutoConnectRunner")
    public ApplicationRunner clawAgentMcpAutoConnectRunner(ClawAgentProperties properties, McpRegistry mcpRegistry) {
        return args -> {
            if (!properties.getMcp().isEnabled()) {
                log.info("mcp auto connect skipped because clawagent.mcp.enabled=false");
                return;
            }
            if (!properties.getMcp().isAutoConnect()) {
                log.info("mcp auto connect skipped because clawagent.mcp.auto-connect=false");
                return;
            }
            // 服务启动后自动连接已启用 MCP Server，并把 tools 注册到 AgentToolRegistry，确保 LLM planner 能看到 MCP tools。
            log.info("mcp auto connect starting paths={}", properties.getMcp().getPath());
            mcpRegistry.connectAll();
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(ClawAgentProperties properties, AgentToolRegistry toolRegistry) {
        return new FileSkillRegistry(properties.getSkills().getPath().stream().map(Path::of).toList(), toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(name = "clawAgentExternalSkillInstallToolRunner")
    public ApplicationRunner clawAgentExternalSkillInstallToolRunner(AgentToolRegistry toolRegistry, SkillRegistry skillRegistry) {
        return args -> {
            // 用真实安装工具覆盖文档型 skills-install.install，使 Agent 能实际安装 Codex/Claude Skill。
            toolRegistry.registerOrReplace(new ExternalSkillInstallTool(skillRegistry));
            log.info("external skill install tool registered toolId=skill.skills-install.install");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolExecutionGuard toolExecutionGuard() {
        return new DefaultToolExecutionGuard();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(
            AgentPlanner planner,
            AgentResponseGenerator responseGenerator,
            AgentToolRegistry registry,
            TaskStore taskStore,
            @Qualifier("sessionStore") SessionStore sessionStore,
            @Qualifier("sessionMessageStore") SessionMessageStore messageStore,
            SessionSummarizer sessionSummarizer,
            List<MemoryPromoter> memoryPromoters,
            @Qualifier("agentEventStore") AgentEventStore eventStore,
            TodoStore todoStore,
            List<ToolExecutionGuard> toolGuards,
            List<AgentCallback> callbacks,
            ClawAgentProperties properties) {
        return new DefaultAgentRuntime(planner, responseGenerator, registry, taskStore, sessionStore, messageStore, sessionSummarizer, memoryPromoters, eventStore, todoStore, toolGuards, callbacks, properties.getRuntime().getMaxReactRounds());
    }

    private ClawAgentProperties.ModelConfig defaultModelConfig(ClawAgentProperties properties) {
        String defaultModelId = properties.getModel().getDefault();
        return Optional.ofNullable(properties.getModels().get(defaultModelId))
                .orElseGet(() -> {
                    ClawAgentProperties.ModelConfig config = new ClawAgentProperties.ModelConfig();
                    config.setModel(defaultModelId);
                    return config;
                });
    }

    private String resolveApiKey(ClawAgentProperties.ModelConfig config) {
        return resolveApiKey(config.getApiKey(), config.getApiKeyEnv());
    }

    private String resolveApiKey(String apiKey, String apiKeyEnv) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return null;
        }
        return System.getenv(apiKeyEnv);
    }

    private ToolkitProperties toolkitProperties(ClawAgentProperties properties) {
        ClawAgentProperties.Toolkit source = properties.getToolkit();
        ToolkitProperties target = new ToolkitProperties();
        target.setEnabled(source.isEnabled());
        Map<String, ToolkitToolProperties> tools = new LinkedHashMap<>();
        for (Map.Entry<String, ClawAgentProperties.Tool> entry : source.getTools().entrySet()) {
            ToolkitToolProperties tool = new ToolkitToolProperties();
            // starter 只透传统一工具配置，不解释具体 env 含义，具体工具自行解析自己的参数。
            tool.setEnabled(entry.getValue().isEnabled());
            tool.setEnv(entry.getValue().getEnv());
            tools.put(entry.getKey(), tool);
        }
        target.setTools(tools);
        return target;
    }

    private Object findSpringAiChatClientBuilder(ApplicationContext applicationContext) {
        try {
            Class<?> builderClass = Class.forName("org.springframework.ai.chat.client.ChatClient$Builder");
            String[] beanNames = applicationContext.getBeanNamesForType(builderClass);
            if (beanNames.length == 0) {
                throw new IllegalStateException("clawagent.model.client=spring-ai 需要业务应用提供 Spring AI ChatClient.Builder Bean");
            }
            // 使用第一个 Builder Bean；多模型路由后续可以扩展为按 model id 选择不同 Builder。
            return applicationContext.getBean(beanNames[0]);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("clawagent.model.client=spring-ai 需要业务应用引入 Spring AI ChatClient 依赖", e);
        }
    }
}
