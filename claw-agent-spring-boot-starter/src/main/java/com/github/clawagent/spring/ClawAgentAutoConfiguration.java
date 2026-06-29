package com.github.clawagent.spring;

import java.nio.file.Files;
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

import com.github.clawagent.knowledge.AttachmentService;
import com.github.clawagent.knowledge.KnowledgeService;
import com.github.clawagent.knowledge.LocalFileStorageProvider;
import com.github.clawagent.knowledge.LocalKnowledgeProvider;
import com.github.clawagent.channel.ChannelAdapterRegistry;
import com.github.clawagent.channel.ChannelRouter;
import com.github.clawagent.channel.ChannelOutboundClient;
import com.github.clawagent.channel.ChannelRuntimeAdapter;
import com.github.clawagent.channel.ChannelSessionMapper;
import com.github.clawagent.channel.ChannelStreamClientManager;
import com.github.clawagent.channel.ChannelStreamStatus;
import com.github.clawagent.channel.FileChannelRegistry;
import com.github.clawagent.core.ChannelDefinition;
import com.github.clawagent.mcp.FileMcpRegistry;
import com.github.clawagent.mcp.McpRegistry;
import com.github.clawagent.memory.DefaultMemoryContextBuilder;
import com.github.clawagent.memory.DefaultMemoryExtractor;
import com.github.clawagent.memory.LlmMemoryIntentClassifier;
import com.github.clawagent.memory.LocalMemoryProvider;
import com.github.clawagent.memory.MarkdownMemoryPromoter;
import com.github.clawagent.memory.MarkdownMemoryRepository;
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
import com.github.clawagent.runtime.DefaultMemoryCandidateProcessor;
import com.github.clawagent.runtime.InMemoryAgentEventStore;
import com.github.clawagent.runtime.InMemoryAutomationStore;
import com.github.clawagent.runtime.InMemorySessionMessageStore;
import com.github.clawagent.runtime.InMemorySessionStore;
import com.github.clawagent.runtime.InMemoryTaskStore;
import com.github.clawagent.runtime.InMemoryTodoStore;
import com.github.clawagent.runtime.MemoryCandidateProcessingOptions;
import com.github.clawagent.runtime.RuleBasedPlanner;
import com.github.clawagent.runtime.SanitizationOptions;
import com.github.clawagent.runtime.SensitiveDataInterceptor;
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
import com.github.clawagent.spi.AgentRuntimeInterceptor;
import com.github.clawagent.spi.AgentTool;
import com.github.clawagent.spi.AgentToolRegistry;
import com.github.clawagent.spi.AutomationStore;
import com.github.clawagent.spi.ChatOptions;
import com.github.clawagent.spi.ChannelRegistry;
import com.github.clawagent.spi.EmbeddingClient;
import com.github.clawagent.spi.EmbeddingOptions;
import com.github.clawagent.spi.FileStorageProvider;
import com.github.clawagent.spi.KnowledgeProvider;
import com.github.clawagent.spi.MemoryCandidateProcessor;
import com.github.clawagent.spi.MemoryPromoter;
import com.github.clawagent.spi.MemoryContextBuilder;
import com.github.clawagent.spi.MemoryExtractor;
import com.github.clawagent.spi.MemoryIntentClassifier;
import com.github.clawagent.spi.MemoryProvider;
import com.github.clawagent.spi.ModelClient;
import com.github.clawagent.spi.SessionMessageStore;
import com.github.clawagent.spi.SessionStore;
import com.github.clawagent.spi.SessionSummarizer;
import com.github.clawagent.spi.TaskStore;
import com.github.clawagent.spi.TodoStore;
import com.github.clawagent.spi.ToolExecutionGuard;
import com.github.clawagent.spring.automation.AutomationSchedulerService;
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
    @ConditionalOnProperty(prefix = "clawagent.knowledge", name = "enabled", havingValue = "true", matchIfMissing = true)
    public KnowledgeProvider localKnowledgeProvider(
            ClawAgentProperties properties,
            EmbeddingClient embeddingClient,
            EmbeddingOptions embeddingOptions) {
        return new LocalKnowledgeProvider(
                resolveRuntimePath(properties.getPersistence().getSqlite().getPath()),
                resolveRuntimePath(properties.getKnowledge().getFilesPath()),
                resolveRuntimePath(properties.getKnowledge().getVectorsPath()),
                embeddingClient,
                embeddingOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeService knowledgeService(ClawAgentProperties properties, List<KnowledgeProvider> providers) {
        return new KnowledgeService(providers, properties.getKnowledge().getProvider());
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryProvider localMemoryProvider(
            ClawAgentProperties properties,
            EmbeddingClient embeddingClient,
            EmbeddingOptions embeddingOptions) {
        ClawAgentProperties.Governance governance = properties.getMemory().getGovernance();
        return new LocalMemoryProvider(
                resolveRuntimePath(properties.getPersistence().getSqlite().getPath()),
                resolveRuntimePath(properties.getMemory().getMarkdown().getPath()),
                resolveRuntimePath(properties.getMemory().getVector().getPath()),
                embeddingClient,
                embeddingOptions,
                new LocalMemoryProvider.GovernanceOptions(
                        governance.getStaleAfterDays(),
                        governance.getVeryStaleAfterDays(),
                        governance.isAutoArchiveEnabled(),
                        governance.getArchiveAfterDays(),
                        governance.getArchiveBelowQuality()));
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryContextBuilder memoryContextBuilder(List<MemoryProvider> providers) {
        return new DefaultMemoryContextBuilder(providers);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryIntentClassifier memoryIntentClassifier(ClawAgentProperties properties,
                                                         ModelClient modelClient,
                                                         ChatOptions chatOptions,
                                                         ApplicationContext applicationContext) {
        ClawAgentProperties.ModelConfig memoryConfig = memoryModelConfig(properties);
        if (memoryConfig == null) {
            // 未配置独立记忆模型时复用聊天模型，保持旧配置可直接运行。
            return new LlmMemoryIntentClassifier(modelClient, chatOptions);
        }
        ChatOptions memoryOptions = new ChatOptions(
                memoryConfig.getModel(),
                memoryConfig.getTemperature(),
                memoryConfig.getTimeoutSeconds());
        return new LlmMemoryIntentClassifier(createModelClient(properties, memoryConfig, applicationContext), memoryOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryExtractor memoryExtractor(MemoryIntentClassifier memoryIntentClassifier) {
        // 默认提炼器只消费分类器结果，并只生成 pending 候选。
        return new DefaultMemoryExtractor(memoryIntentClassifier);
    }

    @Bean
    @ConditionalOnMissingBean
    public FileStorageProvider fileStorageProvider(ClawAgentProperties properties) {
        return new LocalFileStorageProvider(resolveRuntimePath(properties.getAttachments().getLocalPath()));
    }

    @Bean
    @ConditionalOnMissingBean
    public AttachmentService attachmentService(FileStorageProvider fileStorageProvider, KnowledgeService knowledgeService) {
        return new AttachmentService(fileStorageProvider, knowledgeService);
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
            return new SqliteTaskStore(resolveRuntimePath(properties.getPersistence().getSqlite().getPath()));
        }
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore(@Qualifier("taskStore") TaskStore taskStore) {
        if (taskStore instanceof SessionStore sessionStore) {
            return sessionStore;
        }
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionMessageStore sessionMessageStore(@Qualifier("taskStore") TaskStore taskStore) {
        if (taskStore instanceof SessionMessageStore messageStore) {
            return messageStore;
        }
        return new InMemorySessionMessageStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEventStore agentEventStore(@Qualifier("taskStore") TaskStore taskStore) {
        if (taskStore instanceof AgentEventStore eventStore) {
            return eventStore;
        }
        return new InMemoryAgentEventStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public TodoStore todoStore(@Qualifier("taskStore") TaskStore taskStore) {
        if (taskStore instanceof TodoStore todoStore) {
            return todoStore;
        }
        return new InMemoryTodoStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public AutomationStore automationStore(@Qualifier("taskStore") TaskStore taskStore) {
        if (taskStore instanceof AutomationStore automationStore) {
            return automationStore;
        }
        return new InMemoryAutomationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public MarkdownMemoryRepository markdownMemoryRepository(ClawAgentProperties properties) {
        MarkdownMemoryRepository repository = new MarkdownMemoryRepository(resolveRuntimePath(properties.getMemory().getMarkdown().getPath()));
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
    public ToolkitRegistry toolkitRegistry(AgentToolRegistry toolRegistry,
                                           ClawAgentProperties properties,
                                           @Qualifier("todoStore") TodoStore todoStore) {
        ToolkitRegistry registry = new ToolkitRegistry(toolRegistry, toolkitProperties(properties), todoStore);
        // starter 只初始化 toolkit 注册器，不再逐个实例化具体工具。
        registry.load();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public McpRegistry mcpRegistry(AgentToolRegistry toolRegistry, ClawAgentProperties properties) {
        return new FileMcpRegistry(toolRegistry, resolveRuntimePaths(properties.getMcp().getPath()));
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
        return new FileSkillRegistry(resolveRuntimePaths(properties.getSkills().getPath()), toolRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelAdapterRegistry channelAdapterRegistry(ClawAgentProperties properties,
                                                         List<ChannelRuntimeAdapter> channelRuntimeAdapters) {
        // 平台 Channel 默认由独立模块的 Spring Bean 注册；外部 jar adapter 作为可选扩展补充加载。
        return new ChannelAdapterRegistry(channelRuntimeAdapters, resolveRuntimePaths(properties.getChannels().getAdapterPath()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelRegistry channelRegistry(ClawAgentProperties properties, ChannelAdapterRegistry channelAdapterRegistry) {
        return new FileChannelRegistry(
                resolveRuntimePath(".clawagent/channels/channels.json"),
                channelAdapterRegistry,
                configuredChannels(properties));
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelSessionMapper channelSessionMapper() {
        return new ChannelSessionMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelOutboundClient channelOutboundClient(ChannelAdapterRegistry channelAdapterRegistry) {
        return new ChannelOutboundClient(channelAdapterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChannelStreamClientManager channelStreamClientManager(ChannelAdapterRegistry channelAdapterRegistry) {
        return new ChannelStreamClientManager(channelAdapterRegistry, true);
    }

    @Bean
    @ConditionalOnMissingBean(name = "clawAgentChannelStreamAutoStartRunner")
    public ApplicationRunner clawAgentChannelStreamAutoStartRunner(
            ChannelRegistry channelRegistry,
            ChannelStreamClientManager channelStreamClientManager) {
        return args -> channelRegistry.list().stream()
                .filter(ChannelDefinition::enabled)
                .forEach(channel -> {
                    ChannelStreamStatus status = channelStreamClientManager.start(channel);
                    if ("running".equalsIgnoreCase(status.status())) {
                        log.info("channel stream auto-started channelId={} channelType={} mode={} status={}",
                                status.channelId(), status.channelType(), status.mode(), status.status());
                    } else if ("failed".equalsIgnoreCase(status.status())) {
                        log.warn("channel stream auto-start failed channelId={} channelType={} mode={} message={}",
                                status.channelId(), status.channelType(), status.mode(), status.message());
                    }
                });
    }
    @Bean
    @ConditionalOnMissingBean
    public ChannelRouter channelRouter(
            AgentRuntime agentRuntime,
            ChannelRegistry channelRegistry,
            ChannelSessionMapper channelSessionMapper,
            ChannelOutboundClient channelOutboundClient) {
        // ChannelRouter 是 IM/Webhook/API 入站的统一入口，server 只做 HTTP 参数适配。
        return new ChannelRouter(agentRuntime, channelRegistry, channelSessionMapper, channelOutboundClient);
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

    @Bean(name = "clawAgentSensitiveDataInterceptor")
    @ConditionalOnMissingBean(name = "clawAgentSensitiveDataInterceptor")
    @ConditionalOnProperty(prefix = "clawagent.runtime.sanitization", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AgentRuntimeInterceptor clawAgentSensitiveDataInterceptor(ClawAgentProperties properties) {
        ClawAgentProperties.Sanitization sanitization = properties.getRuntime().getSanitization();
        return new SensitiveDataInterceptor(new SanitizationOptions(
                sanitization.isEnabled(),
                0,
                sanitization.getReplacement(),
                sanitization.getSensitiveKeys(),
                sanitization.getValuePatterns()));
    }

    @Bean(name = "clawAgentMediaAttachmentRuntimeInterceptor")
    @ConditionalOnMissingBean(name = "clawAgentMediaAttachmentRuntimeInterceptor")
    public AgentRuntimeInterceptor clawAgentMediaAttachmentRuntimeInterceptor(
            ClawAgentProperties properties,
            FileStorageProvider fileStorageProvider,
            AttachmentService attachmentService,
            KnowledgeService knowledgeService) {
        return new MediaAttachmentRuntimeInterceptor(properties, fileStorageProvider, attachmentService, knowledgeService);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryCandidateProcessor memoryCandidateProcessor(
            @Qualifier("sessionStore") SessionStore sessionStore,
            @Qualifier("sessionMessageStore") SessionMessageStore messageStore,
            @Qualifier("agentEventStore") AgentEventStore eventStore,
            List<MemoryExtractor> memoryExtractors,
            MemoryProvider memoryProvider,
            ClawAgentProperties properties) {
        ClawAgentProperties.Extraction extraction = properties.getMemory().getExtraction();
        // 候选记忆提炼改为后台批处理，避免聊天完成后同步等待记忆模型。
        MemoryCandidateProcessingOptions options = new MemoryCandidateProcessingOptions(
                extraction.isEnabled(),
                extraction.getMode(),
                extraction.getIntervalSeconds(),
                extraction.getBatchSize());
        return new DefaultMemoryCandidateProcessor(sessionStore, messageStore, eventStore, memoryExtractors, memoryProvider, options);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntime agentRuntime(
            AgentPlanner planner,
            AgentResponseGenerator responseGenerator,
            AgentToolRegistry registry,
            @Qualifier("taskStore") TaskStore taskStore,
            @Qualifier("sessionStore") SessionStore sessionStore,
            @Qualifier("sessionMessageStore") SessionMessageStore messageStore,
            SessionSummarizer sessionSummarizer,
            List<MemoryPromoter> memoryPromoters,
            MemoryContextBuilder memoryContextBuilder,
            MemoryCandidateProcessor memoryCandidateProcessor,
            @Qualifier("agentEventStore") AgentEventStore eventStore,
            @Qualifier("todoStore") TodoStore todoStore,
            List<ToolExecutionGuard> toolGuards,
            List<AgentCallback> callbacks,
            List<AgentRuntimeInterceptor> runtimeInterceptors,
            ClawAgentProperties properties) {
        return new DefaultAgentRuntime(
                planner,
                responseGenerator,
                registry,
                taskStore,
                sessionStore,
                messageStore,
                sessionSummarizer,
                memoryPromoters,
                memoryContextBuilder,
                memoryCandidateProcessor,
                eventStore,
                todoStore,
                toolGuards,
                callbacks,
                properties.getRuntime().getMaxReactRounds(),
                runtimeInterceptors);
    }

    @Bean
    @ConditionalOnMissingBean
    public AutomationSchedulerService automationSchedulerService(
            @Qualifier("automationStore") AutomationStore automationStore,
            AgentRuntime agentRuntime,
            ClawAgentProperties properties) {
        return new AutomationSchedulerService(automationStore, agentRuntime, properties);
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

    private ClawAgentProperties.ModelConfig memoryModelConfig(ClawAgentProperties properties) {
        String memoryModelId = properties.getModel().getMemoryModel();
        if (memoryModelId == null || memoryModelId.isBlank()
                || memoryModelId.equals(properties.getModel().getDefault())) {
            return null;
        }
        return Optional.ofNullable(properties.getModels().get(memoryModelId))
                .orElseGet(() -> {
                    ClawAgentProperties.ModelConfig config = new ClawAgentProperties.ModelConfig();
                    config.setModel(memoryModelId);
                    return config;
                });
    }

    private ModelClient createModelClient(ClawAgentProperties properties,
                                          ClawAgentProperties.ModelConfig config,
                                          ApplicationContext applicationContext) {
        if ("spring-ai".equalsIgnoreCase(properties.getModel().getClient())) {
            Object builder = findSpringAiChatClientBuilder(applicationContext);
            // Spring AI 模式下仍由业务应用提供模型 Builder；memory-model 只切换 ChatOptions.model。
            return new SpringAiChatClientModelClient(builder);
        }
        return new OpenAiCompatibleModelClient(config.getBaseUrl(), resolveApiKey(config));
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
            Map<String, String> env = new LinkedHashMap<>(entry.getValue().getEnv());
            if ("filesystem".equals(entry.getKey()) && !env.containsKey("IGNORED_PATTERNS")) {
                // 本地配置页维护的是产品层工作区规则，这里下发给 filesystem 工具执行批量搜索过滤。
                env.put("IGNORED_PATTERNS", String.join(",", properties.getLocal().getIgnorePatterns()));
            }
            tool.setEnv(env);
            tools.put(entry.getKey(), tool);
        }
        target.setTools(tools);
        return target;
    }

    private List<Path> resolveRuntimePaths(List<String> paths) {
        return paths.stream().map(this::resolveRuntimePath).toList();
    }

    List<ChannelDefinition> configuredChannels(ClawAgentProperties properties) {
        List<ChannelDefinition> channels = new java.util.ArrayList<>();
        for (ClawAgentProperties.Channel channel : properties.getChannels().getDefinitions()) {
            if (channel == null) {
                continue;
            }
            channels.add(new ChannelDefinition(
                    channel.getId(),
                    channel.getName(),
                    channel.getType(),
                    channel.isEnabled(),
                    channel.getApprovalMode(),
                    channel.getApprovedToolIds(),
                    channel.getInboundPath(),
                    channel.getMetadata(),
                    null,
                    null));
        }
        channels.addAll(FileChannelRegistry.fromAccountStyleConfig(properties.getChannels().getConfigs()));
        return channels;
    }

    private Path resolveRuntimePath(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path directPath = userDir.resolve(path).normalize();
        if (Files.exists(directPath)) {
            return directPath;
        }

        // 业务数据目录默认放在项目根目录；从 server 子模块启动时，向上查找最近的 .clawagent 根目录。
        if (path.getNameCount() > 0 && ".clawagent".equals(path.getName(0).toString())) {
            Path cursor = userDir;
            while (cursor != null) {
                Path base = cursor.resolve(".clawagent").normalize();
                if (Files.exists(base)) {
                    return cursor.resolve(path).normalize();
                }
                cursor = cursor.getParent();
            }
        }

        return directPath;
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
