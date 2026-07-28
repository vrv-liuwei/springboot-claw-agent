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
    private String mode = "server";
    private final Persistence persistence = new Persistence();
    private final Memory memory = new Memory();
    private final Mcp mcp = new Mcp();
    private final Skills skills = new Skills();
    private final Toolkit toolkit = new Toolkit();
    private final Knowledge knowledge = new Knowledge();
    private final Attachments attachments = new Attachments();
    private final Automation automation = new Automation();
    private final Intent intent = new Intent();
    private final Channels channels = new Channels();
    private final Runtime runtime = new Runtime();
    private final Local local = new Local();
    private final Cost cost = new Cost();
    private final Model model = new Model();
    private final Agents agents = new Agents();
    private final Map<String, ModelConfig> models = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
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

    public Intent getIntent() {
        return intent;
    }

    public Channels getChannels() {
        return channels;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public Local getLocal() {
        return local;
    }

    public Cost getCost() {
        return cost;
    }

    public Model getModel() {
        return model;
    }

    public Agents getAgents() {
        return agents;
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
        private final Governance governance = new Governance();

        public Markdown getMarkdown() { return markdown; }
        public Vector getVector() { return vector; }
        public Extraction getExtraction() { return extraction; }
        public Governance getGovernance() { return governance; }
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

    /**
     * 长期记忆治理配置。
     * 只处理 active 记忆，pending/conflict/disabled 不会因为自动规则改变状态。
     */
    public static class Governance {
        /** 超过该天数未命中后开始降权。 */
        private int staleAfterDays = 30;
        /** 超过该天数未命中后降权趋近上限。 */
        private int veryStaleAfterDays = 180;
        /** 是否启用低质量或长期未命中记忆自动归档。 */
        private boolean autoArchiveEnabled = false;
        /** 超过该天数未命中时归档，0 表示不按天数归档。 */
        private int archiveAfterDays = 365;
        /** 质量分低于该阈值时归档，0 表示不按质量分归档。 */
        private double archiveBelowQuality = 0.15;

        public int getStaleAfterDays() { return staleAfterDays; }
        public void setStaleAfterDays(int staleAfterDays) { this.staleAfterDays = staleAfterDays; }
        public int getVeryStaleAfterDays() { return veryStaleAfterDays; }
        public void setVeryStaleAfterDays(int veryStaleAfterDays) { this.veryStaleAfterDays = veryStaleAfterDays; }
        public boolean isAutoArchiveEnabled() { return autoArchiveEnabled; }
        public void setAutoArchiveEnabled(boolean autoArchiveEnabled) { this.autoArchiveEnabled = autoArchiveEnabled; }
        public int getArchiveAfterDays() { return archiveAfterDays; }
        public void setArchiveAfterDays(int archiveAfterDays) { this.archiveAfterDays = archiveAfterDays; }
        public double getArchiveBelowQuality() { return archiveBelowQuality; }
        public void setArchiveBelowQuality(double archiveBelowQuality) { this.archiveBelowQuality = archiveBelowQuality; }
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
        private int maxRetryAttempts = 0;
        private int retryBackoffSeconds = 60;
        private boolean pauseAfterRetriesExhausted = false;
        private String defaultChannelId = "automation";
        private String defaultUserId = "automation";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPollIntervalSeconds() { return pollIntervalSeconds; }
        public void setPollIntervalSeconds(int pollIntervalSeconds) { this.pollIntervalSeconds = pollIntervalSeconds; }
        public int getDueBatchSize() { return dueBatchSize; }
        public void setDueBatchSize(int dueBatchSize) { this.dueBatchSize = dueBatchSize; }
        public int getMaxRetryAttempts() { return maxRetryAttempts; }
        public void setMaxRetryAttempts(int maxRetryAttempts) { this.maxRetryAttempts = maxRetryAttempts; }
        public int getRetryBackoffSeconds() { return retryBackoffSeconds; }
        public void setRetryBackoffSeconds(int retryBackoffSeconds) { this.retryBackoffSeconds = retryBackoffSeconds; }
        public boolean isPauseAfterRetriesExhausted() { return pauseAfterRetriesExhausted; }
        public void setPauseAfterRetriesExhausted(boolean pauseAfterRetriesExhausted) { this.pauseAfterRetriesExhausted = pauseAfterRetriesExhausted; }
        public String getDefaultChannelId() { return defaultChannelId; }
        public void setDefaultChannelId(String defaultChannelId) { this.defaultChannelId = defaultChannelId; }
        public String getDefaultUserId() { return defaultUserId; }
        public void setDefaultUserId(String defaultUserId) { this.defaultUserId = defaultUserId; }
    }

    public static class Intent {
        private boolean enabled = true;
        private double threshold = 0.78;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
    }

    public static class Channels {
        /** 外部 ChannelRuntimeAdapter jar 扫描路径；目录下所有 jar 会通过 ServiceLoader 加入 adapter 注册表。 */
        private List<String> adapterPath = new ArrayList<>(List.of(".clawagent/channels/adapters"));
        /** application.yml 中声明的扁平 Channel 配置，启动时会和内置模板、本地 channels.json 合并。 */
        private List<Channel> definitions = new ArrayList<>();
        /** OpenClaw 风格多账号配置，key 是 channel type，例如 feishu/dingtalk/ddio。 */
        private Map<String, Object> configs = new LinkedHashMap<>();

        public List<String> getAdapterPath() { return adapterPath; }
        public void setAdapterPath(List<String> adapterPath) {
            this.adapterPath = adapterPath == null ? new ArrayList<>() : new ArrayList<>(adapterPath);
        }
        public List<Channel> getDefinitions() { return definitions; }
        public void setDefinitions(List<Channel> definitions) {
            this.definitions = definitions == null ? new ArrayList<>() : new ArrayList<>(definitions);
        }
        public Map<String, Object> getConfigs() { return configs; }
        public void setConfigs(Map<String, Object> configs) {
            this.configs = configs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(configs);
        }
    }

    public static class Channel {
        private String id = "";
        private String name = "";
        private String type = "";
        private boolean enabled = false;

        private String approvalMode = "ask";
        private List<String> approvedToolIds = new ArrayList<>();
        private String inboundPath = "";
        private Map<String, String> metadata = new LinkedHashMap<>();

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getApprovalMode() { return approvalMode; }
        public void setApprovalMode(String approvalMode) { this.approvalMode = approvalMode; }
        public List<String> getApprovedToolIds() { return approvedToolIds; }
        public void setApprovedToolIds(List<String> approvedToolIds) {
            this.approvedToolIds = approvedToolIds == null ? new ArrayList<>() : new ArrayList<>(approvedToolIds);
        }
        public String getInboundPath() { return inboundPath; }
        public void setInboundPath(String inboundPath) { this.inboundPath = inboundPath; }
        public Map<String, String> getMetadata() { return metadata; }
        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        }
    }

    public static class Cost {
        /** 成本估算默认币种；管理台会按模型价格规则估算 Token 成本。 */
        private String currency = "USD";
        /** 按模型 ID 或真实模型名维护的每百万 Token 单价。 */
        private Map<String, ModelPrice> rules = new LinkedHashMap<>();

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public Map<String, ModelPrice> getRules() { return rules; }
        public void setRules(Map<String, ModelPrice> rules) {
            this.rules = rules == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rules);
        }
    }

    public static class ModelPrice {
        /** 输入 Token 每百万单价。 */
        private double inputPerMillion = 0;
        /** 输出 Token 每百万单价。 */
        private double outputPerMillion = 0;
        /** 单条规则可覆盖默认币种。 */
        private String currency = "";

        public double getInputPerMillion() { return inputPerMillion; }
        public void setInputPerMillion(double inputPerMillion) { this.inputPerMillion = inputPerMillion; }
        public double getOutputPerMillion() { return outputPerMillion; }
        public void setOutputPerMillion(double outputPerMillion) { this.outputPerMillion = outputPerMillion; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }

    public static class Local {
        /** 默认本地项目目录；聊天页和桌面端都可以用它作为任务工作区起点。 */
        private String workspaceRoot = ".clawagent/workspace";
        /** 默认 Shell 名称；桌面端和 Web 管理台用它提示用户当前命令执行环境。 */
        private String defaultShell = System.getProperty("os.name", "").toLowerCase().contains("win") ? "powershell" : "sh";
        /** 本地工具权限模式：ask/auto/full/custom，真实执行仍由 ToolExecutionGuard 做最终判定。 */
        private String permissionMode = "ask";
        /** custom 权限模式下默认批准的工具 ID；每次任务仍会写入 metadata 便于审计。 */
        private List<String> approvedToolIds = new ArrayList<>();
        /** 允许本地工具访问的根目录；会同步到 execute/filesystem 的 ALLOWED_ROOTS。 */
        private List<String> allowedRoots = new ArrayList<>(List.of(".clawagent/workspace"));
        /** 用户确认过的最近项目目录，后续用于快速切换工作区。 */
        private List<String> recentProjects = new ArrayList<>();
        /** 项目验证命令，按顺序展示和建议执行，默认仍会结合项目文件自动检测。 */
        private List<String> testCommands = new ArrayList<>();
        /** 按项目目录配置的验证命令；用于 monorepo 或多个本地项目共用一套 ClawAgent 的场景。 */
        private Map<String, List<String>> projectTestCommands = new LinkedHashMap<>();
        /** 本地开发默认忽略目录；用于搜索、文件审查和摘要，不影响用户显式读取单个文件。 */
        private List<String> ignorePatterns = new ArrayList<>(List.of(
                "**/.git/**",
                ".git/**",
                "**/.clawagent/**",
                ".clawagent/**",
                "**/node_modules/**",
                "node_modules/**",
                "**/target/**",
                "target/**",
                "**/build/**",
                "build/**",
                "**/dist/**",
                "dist/**",
                "**/.idea/**",
                ".idea/**",
                "**/.vscode/**",
                ".vscode/**"
        ));
        /** 敏感路径模式；filesystem 直接拦截，execute 命中后升为高危审批。 */
        private List<String> sensitivePathPatterns = new ArrayList<>(List.of(
                "**/.env",
                ".env",
                "**/.env.*",
                ".env.*",
                "**/*.key",
                "**/*.pem",
                "**/*.p12",
                "**/*.pfx",
                "**/.ssh/**",
                ".ssh/**",
                "**/.git/**",
                ".git/**"
        ));

        public String getWorkspaceRoot() { return workspaceRoot; }
        public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }
        public String getDefaultShell() { return defaultShell; }
        public void setDefaultShell(String defaultShell) { this.defaultShell = defaultShell; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) { this.permissionMode = permissionMode; }
        public List<String> getApprovedToolIds() { return approvedToolIds; }
        public void setApprovedToolIds(List<String> approvedToolIds) {
            this.approvedToolIds = approvedToolIds == null ? new ArrayList<>() : new ArrayList<>(approvedToolIds);
        }
        public List<String> getAllowedRoots() { return allowedRoots; }
        public void setAllowedRoots(List<String> allowedRoots) {
            this.allowedRoots = allowedRoots == null ? new ArrayList<>() : new ArrayList<>(allowedRoots);
        }
        public List<String> getRecentProjects() { return recentProjects; }
        public void setRecentProjects(List<String> recentProjects) {
            this.recentProjects = recentProjects == null ? new ArrayList<>() : new ArrayList<>(recentProjects);
        }
        public List<String> getTestCommands() { return testCommands; }
        public void setTestCommands(List<String> testCommands) {
            this.testCommands = testCommands == null ? new ArrayList<>() : new ArrayList<>(testCommands);
        }
        public Map<String, List<String>> getProjectTestCommands() { return projectTestCommands; }
        public void setProjectTestCommands(Map<String, List<String>> projectTestCommands) {
            this.projectTestCommands = new LinkedHashMap<>();
            if (projectTestCommands == null) {
                return;
            }
            projectTestCommands.forEach((path, commands) ->
                    this.projectTestCommands.put(path, commands == null ? new ArrayList<>() : new ArrayList<>(commands)));
        }
        public List<String> getIgnorePatterns() { return ignorePatterns; }
        public void setIgnorePatterns(List<String> ignorePatterns) {
            this.ignorePatterns = ignorePatterns == null ? new ArrayList<>() : new ArrayList<>(ignorePatterns);
        }
        public List<String> getSensitivePathPatterns() { return sensitivePathPatterns; }
        public void setSensitivePathPatterns(List<String> sensitivePathPatterns) {
            this.sensitivePathPatterns = sensitivePathPatterns == null ? new ArrayList<>() : new ArrayList<>(sensitivePathPatterns);
        }
    }

    public static class Runtime {
        private int maxReactRounds = 15;
        private final Sanitization sanitization = new Sanitization();

        public int getMaxReactRounds() { return maxReactRounds; }
        public void setMaxReactRounds(int maxReactRounds) { this.maxReactRounds = maxReactRounds; }
        public Sanitization getSanitization() { return sanitization; }
    }

    public static class Agents {
        /** Agent 角色到权限策略的映射；调度层只写 agent.role，策略模板由服务端统一解析。 */
        private Map<String, AgentPolicy> policies = new LinkedHashMap<>();
        private final SubAgentWorker worker = new SubAgentWorker();

        public Map<String, AgentPolicy> getPolicies() { return policies; }
        public void setPolicies(Map<String, AgentPolicy> policies) {
            this.policies = policies == null ? new LinkedHashMap<>() : new LinkedHashMap<>(policies);
        }
        public SubAgentWorker getWorker() { return worker; }
    }

    public static class AgentPolicy {
        /** 是否启用该 Agent 角色策略；禁用后仅保留配置，不参与任务权限合并。 */
        private boolean enabled = true;
        /** Agent 角色的工具权限模式，语义和本地 permission-mode 保持一致。 */
        private String permissionMode = "";
        /** 兼容旧命名或页面命名；为空时优先使用 permissionMode。 */
        private String approvalMode = "";
        /** Agent 角色允许的工具白名单；多层策略同时存在时会取交集。 */
        private List<String> approvedToolIds = new ArrayList<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getPermissionMode() { return permissionMode; }
        public void setPermissionMode(String permissionMode) {
            this.permissionMode = permissionMode == null ? "" : permissionMode.trim();
        }
        public String getApprovalMode() { return approvalMode; }
        public void setApprovalMode(String approvalMode) {
            this.approvalMode = approvalMode == null ? "" : approvalMode.trim();
        }
        public List<String> getApprovedToolIds() { return approvedToolIds; }
        public void setApprovedToolIds(List<String> approvedToolIds) {
            this.approvedToolIds = approvedToolIds == null ? new ArrayList<>() : new ArrayList<>(approvedToolIds);
        }
    }

    public static class SubAgentWorker {
        /** 是否允许子 Agent 请求独立 worker。默认关闭，避免误以为已经进程隔离。 */
        private boolean enabled = false;
        /** 子 Agent worker 目标模式；当前只接受 external-process/process 作为未来进程入口。 */
        private String mode = "external-process";
        /** 外部 worker 启动命令。为空时仅记录请求并降级，不会声明已具备进程调度能力。 */
        private String command = "";
        /** 外部 worker 启动参数；推荐把可执行文件放 command，参数放 args，避免 Windows 路径空格解析问题。 */
        private List<String> args = new ArrayList<>();
        /** 子 Agent worker 最大并发，用于后续独立进程调度器限流。 */
        private int maxConcurrent = 2;
        /** 获取 worker 槽位的等待时间，避免子 Agent 派发无界阻塞。 */
        private long acquireTimeoutMs = 5000;
        /** 单个子 Agent worker 进程最大运行时间，超时后会强杀进程树。 */
        private long timeoutMs = 300000;
        /** worker stdout/stderr 单流最大读取字节数，超出后截断但继续 drain，避免管道写满卡死。 */
        private int maxOutputBytes = 1024 * 1024;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode == null || mode.isBlank() ? "external-process" : mode.trim(); }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command == null ? "" : command.trim(); }
        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args == null ? new ArrayList<>() : new ArrayList<>(args); }
        public int getMaxConcurrent() { return maxConcurrent; }
        public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent <= 0 ? 1 : maxConcurrent; }
        public long getAcquireTimeoutMs() { return acquireTimeoutMs; }
        public void setAcquireTimeoutMs(long acquireTimeoutMs) { this.acquireTimeoutMs = acquireTimeoutMs <= 0 ? 5000 : acquireTimeoutMs; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs <= 0 ? 300000 : timeoutMs; }
        public int getMaxOutputBytes() { return maxOutputBytes; }
        public void setMaxOutputBytes(int maxOutputBytes) { this.maxOutputBytes = maxOutputBytes <= 0 ? 1024 * 1024 : maxOutputBytes; }
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
        private String visionModel = "";
        private String planner = "single";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getClient() { return client; }
        public void setClient(String client) { this.client = client; }
        public String getDefault() { return defaultModel; }
        public void setDefault(String defaultModel) { this.defaultModel = defaultModel; }
        public String getMemoryModel() { return memoryModel; }
        public void setMemoryModel(String memoryModel) { this.memoryModel = memoryModel; }
        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
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
        private boolean vision = false;

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
        public boolean isVision() { return vision; }
        public void setVision(boolean vision) { this.vision = vision; }
    }
}
