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

        public Markdown getMarkdown() { return markdown; }
        public Vector getVector() { return vector; }
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
        private final Embedding embedding = new Embedding();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
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
        private String planner = "single";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getClient() { return client; }
        public void setClient(String client) { this.client = client; }
        public String getDefault() { return defaultModel; }
        public void setDefault(String defaultModel) { this.defaultModel = defaultModel; }
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
