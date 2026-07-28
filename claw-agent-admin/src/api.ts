import type {
  AgentEvent,
  AgentMessage,
  AgentOrchestrationGraphView,
  AgentSession,
  AgentStep,
  AgentTask,
  ApiTokenCreateRequest,
  ApiTokenCreateResponse,
  ApiTokenView,
  AuthSetupView,
  AttachmentParseResponse,
  AutomationDefinition,
  AutomationRun,
  AutomationUpsertRequest,
  ChannelDefinition,
  ChannelAdapterDeleteResult,
  ChannelAdapterDescriptor,
  ChannelAdapterReloadResult,
  ChannelConnectivityStatus,
  ChannelInboundMessage,
  ChannelInboundResult,
  ChannelOutboundTestRequest,
  ChannelOutboundTestResponse,
  ChannelStreamStatus,
  ChannelUserBindingRequest,
  ChannelUserBindingView,
  DeviceRegisterRequest,
  DevicePairRequest,
  DevicePairResponse,
  DevicePairingCreateRequest,
  DevicePairingCodeResponse,
  DevicePermissionUpdateRequest,
  DeviceSecretRotateResponse,
  DeviceSecretVerifyRequest,
  DeviceSecretVerifyResponse,
  DeviceUserBindRequest,
  DeviceView,
  HealthStatus,
  LocalHealthView,
  LocalUserCreateRequest,
  LocalUserCurrentResponse,
  LocalUserLoginRequest,
  LocalUserLoginResponse,
  LocalUserPasswordChangeRequest,
  LocalUserPermissionUpdateRequest,
  LocalUserSessionView,
  LocalUserView,
  FileChangeView,
  FileReviewView,
  DevelopmentTaskSummary,
  KnowledgeDocument,
  KnowledgeProviderView,
  KnowledgeSearchResponse,
  MemoryHitLog,
  MemoryItem,
  MemorySearchResponse,
  MemoryUpsertRequest,
  ManagedProcessLogsView,
  ManagedProcessView,
  McpServerConfig,
  McpServerRegistration,
  ModelApiTestRequest,
  ModelApiTestResponse,
  ModelConfigUpsertRequest,
  ModelConfigUpdate,
  PlanCreateRequest,
  PlanDraft,
  PlanReviseRequest,
  PlanRevisionSummaryView,
  PlanRunRequest,
  PlanTemplateView,
  PolicyConfigUpdate,
  PolicyResolveRequest,
  PolicyResolveView,
  ResumeStateView,
  RuntimeConfigSnapshot,
  SessionContextClearRequest,
  SessionContextCommandResponse,
  SessionContextCompactRequest,
  SessionContextView,
  SessionRuntimeStatusView,
  SkillImportRequest,
  SkillInstallRequest,
  SkillRegistration,
  SubAgentBatchTaskRequest,
  SubAgentBatchTaskResponse,
  SubAgentPlanDispatchRequest,
  SubAgentTaskRequest,
  SubAgentTaskResponse,
  SystemLogLine,
  SystemLogSource,
  TaskAuditView,
  TodoItem,
  TokenUsageSummary,
  ToolDefinition,
  VectorStatusView,
} from './types';

type RequestOptions = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
};

const AUTH_SESSION_STORAGE_KEY = 'clawagent.auth.sessionToken';

function authHeaders(sessionToken?: string): Record<string, string> {
  const token = (sessionToken || window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY) || '').trim();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: {
      Accept: 'application/json',
      ...authHeaders(),
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(options.headers || {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${response.status} ${response.statusText}: ${body || 'empty body'}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  health: () => requestJson<HealthStatus>('/api/v1/health'),
  channels: () => requestJson<ChannelDefinition[]>('/api/v1/channels'),
  channelAdapters: () => requestJson<ChannelAdapterDescriptor[]>('/api/v1/channels/adapters'),
  reloadChannelAdapters: () =>
    requestJson<ChannelAdapterReloadResult>('/api/v1/channels/adapters/reload', { method: 'POST' }),
  uploadChannelAdapter: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return fetch('/api/v1/channels/adapters/upload', { method: 'POST', headers: authHeaders(), body: form }).then(async (response) => {
      if (!response.ok) {
        const body = await response.text();
        throw new Error(`${response.status} ${response.statusText}: ${body || 'empty body'}`);
      }
      return response.json() as Promise<ChannelAdapterReloadResult>;
    });
  },
  deleteChannelAdapter: (filename: string) =>
    requestJson<ChannelAdapterDeleteResult>(`/api/v1/channels/adapters/${encodeURIComponent(filename)}`, { method: 'DELETE' }),
  channel: (channelId: string) =>
    requestJson<ChannelDefinition>(`/api/v1/channels/${encodeURIComponent(channelId)}`),
  saveChannel: (body: ChannelDefinition) =>
    body.id
      ? requestJson<ChannelDefinition>(`/api/v1/channels/${encodeURIComponent(body.id)}`, { method: 'PUT', body })
      : requestJson<ChannelDefinition>('/api/v1/channels', { method: 'POST', body }),
  deleteChannel: (channelId: string) =>
    requestJson<{ deleted: boolean; channelId: string }>(`/api/v1/channels/${encodeURIComponent(channelId)}`, { method: 'DELETE' }),
  checkChannelHealth: (channelId: string) =>
    requestJson<ChannelConnectivityStatus>(`/api/v1/channels/${encodeURIComponent(channelId)}/health`, { method: 'POST' }),
  channelStreamStatus: (channelId: string) =>
    requestJson<ChannelStreamStatus>(`/api/v1/channels/${encodeURIComponent(channelId)}/stream/status`),
  startChannelStream: (channelId: string) =>
    requestJson<ChannelStreamStatus>(`/api/v1/channels/${encodeURIComponent(channelId)}/stream/start`, { method: 'POST' }),
  stopChannelStream: (channelId: string) =>
    requestJson<ChannelStreamStatus>(`/api/v1/channels/${encodeURIComponent(channelId)}/stream/stop`, { method: 'POST' }),
  channelUserBindings: (channelId: string) =>
    requestJson<ChannelUserBindingView[]>(`/api/v1/channels/${encodeURIComponent(channelId)}/users`),
  bindChannelUser: (channelId: string, body: ChannelUserBindingRequest) =>
    requestJson<ChannelUserBindingView>(`/api/v1/channels/${encodeURIComponent(channelId)}/users`, { method: 'POST', body }),
  unbindChannelUser: (channelId: string, externalUserId: string) =>
    requestJson<{ channelId: string; externalUserId: string; unbound: boolean }>(
      `/api/v1/channels/${encodeURIComponent(channelId)}/users?externalUserId=${encodeURIComponent(externalUserId)}`,
      { method: 'DELETE' },
    ),
  submitChannelMessage: (body: ChannelInboundMessage) =>
    requestJson<ChannelInboundResult>('/api/v1/channels/inbound', { method: 'POST', body }),
  testChannelOutbound: (channelId: string, body: ChannelOutboundTestRequest) =>
    requestJson<ChannelOutboundTestResponse>(`/api/v1/channels/${encodeURIComponent(channelId)}/outbound/test`, { method: 'POST', body }),
  apiTokens: () => requestJson<ApiTokenView[]>('/api/v1/auth/tokens'),
  createApiToken: (body: ApiTokenCreateRequest) =>
    requestJson<ApiTokenCreateResponse>('/api/v1/auth/tokens', { method: 'POST', body }),
  deleteApiToken: (tokenId: string) =>
    requestJson<ApiTokenView>(`/api/v1/auth/tokens/${encodeURIComponent(tokenId)}`, { method: 'DELETE' }),
  loginLocalUser: (body: LocalUserLoginRequest) =>
    requestJson<LocalUserLoginResponse>('/api/v1/auth/login', { method: 'POST', body }),
  currentLocalUser: (sessionToken: string) =>
    requestJson<LocalUserCurrentResponse>('/api/v1/auth/me', {
      headers: { Authorization: `Bearer ${sessionToken}` },
    }),
  logoutLocalUser: (sessionToken: string) =>
    requestJson<{ success: boolean }>('/api/v1/auth/logout', {
      method: 'POST',
      headers: { Authorization: `Bearer ${sessionToken}` },
    }),
  authSetupStatus: () => requestJson<AuthSetupView>('/api/v1/auth/setup'),
  setupOwner: (body: LocalUserCreateRequest) =>
    requestJson<LocalUserView>('/api/v1/auth/setup', { method: 'POST', body }),
  localUsers: () => requestJson<LocalUserView[]>('/api/v1/auth/users'),
  createLocalUser: (body: LocalUserCreateRequest) =>
    requestJson<LocalUserView>('/api/v1/auth/users', { method: 'POST', body }),
  changeLocalUserPassword: (userId: string, body: LocalUserPasswordChangeRequest) =>
    requestJson<LocalUserView>(`/api/v1/auth/users/${encodeURIComponent(userId)}/password`, { method: 'POST', body }),
  updateLocalUserPermissions: (userId: string, body: LocalUserPermissionUpdateRequest) =>
    requestJson<LocalUserView>(`/api/v1/auth/users/${encodeURIComponent(userId)}/permissions`, { method: 'POST', body }),
  disableLocalUser: (userId: string) =>
    requestJson<LocalUserView>(`/api/v1/auth/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
  localUserSessions: () => requestJson<LocalUserSessionView[]>('/api/v1/auth/sessions'),
  revokeLocalUserSession: (sessionId: string) =>
    requestJson<LocalUserSessionView>(`/api/v1/auth/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }),
  devices: () => requestJson<DeviceView[]>('/api/v1/auth/devices'),
  registerDevice: (body: DeviceRegisterRequest) =>
    requestJson<DeviceView>('/api/v1/auth/devices', { method: 'POST', body }),
  createDevicePairingCode: (body: DevicePairingCreateRequest) =>
    requestJson<DevicePairingCodeResponse>('/api/v1/auth/devices/pairing-codes', { method: 'POST', body }),
  pairDevice: (body: DevicePairRequest) =>
    requestJson<DevicePairResponse>('/api/v1/auth/devices/pair', { method: 'POST', body }),
  heartbeatDevice: (deviceId: string) =>
    requestJson<DeviceView>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}/heartbeat`, { method: 'POST' }),
  verifyDeviceSecret: (deviceId: string, body: DeviceSecretVerifyRequest) =>
    requestJson<DeviceSecretVerifyResponse>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}/verify`, { method: 'POST', body }),
  rotateDeviceSecret: (deviceId: string) =>
    requestJson<DeviceSecretRotateResponse>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}/secret/rotate`, { method: 'POST' }),
  bindDeviceUser: (deviceId: string, body: DeviceUserBindRequest) =>
    requestJson<DeviceView>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}/user`, { method: 'POST', body }),
  updateDevicePermissions: (deviceId: string, body: DevicePermissionUpdateRequest) =>
    requestJson<DeviceView>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}/permissions`, { method: 'POST', body }),
  revokeDevice: (deviceId: string) =>
    requestJson<DeviceView>(`/api/v1/auth/devices/${encodeURIComponent(deviceId)}`, { method: 'DELETE' }),
  createSessionId: () => requestJson<{ sessionId: string }>('/api/v1/sessions/id', { method: 'POST' }),
  submitStream: (body: {
    input: string;
    sessionId?: string;
    channelId: string;
    userId: string;
    metadata: Record<string, unknown>;
  }, signal?: AbortSignal, sessionToken?: string) =>
    fetch('/api/v1/tasks/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders(sessionToken) },
      body: JSON.stringify(body),
      signal,
    }),
  resumeStream: (taskId: string, body: {
    input?: string;
    channelId: string;
    userId: string;
    metadata: Record<string, unknown>;
  }, signal?: AbortSignal, sessionToken?: string) =>
    fetch(`/api/v1/tasks/${encodeURIComponent(taskId)}/resume/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders(sessionToken) },
      body: JSON.stringify(body),
      signal,
    }),
  plans: (sessionId?: string, limit = 100) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (sessionId) params.set('sessionId', sessionId);
    return requestJson<PlanDraft[]>(`/api/v1/plans?${params.toString()}`);
  },
  planTemplates: () => requestJson<PlanTemplateView[]>('/api/v1/plans/templates'),
  plan: (planId: string) => requestJson<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}`),
  planRevisionSummary: (planId: string) =>
    requestJson<PlanRevisionSummaryView | null>(`/api/v1/plans/${encodeURIComponent(planId)}/revision-summary`),
  createPlan: (body: PlanCreateRequest) =>
    requestJson<PlanDraft>('/api/v1/plans', { method: 'POST', body }),
  revisePlan: (planId: string, body: PlanReviseRequest) =>
    requestJson<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/revise`, { method: 'POST', body }),
  approvePlan: (planId: string) =>
    requestJson<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/approve`, { method: 'POST' }),
  cancelPlan: (planId: string) =>
    requestJson<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/cancel`, { method: 'POST' }),
  runPlanStream: (planId: string, body: PlanRunRequest, signal?: AbortSignal, sessionToken?: string) =>
    fetch(`/api/v1/plans/${encodeURIComponent(planId)}/run/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders(sessionToken) },
      body: JSON.stringify(body),
      signal,
    }),
  parseAttachments: (files: File[], userId = 'console', signal?: AbortSignal) => {
    const form = new FormData();
    files.forEach((file) => form.append('files', file));
    form.append('userId', userId);
    return fetch('/api/v1/attachments/parse', {
      method: 'POST',
      body: form,
      signal,
    }).then(async (response) => {
      if (!response.ok) {
        const body = await response.text();
        throw new Error(`${response.status} ${response.statusText}: ${body || 'empty body'}`);
      }
      return response.json() as Promise<AttachmentParseResponse>;
    });
  },
  knowledgeProviders: () => requestJson<KnowledgeProviderView[]>('/api/v1/knowledge/providers'),
  knowledgeDocuments: (userId = 'console', limit = 100) =>
    requestJson<KnowledgeDocument[]>(`/api/v1/knowledge/documents?userId=${encodeURIComponent(userId)}&limit=${limit}`),
  knowledgeVectorStatus: (userId = 'console') =>
    requestJson<VectorStatusView[]>(`/api/v1/knowledge/vector-status?userId=${encodeURIComponent(userId)}`),
  uploadKnowledgeDocuments: (files: File[], userId = 'console', signal?: AbortSignal) => {
    const form = new FormData();
    files.forEach((file) => form.append('files', file));
    form.append('userId', userId);
    return fetch('/api/v1/knowledge/documents/upload', {
      method: 'POST',
      body: form,
      signal,
    }).then(async (response) => {
      if (!response.ok) {
        const body = await response.text();
        throw new Error(`${response.status} ${response.statusText}: ${body || 'empty body'}`);
      }
      return response.json() as Promise<KnowledgeDocument[]>;
    });
  },
  deleteKnowledgeDocument: (documentId: string, userId = 'console') =>
    requestJson<{ deleted: boolean; documentId: string }>(
      `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}?userId=${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
    ),
  knowledgeDownloadUrl: (documentId: string, userId = 'console') =>
    `/api/v1/knowledge/documents/${encodeURIComponent(documentId)}/download?userId=${encodeURIComponent(userId)}`,
  searchKnowledge: (body: {
    userId?: string;
    query: string;
    documentIds?: string[];
    mode?: 'keyword' | 'vector' | 'hybrid';
    topK?: number;
  }) => requestJson<KnowledgeSearchResponse>('/api/v1/knowledge/search', { method: 'POST', body }),
  memoryProvider: () => requestJson<{ id: string; capabilities?: Record<string, unknown> }>('/api/v1/memory/provider'),
  memoryVectorStatus: (userId = 'console') =>
    requestJson<VectorStatusView[]>(`/api/v1/memory/vector-status?userId=${encodeURIComponent(userId)}`),
  memoryItems: (params: { userId?: string; scopeType?: string; status?: string; limit?: number } = {}) => {
    const search = new URLSearchParams({ userId: params.userId || 'console', limit: String(params.limit || 100) });
    if (params.scopeType) search.set('scopeType', params.scopeType);
    if (params.status) search.set('status', params.status);
    return requestJson<MemoryItem[]>(`/api/v1/memory/items?${search.toString()}`);
  },
  createMemoryItem: (body: MemoryUpsertRequest) =>
    requestJson<MemoryItem>('/api/v1/memory/items', { method: 'POST', body }),
  updateMemoryItem: (itemId: string, body: MemoryUpsertRequest) =>
    requestJson<MemoryItem>(`/api/v1/memory/items/${encodeURIComponent(itemId)}`, { method: 'PUT', body }),
  deleteMemoryItem: (itemId: string, userId = 'console') =>
    requestJson<{ deleted: boolean; itemId: string }>(
      `/api/v1/memory/items/${encodeURIComponent(itemId)}?userId=${encodeURIComponent(userId)}`,
      { method: 'DELETE' },
    ),
  enableMemoryItem: (itemId: string, userId = 'console') =>
    requestJson<MemoryItem>(`/api/v1/memory/items/${encodeURIComponent(itemId)}/enable?userId=${encodeURIComponent(userId)}`, { method: 'POST' }),
  disableMemoryItem: (itemId: string, userId = 'console') =>
    requestJson<MemoryItem>(`/api/v1/memory/items/${encodeURIComponent(itemId)}/disable?userId=${encodeURIComponent(userId)}`, { method: 'POST' }),
  archiveMemoryItem: (itemId: string, userId = 'console') =>
    requestJson<MemoryItem>(`/api/v1/memory/items/${encodeURIComponent(itemId)}/archive?userId=${encodeURIComponent(userId)}`, { method: 'POST' }),
  memoryCandidates: (userId = 'console', limit = 100) =>
    requestJson<MemoryItem[]>(`/api/v1/memory/candidates?userId=${encodeURIComponent(userId)}&limit=${limit}`),
  acceptMemoryCandidate: (itemId: string, userId = 'console') =>
    requestJson<MemoryItem>(`/api/v1/memory/candidates/${encodeURIComponent(itemId)}/accept?userId=${encodeURIComponent(userId)}`, { method: 'POST' }),
  rejectMemoryCandidate: (itemId: string, userId = 'console') =>
    requestJson<MemoryItem>(`/api/v1/memory/candidates/${encodeURIComponent(itemId)}/reject?userId=${encodeURIComponent(userId)}`, { method: 'POST' }),
  searchMemory: (body: {
    userId?: string;
    query: string;
    scopeTypes?: string[];
    scopeId?: string;
    statuses?: string[];
    mode?: 'keyword' | 'vector' | 'hybrid';
    topK?: number;
  }) => requestJson<MemorySearchResponse>('/api/v1/memory/search', { method: 'POST', body }),
  memoryHits: (params: { userId?: string; sessionId?: string; taskId?: string; limit?: number } = {}) => {
    const search = new URLSearchParams({ userId: params.userId || 'console', limit: String(params.limit || 100) });
    if (params.sessionId) search.set('sessionId', params.sessionId);
    if (params.taskId) search.set('taskId', params.taskId);
    return requestJson<MemoryHitLog[]>(`/api/v1/memory/hits?${search.toString()}`);
  },
  attachmentDownloadUrl: (attachmentId: string) =>
    `/api/v1/attachments/${encodeURIComponent(attachmentId)}/download`,
  attachmentViewUrl: (attachmentId: string) =>
    `/api/v1/attachments/${encodeURIComponent(attachmentId)}/view`,
  cancelTask: (taskId: string) =>
    requestJson<AgentTask>(`/api/v1/tasks/${encodeURIComponent(taskId)}/cancel`, { method: 'POST' }),
  approveToolCall: (taskId: string, stepId: string, toolId: string) =>
    requestJson<AgentTask>(
      `/api/v1/tasks/${encodeURIComponent(taskId)}/approvals/${encodeURIComponent(stepId)}/approve`,
      { method: 'POST', body: { toolId } },
    ),
  rejectToolCall: (taskId: string, stepId: string, toolId: string, reason = '用户拒绝审批') =>
    requestJson<AgentTask>(
      `/api/v1/tasks/${encodeURIComponent(taskId)}/approvals/${encodeURIComponent(stepId)}/reject`,
      { method: 'POST', body: { toolId, reason } },
    ),
  taskEvents: (taskId: string, limit = 200, todoId?: string, stepId?: string) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (todoId) params.set('todoId', todoId);
    if (stepId) params.set('stepId', stepId);
    return requestJson<AgentEvent[]>(`/api/v1/tasks/${encodeURIComponent(taskId)}/events?${params.toString()}`);
  },
  taskMessages: (taskId: string, limit = 100) =>
    requestJson<AgentMessage[]>(`/api/v1/tasks/${encodeURIComponent(taskId)}/messages?limit=${limit}`),
  task: (taskId: string) => requestJson<AgentTask>(`/api/v1/tasks/${encodeURIComponent(taskId)}`),
  searchTasks: (params: {
    query?: string;
    status?: string;
    channelId?: string;
    userId?: string;
    sessionId?: string;
    limit?: number;
  } = {}) => {
    const search = new URLSearchParams({ limit: String(params.limit || 100) });
    if (params.query) search.set('query', params.query);
    if (params.status) search.set('status', params.status);
    if (params.channelId) search.set('channelId', params.channelId);
    if (params.userId) search.set('userId', params.userId);
    if (params.sessionId) search.set('sessionId', params.sessionId);
    return requestJson<AgentTask[]>(`/api/v1/tasks/search?${search.toString()}`);
  },
  searchSteps: (params: {
    query?: string;
    status?: string;
    taskId?: string;
    toolId?: string;
    riskLevel?: string;
    limit?: number;
  } = {}) => {
    const search = new URLSearchParams({ limit: String(params.limit || 100) });
    if (params.query) search.set('query', params.query);
    if (params.status) search.set('status', params.status);
    if (params.taskId) search.set('taskId', params.taskId);
    if (params.toolId) search.set('toolId', params.toolId);
    if (params.riskLevel) search.set('riskLevel', params.riskLevel);
    return requestJson<AgentStep[]>(`/api/v1/steps/search?${search.toString()}`);
  },
  taskResumeState: (taskId: string) =>
    requestJson<ResumeStateView>(`/api/v1/tasks/${encodeURIComponent(taskId)}/resume-state`),
  taskTokenUsage: (taskId: string) =>
    requestJson<TokenUsageSummary>(`/api/v1/tasks/${encodeURIComponent(taskId)}/token-usage`),
  taskFileChanges: (taskId: string, limit = 100) =>
    requestJson<FileChangeView[]>(`/api/v1/tasks/${encodeURIComponent(taskId)}/file-changes?limit=${limit}`),
  developmentSummary: (taskId: string) =>
    requestJson<DevelopmentTaskSummary>(`/api/v1/tasks/${encodeURIComponent(taskId)}/development-summary`),
  taskAudit: (taskId: string) =>
    requestJson<TaskAuditView>(`/api/v1/tasks/${encodeURIComponent(taskId)}/audit`),
  subAgentTasks: (taskId: string, limit = 100) =>
    requestJson<AgentTask[]>(`/api/v1/agents/${encodeURIComponent(taskId)}/subtasks?limit=${limit}`),
  agentOrchestrationGraph: (taskId: string, depth = 3) =>
    requestJson<AgentOrchestrationGraphView>(`/api/v1/agents/${encodeURIComponent(taskId)}/graph?depth=${depth}`),
  createSubAgentTask: (taskId: string, body: SubAgentTaskRequest) =>
    requestJson<SubAgentTaskResponse>(`/api/v1/agents/${encodeURIComponent(taskId)}/subtasks`, { method: 'POST', body }),
  createSubAgentTasks: (taskId: string, body: SubAgentBatchTaskRequest) =>
    requestJson<SubAgentBatchTaskResponse>(`/api/v1/agents/${encodeURIComponent(taskId)}/subtasks/batch`, { method: 'POST', body }),
  createSubAgentTasksFromPlan: (taskId: string, body: SubAgentPlanDispatchRequest) =>
    requestJson<SubAgentBatchTaskResponse>(`/api/v1/agents/${encodeURIComponent(taskId)}/subtasks/from-plan`, { method: 'POST', body }),
  fileReview: (taskId: string, change: FileChangeView) => {
    const params = new URLSearchParams({
      stepId: change.stepId || '',
      path: change.path || '',
    });
    if (change.backupPath) params.set('backupPath', change.backupPath);
    return requestJson<FileReviewView>(`/api/v1/tasks/${encodeURIComponent(taskId)}/file-review?${params.toString()}`);
  },
  openTaskFile: (taskId: string, change: FileChangeView, action: 'vscode' | 'explorer') =>
    requestJson<Record<string, string>>(`/api/v1/tasks/${encodeURIComponent(taskId)}/open-file`, {
      method: 'POST',
      body: {
        stepId: change.stepId || '',
        path: change.path || '',
        action,
      },
    }),
  rollbackTaskFile: (taskId: string, change: FileChangeView) =>
    requestJson<FileReviewView>(`/api/v1/tasks/${encodeURIComponent(taskId)}/rollback-file`, {
      method: 'POST',
      body: {
        stepId: change.stepId || '',
        path: change.path || '',
      },
    }),
  rollbackTaskFileSelection: (
    taskId: string,
    change: FileChangeView,
    selection: { startLine: number; endLine: number; selectedText: string; base?: 'current' | 'before'; insertAfterLine?: number }
  ) =>
    requestJson<FileReviewView>(`/api/v1/tasks/${encodeURIComponent(taskId)}/rollback-file-selection`, {
      method: 'POST',
      body: {
        stepId: change.stepId || '',
        path: change.path || '',
        backupPath: change.backupPath || '',
        startLine: selection.startLine,
        endLine: selection.endLine,
        selectedText: selection.selectedText,
        base: selection.base || 'current',
        insertAfterLine: selection.insertAfterLine,
      },
    }),
  todos: (sessionId?: string, taskId?: string, limit = 100) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (sessionId) params.set('sessionId', sessionId);
    if (taskId) params.set('taskId', taskId);
    return requestJson<TodoItem[]>(`/api/v1/todos?${params.toString()}`);
  },
  sessions: (limit = 30) => requestJson<AgentSession[]>(`/api/v1/sessions?limit=${limit}`),
  sessionTasks: (sessionId: string, limit = 50) =>
    requestJson<AgentTask[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/tasks?limit=${limit}`),
  sessionMessages: (sessionId: string, limit = 100, before?: string) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (before) params.set('before', before);
    return requestJson<AgentMessage[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?${params.toString()}`);
  },
  sessionEvents: (sessionId: string, limit = 100) =>
    requestJson<AgentEvent[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events?limit=${limit}`),
  sessionTokenUsage: (sessionId: string, limit = 1000) =>
    requestJson<TokenUsageSummary>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/token-usage?limit=${limit}`),
  clearSessionContext: (sessionId: string, body: SessionContextClearRequest = {}) =>
    requestJson<SessionContextCommandResponse>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/context/clear`, {
      method: 'POST',
      body,
    }),
  compactSessionContext: (sessionId: string, body: SessionContextCompactRequest = {}) =>
    requestJson<SessionContextCommandResponse>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/context/compact`, {
      method: 'POST',
      body,
    }),
  sessionContext: (sessionId: string) =>
    requestJson<SessionContextView>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/context`),
  sessionRuntimeStatus: (sessionId: string) =>
    requestJson<SessionRuntimeStatusView>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/runtime-status`),
  queryLogs: (params: {
    from?: string;
    to?: string;
    level?: string;
    keyword?: string;
    logger?: string;
    userId?: string;
    sessionId?: string;
    taskId?: string;
    limit?: number;
  }) => {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value).trim()) {
        search.set(key, String(value));
      }
    });
    return requestJson<SystemLogLine[]>(`/api/v1/logs/query?${search.toString()}`);
  },
  logSources: () => requestJson<SystemLogSource[]>('/api/v1/logs/sources'),
  auditEvents: (params: {
    from?: string;
    to?: string;
    level?: string;
    type?: string;
    sessionId?: string;
    taskId?: string;
    userId?: string;
    channelId?: string;
    toolId?: string;
    riskLevel?: string;
    detailKey?: string;
    detailValue?: string;
    q?: string;
    limit?: number;
  }) => {
    const search = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
      if (value !== undefined && value !== null && String(value).trim()) {
        search.set(key, String(value));
      }
    });
    return requestJson<AgentEvent[]>(`/api/v1/audit/events?${search.toString()}`);
  },
  tools: () => requestJson<ToolDefinition[]>('/api/v1/tools'),
  mcpServers: () => requestJson<McpServerRegistration[]>('/api/v1/mcp/servers'),
  importMcpServers: (json: string) =>
    requestJson<McpServerRegistration[]>('/api/v1/mcp/import', { method: 'POST', body: { json } }),
  registerMcpServer: (body: McpServerConfig) =>
    requestJson<McpServerRegistration>('/api/v1/mcp/servers', { method: 'POST', body }),
  updateMcpServer: (serverId: string, body: McpServerConfig) =>
    requestJson<McpServerRegistration>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}`, { method: 'PUT', body }),
  deleteMcpServer: (serverId: string) =>
    requestJson<{ deleted: boolean; serverId: string }>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}`, { method: 'DELETE' }),
  testMcpServer: (body: McpServerConfig) =>
    requestJson<McpServerRegistration>('/api/v1/mcp/servers/test', { method: 'POST', body }),
  connectMcpServer: (serverId: string) =>
    requestJson<McpServerRegistration>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/connect`, { method: 'POST' }),
  disconnectMcpServer: (serverId: string) =>
    requestJson<McpServerRegistration>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/disconnect`, { method: 'POST' }),
  refreshMcpTools: (serverId: string) =>
    requestJson<unknown[]>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/refresh-tools`, { method: 'POST' }),
  skills: () => requestJson<SkillRegistration[]>('/api/v1/skills'),
  refreshSkills: () =>
    requestJson<SkillRegistration[]>('/api/v1/skills/refresh', { method: 'POST' }),
  importSkill: (body: SkillImportRequest) =>
    requestJson<SkillRegistration[]>('/api/v1/skills/import', { method: 'POST', body }),
  installSkill: (body: SkillInstallRequest) =>
    requestJson<SkillRegistration>('/api/v1/skills', { method: 'POST', body }),
  updateSkill: (skillId: string, body: SkillInstallRequest) =>
    requestJson<SkillRegistration>(`/api/v1/skills/${encodeURIComponent(skillId)}`, { method: 'PUT', body }),
  enableSkill: (skillId: string) =>
    requestJson<SkillRegistration>(`/api/v1/skills/${encodeURIComponent(skillId)}/enable`, { method: 'POST' }),
  disableSkill: (skillId: string) =>
    requestJson<SkillRegistration>(`/api/v1/skills/${encodeURIComponent(skillId)}/disable`, { method: 'POST' }),
  deleteSkill: (skillId: string) =>
    requestJson<{ deleted: boolean; skillId: string }>(`/api/v1/skills/${encodeURIComponent(skillId)}`, { method: 'DELETE' }),
  automations: (limit = 100) => requestJson<AutomationDefinition[]>(`/api/v1/automations?limit=${limit}`),
  automation: (automationId: string) =>
    requestJson<AutomationDefinition>(`/api/v1/automations/${encodeURIComponent(automationId)}`),
  createAutomation: (body: AutomationUpsertRequest) =>
    requestJson<AutomationDefinition>('/api/v1/automations', { method: 'POST', body }),
  updateAutomation: (automationId: string, body: AutomationUpsertRequest) =>
    requestJson<AutomationDefinition>(`/api/v1/automations/${encodeURIComponent(automationId)}`, { method: 'PUT', body }),
  deleteAutomation: (automationId: string) =>
    requestJson<{ deleted: boolean; automationId: string }>(`/api/v1/automations/${encodeURIComponent(automationId)}`, { method: 'DELETE' }),
  enableAutomation: (automationId: string) =>
    requestJson<AutomationDefinition>(`/api/v1/automations/${encodeURIComponent(automationId)}/enable`, { method: 'POST' }),
  pauseAutomation: (automationId: string) =>
    requestJson<AutomationDefinition>(`/api/v1/automations/${encodeURIComponent(automationId)}/pause`, { method: 'POST' }),
  runAutomation: (automationId: string) =>
    requestJson<AutomationDefinition>(`/api/v1/automations/${encodeURIComponent(automationId)}/run`, { method: 'POST' }),
  automationRuns: (automationId: string, limit = 50) =>
    requestJson<AutomationRun[]>(`/api/v1/automations/${encodeURIComponent(automationId)}/runs?limit=${limit}`),
  runtimeConfig: () => requestJson<RuntimeConfigSnapshot>('/api/v1/config/runtime'),
  localHealth: (deep = false) =>
    requestJson<LocalHealthView>(`/api/v1/config/local/health?deep=${deep ? 'true' : 'false'}`),
  rememberRecentProject: (path: string) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/local/recent-projects', { method: 'POST', body: { path } }),
  processes: (logChars = 4000) =>
    requestJson<ManagedProcessView[]>(`/api/v1/processes?logChars=${logChars}`),
  processLogs: (pid: number, maxChars = 12000) =>
    requestJson<ManagedProcessLogsView>(`/api/v1/processes/${encodeURIComponent(String(pid))}/logs?maxChars=${maxChars}`),
  stopProcess: (pid: number, force = false) =>
    requestJson<ManagedProcessView>(`/api/v1/processes/${encodeURIComponent(String(pid))}/stop`, {
      method: 'POST',
      body: { force },
    }),
  saveModelConfig: (body: ModelConfigUpdate) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/model', { method: 'PUT', body }),
  savePolicyConfig: (body: PolicyConfigUpdate) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/policy', { method: 'PUT', body }),
  resolvePolicy: (body: PolicyResolveRequest) =>
    requestJson<PolicyResolveView>('/api/v1/config/policy/resolve', { method: 'POST', body }),
  saveModelDefinition: (body: ModelConfigUpsertRequest) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/models', { method: 'POST', body }),
  testModelApi: (body: ModelApiTestRequest) =>
    requestJson<ModelApiTestResponse>('/api/v1/config/model/test', { method: 'POST', body }),
};
