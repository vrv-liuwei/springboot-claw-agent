package com.github.clawagent.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ClawAgentProperties 只承载 M1 必需配置。
 * 全量配置会逐步进入这里，但默认值必须保证下载后能直接启动。
 */
@ConfigurationProperties(prefix = "clawagent")
public class ClawAgentProperties {
    private boolean enabled = true;
    private final Persistence persistence = new Persistence();
    private final Memory memory = new Memory();
    private final Mcp mcp = new Mcp();
    private final Skills skills = new Skills();
    private final Toolkit toolkit = new Toolkit();
    private final Knowledge knowledge = new Knowledge();
    private final Attachments attachments = new Attachments();
    private final Automation automation = new Automation();
    private final Runtime runtime = new Runtime();
    private final Model model = new Model();
    private final Map<String, ModelConfig> models = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Persistence getPersistence() {
        return persistence;
    }

    public Memory getMemory() {
        return memory;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public Skills getSkills() {
        return skills;
    }

    public Toolkit getToolkit() {
        return toolkit;
    }

    public Knowledge getKnowledge() {
        return knowledge;
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public Automation getAutomation() {
        return automation;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Model getModel() {
        return model;
    }

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public static class Persistence {
        private String type = "sqlite";
        private final Sqlite sqlite = new Sqlite();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Sqlite getSqlite() { return sqlite; }
    }

    public static class Sqlite {
        private String path = ".clawagent/clawagent.db";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class Memory {
        private final Markdown markdown = new Markdown();
        private final Vector vector = new Vector();
        private final Extraction extraction = new Extraction();

        public Markdown getMarkdown() { return markdown; }
        public Vector getVector() { return vector; }
        public Extraction getExtraction() { return extraction; }
    }

    /**
     * 候选记忆提炼配置。
     * 这些参数只控制“任务完成后是否调用记忆模型提炼候选”，不影响长期记忆检索。
     */
    public static class Extraction {
        /** 是否启用候选记忆提炼。关闭后不会再调用记忆模型生成 pending 记忆。 */
        private boolean enabled = true;
        /** 处理策略：after-task-async=每轮任务后异步处理；batch=定时或累计条数批处理。 */
        private String mode = "after-task-async";
        /** 定时批处理间隔秒数。 */
        private long intervalSeconds = 60;
        /** 单次后台批处理最多处理多少条任务。 */
        private int batchSize = 100;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public long getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(long intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    public static class Mcp {
        private boolean enabled = true;
        private boolean autoConnect = true;
        private List<String> path = new ArrayList<>(List.of(".clawagent/mcp/mcp.json"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isAutoConnect() { return autoConnect; }
        public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }
        public List<String> getPath() { return path; }
        public void setPath(List<String> path) { this.path = path == null ? new ArrayList<>() : new ArrayList<>(path); }
    }

    public static class Skills {
        private boolean enabled = true;
        private List<String> path = new ArrayList<>(List.of(".clawagent/skills"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getPath() { return path; }
        public void setPath(List<String> path) { this.path = path == null ? new ArrayList<>() : new ArrayList<>(path); }
    }

    public static class Toolkit {
        private boolean enabled = true;
        private Map<String, Tool> tools = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, Tool> getTools() { return tools; }
        public void setTools(Map<String, Tool> tools) { this.tools = tools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tools); }
    }

    public static class Knowledge {
        private boolean enabled = true;
        private String provider = "local";
        private String filesPath = ".clawagent/knowledge/files";
        private String vectorsPath = ".clawagent/knowledge/vectors";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getFilesPath() { return filesPath; }
        public void setFilesPath(String filesPath) { this.filesPath = filesPath; }
        public String getVectorsPath() { return vectorsPath; }
        public void setVectorsPath(String vectorsPath) { this.vectorsPath = vectorsPath; }
    }

    public static class Attachments {
        private String localPath = ".clawagent/attachments";

        public String getLocalPath() { return localPath; }
        public void setLocalPath(String localPath) { this.localPath = localPath; }
    }

    public static class Automation {
        private boolean enabled = true;
        private int pollIntervalSeconds = 5;
        private int dueBatchSize = 10;
        private String defaultChannelId = "automation";
        private String defaultUserId = "automation";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        public int getDueBatchSize() { return dueBatchSize; }
        public void setDueBatchSize(int dueBatchSize) { this.dueBatchSize = dueBatchSize; }
        public String getDefaultChannelId() { return defaultChannelId; }
        public void setDefaultChannelId(String defaultChannelId) { this.defaultChannelId = defaultChannelId; }
        public String getDefaultUserId() { return defaultUserId; }
        public void setDefaultUserId(String defaultUserId) { this.defaultUserId = defaultUserId; }
    }

    public static class Runtime {
        private int maxReactRounds = 15;
        private final Sanitization sanitization = new Sanitization();

        public int getMaxReactRounds() { return maxReactRounds; }
        public void setMaxReactRounds(int maxReactRounds) { this.maxReactRounds = maxReactRounds; }
        public Sanitization getSanitization() { return sanitization; }
    }

    public static class Sanitization {
        private boolean enabled = true;
        private String replacement = "***";
        private List<String> sensitiveKeys = new ArrayList<>(List.of("api_key", "apikey", "api-key", "authorization", "token", "secret", "password", "key"));
        private List<String> valuePatterns = new ArrayList<>(List.of(
                "(?i)(api[_-]?key[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(authorization[\"'\\s:=]+Bearer\\s+)([^\"'\\s,}]+)",
                "(?i)(token[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(secret[\"'\\s:=]+)([^\"'\\s,}]+)",
                "(?i)(password[\"'\\s:=]+)([^\"'\\s,}]+)",
                "as_sk_[A-Za-z0-9_\\-]+",
                "sk-[A-Za-z0-9_\\-]+",
                "glpat-[A-Za-z0-9_\\-]+"
        ));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getReplacement() { return replacement; }
        public void setReplacement(String replacement) { this.replacement = replacement; }
        public List<String> getSensitiveKeys() { return sensitiveKeys; }
        public void setSensitiveKeys(List<String> sensitiveKeys) { this.sensitiveKeys = sensitiveKeys == null ? new ArrayList<>() : new ArrayList<>(sensitiveKeys); }
        public List<String> getValuePatterns() { return valuePatterns; }
        public void setValuePatterns(List<String> valuePatterns) { this.valuePatterns = valuePatterns == null ? new ArrayList<>() : new ArrayList<>(valuePatterns); }
    }

    public static class Tool {
        private boolean enabled = true;
        private Map<String, String> env = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env); }
    }

    public static class Markdown {
        private boolean enabled = true;
        private String path = ".clawagent/memory";
        private boolean autoPromoteSessionSummary = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public boolean isAutoPromoteSessionSummary() { return autoPromoteSessionSummary; }
        public void setAutoPromoteSessionSummary(boolean autoPromoteSessionSummary) { this.autoPromoteSessionSummary = autoPromoteSessionSummary; }
    }

    public static class Vector {
        private boolean enabled = false;
        private String provider = "none";
        private String path = ".clawagent/memory/vectors";
        private final Embedding embedding = new Embedding();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
        public Embedding getEmbedding() { return embedding; }
    }

    public static class Embedding {
        private String provider = "none";
        private String model = "";
        private String baseUrl = "";
        private String apiKey;
        private String apiKeyEnv = "CLAWAGENT_EMBEDDING_API_KEY";
        private int dimensions = 0;
        private int timeoutSeconds = 60;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }

    public static class Model {
        private String mode = "llm";
        private String client = "openai-compatible";
        private String defaultModel = "deepseek-v4-flash";
        private String memoryModel = "";
        private String planner = "single";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getClient() { return client; }
        public void setClient(String client) { this.client = client; }
        public String getDefault() { return defaultModel; }
        public void setDefault(String defaultModel) { this.defaultModel = defaultModel; }
        public String getMemoryModel() { return memoryModel; }
        public void setMemoryModel(String memoryModel) { this.memoryModel = memoryModel; }
        public String getPlanner() { return planner; }
        public void setPlanner(String planner) { this.planner = planner; }
    }

    public static class ModelConfig {
        private String provider = "deepseek";
        private String baseUrl = "https://api.deepseek.com";
        private String model = "deepseek-v4-flash";
        private String apiKey;
        private String apiKeyEnv = "DEEPSEEK_API_KEY";
        private double temperature = 0.2;
        private int timeoutSeconds = 60;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getApiKeyEnv() { return apiKeyEnv; }
        public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
