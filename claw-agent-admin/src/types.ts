export type HealthStatus = {
  status?: string;
  name?: string;
};

export type AgentSession = {
  id: string;
  title?: string;
  summary?: string;
  channelId?: string;
  userId?: string;
  status?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type AgentTask = {
  id: string;
  input?: string;
  sessionId?: string;
  status?: string;
  channelId?: string;
  userId?: string;
  finalAnswer?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type AgentMessage = {
  id: string;
  taskId?: string;
  sessionId?: string;
  role?: string;
  content?: string;
  metadata?: Record<string, string>;
  createdAt?: string;
};

export type AgentEvent = {
  id: string;
  taskId?: string;
  sessionId?: string;
  level?: string;
  type?: string;
  message?: string;
  details?: Record<string, unknown>;
  createdAt?: string;
};

export type SystemLogLine = {
  time?: string;
  level?: string;
  thread?: string;
  traceId?: string;
  sessionId?: string;
  taskId?: string;
  userId?: string;
  channelId?: string;
  logger?: string;
  message?: string;
  rawLine?: string;
  sourceFile?: string;
  compressed?: boolean;
};

export type SystemLogSource = {
  name: string;
  date?: string;
  size: number;
  compressed: boolean;
};

export type TodoItem = {
  id: string;
  sessionId?: string;
  taskId?: string;
  itemOrder?: number;
  title?: string;
  description?: string;
  status?: string;
  metadata?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
};

export type TokenUsageSummary = {
  scopeType?: string;
  scopeId?: string;
  sessionId?: string;
  taskId?: string;
  callCount?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  byModel?: Record<string, TokenUsageSummary>;
  byPhase?: Record<string, TokenUsageSummary>;
};

export type ToolDefinition = {
  id: string;
  name?: string;
  description?: string;
  riskLevel?: string;
};

export type McpServerRegistration = {
  id?: string;
  name?: string;
  status?: string;
  connected?: boolean;
  enabled?: boolean;
  config?: {
    id?: string;
    name?: string;
    type?: string;
    transportType?: string;
    command?: string;
    url?: string;
    disabled?: boolean;
  };
  tools?: unknown[];
};

export type SkillRegistration = {
  status?: string;
  installedPath?: string;
  message?: string;
  manifest?: {
    id?: string;
    name?: string;
    version?: string;
    description?: string;
    enabled?: boolean;
    tools?: string[];
  };
};

export type ModelSettings = {
  mode?: string;
  client?: string;
  defaultModel?: string;
  planner?: string;
};

export type ModelConfigView = {
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKeyEnv?: string;
  apiKeyConfigured?: boolean;
  temperature?: number;
  timeoutSeconds?: number;
};

export type RuntimeConfigSnapshot = {
  cwd?: string;
  configRoot?: string;
  configPath?: string;
  restartRequired?: boolean;
  applied?: boolean;
  message?: string;
  model?: ModelSettings;
  effectiveModel?: ModelConfigView;
  models?: Record<string, ModelConfigView>;
};

export type ModelConfigUpdate = {
  mode?: string;
  client?: string;
  defaultModel?: string;
  planner?: string;
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKeyEnv?: string;
  temperature?: number;
  timeoutSeconds?: number;
};

export type AutomationScheduleType = 'ONCE' | 'INTERVAL' | 'CRON';

export type AutomationStatus = 'ENABLED' | 'PAUSED';

export type AutomationRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';

export type AutomationDefinition = {
  id: string;
  name?: string;
  prompt?: string;
  sessionId?: string;
  channelId?: string;
  userId?: string;
  scheduleType?: AutomationScheduleType;
  cronExpression?: string;
  intervalSeconds?: number;
  timezone?: string;
  nextRunAt?: string;
  lastRunAt?: string;
  status?: AutomationStatus;
  metadata?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
};

export type AutomationRun = {
  id: string;
  automationId?: string;
  taskId?: string;
  status?: AutomationRunStatus;
  startedAt?: string;
  finishedAt?: string;
  error?: string;
};

export type AttachmentParseResult = {
  id: string;
  name?: string;
  contentType?: string;
  size?: number;
  kind?: string;
  storageProvider?: string;
  storagePath?: string;
  extractedText?: string;
  originalChars?: number;
  extractedChars?: number;
  truncated?: boolean;
  message?: string;
  knowledgeDocumentId?: string;
  knowledgeProvider?: string;
  providerDocumentId?: string;
};

export type AttachmentParseResponse = {
  attachments: AttachmentParseResult[];
};

export type KnowledgeProviderCapabilities = {
  fileStorage?: boolean;
  vectorSearch?: boolean;
  bm25?: boolean;
  hybridSearch?: boolean;
  asyncParsing?: boolean;
};

export type KnowledgeProviderView = {
  id: string;
  capabilities?: KnowledgeProviderCapabilities;
  active?: boolean;
  default?: boolean;
};

export type KnowledgeDocument = {
  id: string;
  userId?: string;
  provider?: string;
  providerDocumentId?: string;
  name?: string;
  kind?: string;
  size?: number;
  storagePath?: string;
  status?: string;
  metadata?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
};

export type KnowledgeSearchHit = {
  documentId?: string;
  chunkId?: string;
  userId?: string;
  documentName?: string;
  chunkNo?: number;
  text?: string;
  score?: number;
  provider?: string;
  metadata?: Record<string, string>;
};

export type KnowledgeSearchResponse = {
  hits: KnowledgeSearchHit[];
};

export type AutomationUpsertRequest = {
  name?: string;
  prompt?: string;
  sessionId?: string;
  channelId?: string;
  userId?: string;
  scheduleType?: AutomationScheduleType;
  cronExpression?: string;
  intervalSeconds?: number;
  timezone?: string;
  nextRunAt?: string;
  status?: AutomationStatus;
  metadata?: Record<string, string>;
};
