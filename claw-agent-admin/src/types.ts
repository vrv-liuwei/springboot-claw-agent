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
  metadata?: Record<string, string>;
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
  metadata?: Record<string, string>;
  finalAnswer?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type SubAgentTaskRequest = {
  input: string;
  role?: string;
  isolation?: string;
  metadata?: Record<string, string>;
};

export type SubAgentTaskResponse = {
  parentTaskId?: string;
  childTaskId?: string;
  role?: string;
  isolation?: string;
  result?: {
    taskId?: string;
    status?: string;
    finalAnswer?: string;
    error?: string;
  };
  task?: AgentTask;
};

export type AgentOrchestrationGraphNode = {
  taskId: string;
  parentTaskId?: string;
  role?: string;
  isolation?: string;
  status?: string;
  input?: string;
  depth?: number;
  createdAt?: string;
  updatedAt?: string;
};

export type AgentOrchestrationGraphEdge = {
  parentTaskId: string;
  childTaskId: string;
  role?: string;
  isolation?: string;
};

export type AgentOrchestrationGraphView = {
  rootTaskId: string;
  totalTasks?: number;
  runningCount?: number;
  waitingCount?: number;
  completedCount?: number;
  failedCount?: number;
  maxDepth?: number;
  truncated?: boolean;
  nodes?: AgentOrchestrationGraphNode[];
  edges?: AgentOrchestrationGraphEdge[];
};

export type AgentStep = {
  id: string;
  taskId?: string;
  type?: string;
  name?: string;
  input?: Record<string, string>;
  startedAt?: string;
  finishedAt?: string;
  status?: string;
  output?: string;
  error?: string;
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

export type ResumeStateView = {
  taskId?: string;
  canResume?: boolean;
  status?: string;
  reason?: string;
  resumeFromTaskId?: string;
  projectPath?: string;
  resumeMode?: string;
  resumeInstruction?: string;
  todoId?: string;
  todoOrder?: string;
  todoTitle?: string;
  todoStatus?: string;
  checkpoint?: string;
  remainingTodos?: TodoItem[];
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

export type AppWorkspaceView = {
  id: string;
  name?: string;
  root?: string;
  lastOpenedAt?: string;
};

export type SessionContextClearRequest = {
  reason?: string;
  resetTodo?: boolean;
  resetFileReview?: boolean;
};

export type SessionContextCompactRequest = {
  taskId?: string;
  strategy?: string;
  limit?: number;
  includeTodos?: boolean;
  includeFileChanges?: boolean;
  includeToolSummary?: boolean;
  includeOpenIssues?: boolean;
};

export type SessionContextCommandResponse = {
  sessionId: string;
  action?: string;
  contextVersion?: number;
  contextStartAt?: string;
  summary?: string;
  affectedMessages?: number;
};

export type SessionContextSegment = {
  type?: string;
  label?: string;
  items?: number;
  estimatedChars?: number;
  active?: boolean;
};

export type SessionContextView = {
  sessionId: string;
  contextVersion?: number;
  contextStartAt?: string;
  clearedAt?: string;
  compactedAt?: string;
  compactedTaskId?: string;
  summary?: string;
  totalMessages?: number;
  activeMessages?: number;
  inactiveMessages?: number;
  estimatedContextChars?: number;
  estimatedContextTokens?: number;
  tokenUsage?: TokenUsageSummary;
  segments?: SessionContextSegment[];
};

export type SessionRuntimeStatusView = {
  sessionId: string;
  session?: AgentSession;
  currentTask?: AgentTask;
  workspace?: AppWorkspaceView | null;
  tokenUsage?: TokenUsageSummary;
  permissionMode?: string;
  approvedToolCount?: number;
  mcpServerCount?: number;
  mcpConnectedCount?: number;
  toolCount?: number;
  todoTotal?: number;
  todoOpen?: number;
  context?: SessionContextView;
};

export type CostRuleView = {
  inputPerMillion?: number;
  outputPerMillion?: number;
  currency?: string;
};

export type CostConfigView = {
  currency?: string;
  rules?: Record<string, CostRuleView>;
};

export type ToolDefinition = {
  id: string;
  name?: string;
  description?: string;
  riskLevel?: string;
};

export type ChannelDefinition = {
  id: string;
  name?: string;
  type?: string;
  enabled?: boolean;
  approvalMode?: 'ask' | 'auto' | 'full' | 'custom' | string;
  approvedToolIds?: string[];
  inboundPath?: string;
  metadata?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
};

export type ChannelAdapterDescriptor = {
  type: string;
  className?: string;
  source?: string;
  location?: string;
  active?: boolean;
};

export type ChannelAdapterReloadResult = {
  candidateCount?: number;
  activeCount?: number;
  adapters?: ChannelAdapterDescriptor[];
};

export type ChannelConnectivityStatus = {
  channelId?: string;
  channelType?: string;
  ready?: boolean;
  probedRemote?: boolean;
  status?: string;
  message?: string;
  missingKeys?: string[];
  details?: Record<string, string>;
};

export type ChannelStreamStatus = {
  channelId?: string;
  channelType?: string;
  mode?: string;
  status?: string;
  message?: string;
  details?: Record<string, string>;
};

export type ChannelInboundMessage = {
  channelId?: string;
  externalConversationId?: string;
  externalUserId?: string;
  messageType?: string;
  text: string;
  metadata?: Record<string, string>;
  rawPayload?: Record<string, unknown>;
};

export type ChannelInboundResult = {
  channelId?: string;
  sessionId?: string;
  taskId?: string;
  status?: string;
  answer?: string;
};

export type ChannelOutboundTestRequest = {
  externalConversationId?: string;
  externalUserId?: string;
  text: string;
};

export type ChannelOutboundTestResponse = {
  channelId?: string;
  channelType?: string;
  sent?: boolean;
  status?: string;
  message?: string;
  details?: Record<string, string>;
};

export type ApiTokenView = {
  id: string;
  name?: string;
  tokenPrefix?: string;
  status?: string;
  createdAt?: string;
  revokedAt?: string;
  lastUsedAt?: string;
  usageCount?: number;
  lastUsedMethod?: string;
  lastUsedPath?: string;
  metadata?: Record<string, string>;
};

export type ApiTokenCreateRequest = {
  name?: string;
  metadata?: Record<string, string>;
};

export type ApiTokenCreateResponse = {
  tokenInfo?: ApiTokenView;
  token?: string;
};

export type DeviceView = {
  id: string;
  name?: string;
  type?: string;
  status?: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
  revokedAt?: string;
  metadata?: Record<string, string>;
};

export type DeviceRegisterRequest = {
  name?: string;
  type?: string;
  metadata?: Record<string, string>;
};

export type FileChangeView = {
  id: string;
  taskId?: string;
  stepId?: string;
  toolId?: string;
  changeType?: string;
  path?: string;
  backupPath?: string;
  diff?: string;
  addedLines?: number;
  deletedLines?: number;
  todoId?: string;
  todoOrder?: string;
  todoTitle?: string;
  createdAt?: string;
  reviewStatus?: string;
  supersededCount?: number;
};

export type FileReviewView = {
  change: FileChangeView;
  beforeContent?: string;
  afterContent?: string;
};

export type CommandRunView = {
  stepId?: string;
  toolId?: string;
  status?: string;
  command?: string;
  cwd?: string;
  exitCode?: number;
  riskLevel?: string;
  elapsedMs?: number;
  outputPreview?: string;
};

export type VerificationCommandView = {
  command?: string;
  cwd?: string;
  source?: string;
  reason?: string;
  alreadyRun?: boolean;
  lastStatus?: string;
  lastExitCode?: number;
  lastElapsedMs?: number;
};

export type FailureAnalysisView = {
  category?: string;
  summary?: string;
  command?: string;
  cwd?: string;
  retryable?: boolean;
  retryLimit?: number;
  nextAction?: string;
  evidence?: string;
};

export type FinalResultView = {
  outcome?: string;
  summary?: string;
  verificationStatus?: string;
  readyForCommit?: boolean;
  changedFiles?: number;
  commandsRun?: number;
  testsRun?: number;
  failedCommands?: number;
  riskCount?: number;
  remainingRisks?: string[];
  nextActions?: string[];
};

export type GitReviewView = {
  cwd?: string;
  statusCommand?: string;
  diffCommand?: string;
  statusAlreadyRun?: boolean;
  diffAlreadyRun?: boolean;
  statusExitCode?: number;
  diffExitCode?: number;
  statusOutputPreview?: string;
  diffOutputPreview?: string;
  nextAction?: string;
};

export type DevelopmentTaskSummary = {
  taskId: string;
  status?: string;
  fileChanges?: FileChangeView[];
  commands?: CommandRunView[];
  tests?: CommandRunView[];
  failures?: string[];
  risks?: string[];
  testCommandSuggestions?: string[];
  verificationPlan?: VerificationCommandView[];
  failureAnalyses?: FailureAnalysisView[];
  finalResult?: FinalResultView;
  gitReview?: GitReviewView;
  processes?: ManagedProcessView[];
  commitMessage?: string;
};

export type TaskAuditSummaryView = {
  toolCalls?: number;
  failedToolCalls?: number;
  approvalRequests?: number;
  approvalsGranted?: number;
  fileChanges?: number;
  rollbacks?: number;
  commands?: number;
  failedCommands?: number;
  securityWarnings?: number;
  events?: number;
};

export type TaskAuditResumeView = {
  resumed?: boolean;
  resumeFromTaskId?: string;
  resumeFromStatus?: string;
  todoId?: string;
  todoOrder?: string;
  todoTitle?: string;
  todoStatus?: string;
  resumeMode?: string;
  resumeInstruction?: string;
  checkpoint?: string;
  requestedAt?: string;
  checkpointAt?: string;
};

export type TaskAuditToolView = {
  stepId?: string;
  toolId?: string;
  status?: string;
  riskLevel?: string;
  approvalMode?: string;
  todoTitle?: string;
  inputPreview?: string;
  outputPreview?: string;
  error?: string;
  elapsedMs?: number;
  startedAt?: string;
  finishedAt?: string;
};

export type TaskAuditApprovalView = {
  stepId?: string;
  toolId?: string;
  approvalKey?: string;
  status?: string;
  reason?: string;
  requestedAt?: string;
  approvedAt?: string;
};

export type TaskAuditTimelineView = {
  id?: string;
  type?: string;
  level?: string;
  message?: string;
  toolId?: string;
  stepId?: string;
  todoTitle?: string;
  createdAt?: string;
};

export type TaskAuditView = {
  taskId: string;
  sessionId?: string;
  status?: string;
  input?: string;
  summary?: TaskAuditSummaryView;
  resume?: TaskAuditResumeView;
  tools?: TaskAuditToolView[];
  approvals?: TaskAuditApprovalView[];
  fileChanges?: FileChangeView[];
  commands?: CommandRunView[];
  timeline?: TaskAuditTimelineView[];
};

export type McpServerRegistration = {
  id?: string;
  name?: string;
  status?: string;
  connected?: boolean;
  enabled?: boolean;
  registeredAt?: string;
  config?: {
    id?: string;
    name?: string;
    transport?: string;
    type?: string;
    transportType?: string;
    endpoint?: string;
    command?: string;
    args?: string[];
    env?: Record<string, string>;
    headers?: Record<string, string>;
    cwd?: string;
    timeoutSeconds?: number;
    autoApprove?: string[];
    url?: string;
    disabled?: boolean;
    enabled?: boolean;
  };
  message?: string;
  tools?: unknown[];
};

export type McpServerConfig = {
  id?: string;
  name?: string;
  transport?: string;
  endpoint?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  cwd?: string;
  timeoutSeconds?: number;
  autoApprove?: string[];
  enabled?: boolean;
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
    entrypoint?: string;
    tools?: string[];
    permissions?: string[];
    metadata?: Record<string, unknown>;
  };
  installedAt?: string;
};

export type SkillInstallRequest = {
  manifest?: {
    id?: string;
    name?: string;
    version?: string;
    description?: string;
    enabled?: boolean;
    entrypoint?: string;
    tools?: string[];
    permissions?: string[];
    metadata?: Record<string, unknown>;
  };
  content?: string;
  resourceFiles?: Record<string, string>;
};

export type SkillImportRequest = {
  sourceUrl?: string;
  skillMd?: string;
  id?: string;
  name?: string;
  description?: string;
  overwrite?: boolean;
};

export type ModelSettings = {
  mode?: string;
  client?: string;
  defaultModel?: string;
  memoryModel?: string;
  visionModel?: string;
  planner?: string;
};

export type ModelConfigView = {
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKey?: string;
  apiKeyConfigured?: boolean;
  temperature?: number;
  timeoutSeconds?: number;
  vision?: boolean;
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
  embedding?: EmbeddingConfigView;
  memoryExtraction?: MemoryExtractionConfigView;
  memoryGovernance?: MemoryGovernanceConfigView;
  cost?: CostConfigView;
  local?: {
    workspaceRoot?: string;
    defaultShell?: string;
    permissionMode?: string;
    approvedToolIds?: string[];
    allowedRoots?: string[];
    recentProjects?: string[];
    testCommands?: string[];
    projectTestCommands?: Record<string, string[]>;
    ignorePatterns?: string[];
    sensitivePathPatterns?: string[];
  };
  policy?: PolicySnapshotView;
  auth?: AuthConfigView;
  models?: Record<string, ModelConfigView>;
};

export type AuthConfigView = {
  apiTokenRequired?: boolean;
  protectedPathPatterns?: string[];
  excludedPathPatterns?: string[];
};

export type ApprovalPolicyView = {
  mode?: string;
  approvedToolIds?: string[];
  autoApprovesHighRisk?: boolean;
  fullAccess?: boolean;
  requiresApprovalForMediumOrUnknown?: boolean;
  source?: string;
  scope?: string;
  resolutionOrder?: string;
  overrideReason?: string;
  conflictNotes?: string[];
};

export type PermissionPolicyView = {
  allowedRoots?: string[];
  sensitivePathPatterns?: string[];
  defaultCwd?: string;
  source?: string;
};

export type PolicyResolutionLayerView = {
  order?: number;
  key?: string;
  scope?: string;
  source?: string;
  status?: string;
  description?: string;
};

export type PolicySnapshotView = {
  approval?: ApprovalPolicyView;
  permission?: PermissionPolicyView;
  resolutionOrder?: PolicyResolutionLayerView[];
  effectiveRules?: string[];
  pendingEnhancements?: string[];
};

export type LocalHealthItemView = {
  key?: string;
  label?: string;
  status?: string;
  summary?: string;
  detail?: string;
};

export type LocalHealthView = {
  status?: string;
  items?: LocalHealthItemView[];
};

export type ManagedProcessView = {
  pid: number;
  status?: string;
  command?: string[];
  commandLine?: string;
  cwd?: string;
  logPath?: string;
  startedAt?: string;
  ports?: number[];
  portStatus?: Record<string, boolean>;
  persistent?: boolean;
  storePath?: string;
  logTail?: string;
  taskId?: string;
  sessionId?: string;
  projectPath?: string;
  health?: ProcessHealthView;
  diagnosis?: ProcessDiagnosisView;
};

export type ManagedProcessLogsView = {
  pid: number;
  logPath?: string;
  status?: string;
  logs?: string;
  health?: ProcessHealthView;
  diagnosis?: ProcessDiagnosisView;
};

export type ProcessHealthView = {
  url?: string;
  status?: string;
  httpStatus?: number;
  message?: string;
};

export type ProcessDiagnosisView = {
  status?: string;
  category?: string;
  summary?: string;
  nextAction?: string;
  evidence?: string[];
};

export type ModelConfigUpdate = {
  mode?: string;
  client?: string;
  defaultModel?: string;
  memoryModel?: string;
  visionModel?: string;
  planner?: string;
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKey?: string;
  temperature?: number;
  timeoutSeconds?: number;
  vision?: boolean;
  embeddingProvider?: string;
  embeddingBaseUrl?: string;
  embeddingModel?: string;
  embeddingApiKey?: string;
  embeddingDimensions?: number;
  embeddingTimeoutSeconds?: number;
  memoryExtractionEnabled?: boolean;
  memoryExtractionMode?: string;
  memoryExtractionIntervalSeconds?: number;
  memoryExtractionBatchSize?: number;
  memoryGovernanceStaleAfterDays?: number;
  memoryGovernanceVeryStaleAfterDays?: number;
  memoryGovernanceAutoArchiveEnabled?: boolean;
  memoryGovernanceArchiveAfterDays?: number;
  memoryGovernanceArchiveBelowQuality?: number;
  costCurrency?: string;
  costRules?: Record<string, CostRuleView>;
  localWorkspaceRoot?: string;
  localDefaultShell?: string;
  localPermissionMode?: string;
  localApprovedToolIds?: string[];
  localAllowedRoots?: string[];
  localRecentProjects?: string[];
  localTestCommands?: string[];
  localProjectTestCommands?: Record<string, string[]>;
  localIgnorePatterns?: string[];
  localSensitivePathPatterns?: string[];
};

export type PolicyConfigUpdate = {
  permissionMode?: string;
  approvedToolIds?: string[];
  workspaceRoot?: string;
  defaultShell?: string;
  allowedRoots?: string[];
  sensitivePathPatterns?: string[];
};

export type MemoryExtractionConfigView = {
  enabled?: boolean;
  mode?: string;
  intervalSeconds?: number;
  batchSize?: number;
};

export type MemoryGovernanceConfigView = {
  staleAfterDays?: number;
  veryStaleAfterDays?: number;
  autoArchiveEnabled?: boolean;
  archiveAfterDays?: number;
  archiveBelowQuality?: number;
};

export type EmbeddingConfigView = {
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKey?: string;
  apiKeyConfigured?: boolean;
  dimensions?: number;
  timeoutSeconds?: number;
};

export type ModelConfigUpsertRequest = {
  id: string;
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKey?: string;
  temperature?: number;
  timeoutSeconds?: number;
  vision?: boolean;
};

export type ModelApiTestRequest = {
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKey?: string;
  prompt?: string;
  temperature?: number;
  timeoutSeconds?: number;
};

export type ModelApiTestResponse = {
  success: boolean;
  statusCode?: number;
  message?: string;
  rawError?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  elapsedMs?: number;
};

export type VectorStatusView = {
  id: string;
  name?: string;
  status?: string;
  chunkCount?: number;
  vectorCount?: number;
  vectorized?: boolean;
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
  elapsedMs?: number;
  tokenCalls?: number;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  toolCalls?: number;
  failedToolCalls?: number;
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

export type MemoryItem = {
  id: string;
  userId?: string;
  scopeType?: 'global' | 'channel' | 'session' | string;
  scopeId?: string;
  type?: string;
  status?: 'pending' | 'active' | 'disabled' | 'archived' | string;
  content?: string;
  summary?: string;
  sourceSessionId?: string;
  sourceTaskId?: string;
  importance?: number;
  confidence?: number;
  metadata?: Record<string, string>;
  createdAt?: string;
  updatedAt?: string;
};

export type MemorySearchHit = {
  itemId?: string;
  chunkId?: string;
  userId?: string;
  scopeType?: string;
  scopeId?: string;
  type?: string;
  summary?: string;
  content?: string;
  score?: number;
  metadata?: Record<string, string>;
};

export type MemoryHitLog = {
  id: string;
  userId?: string;
  itemId?: string;
  sessionId?: string;
  taskId?: string;
  query?: string;
  score?: number;
  reason?: string;
  createdAt?: string;
};

export type MemorySearchResponse = {
  hits: MemorySearchHit[];
};

export type MemoryUpsertRequest = Partial<Omit<MemoryItem, 'createdAt' | 'updatedAt'>>;

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
