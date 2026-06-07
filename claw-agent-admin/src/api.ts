import type {
  AgentEvent,
  AgentMessage,
  AgentSession,
  AgentTask,
  AttachmentParseResponse,
  AutomationDefinition,
  AutomationRun,
  AutomationUpsertRequest,
  HealthStatus,
  KnowledgeDocument,
  KnowledgeProviderView,
  KnowledgeSearchResponse,
  MemoryHitLog,
  MemoryItem,
  MemorySearchResponse,
  MemoryUpsertRequest,
  McpServerConfig,
  McpServerRegistration,
  ModelApiTestRequest,
  ModelApiTestResponse,
  ModelConfigUpsertRequest,
  ModelConfigUpdate,
  RuntimeConfigSnapshot,
  SkillInstallRequest,
  SkillRegistration,
  SystemLogLine,
  SystemLogSource,
  TodoItem,
  TokenUsageSummary,
  ToolDefinition,
  VectorStatusView,
} from './types';

type RequestOptions = {
  method?: string;
  body?: unknown;
};

async function requestJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await fetch(path, {
    method: options.method || 'GET',
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
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
  createSessionId: () => requestJson<{ sessionId: string }>('/api/v1/sessions/id', { method: 'POST' }),
  submitStream: (body: {
    input: string;
    sessionId?: string;
    channelId: string;
    userId: string;
    metadata: Record<string, unknown>;
  }, signal?: AbortSignal) =>
    fetch('/api/v1/tasks/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
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
  taskEvents: (taskId: string, limit = 200, todoId?: string, stepId?: string) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (todoId) params.set('todoId', todoId);
    if (stepId) params.set('stepId', stepId);
    return requestJson<AgentEvent[]>(`/api/v1/tasks/${encodeURIComponent(taskId)}/events?${params.toString()}`);
  },
  taskMessages: (taskId: string, limit = 100) =>
    requestJson<AgentMessage[]>(`/api/v1/tasks/${encodeURIComponent(taskId)}/messages?limit=${limit}`),
  task: (taskId: string) => requestJson<AgentTask>(`/api/v1/tasks/${encodeURIComponent(taskId)}`),
  taskTokenUsage: (taskId: string) =>
    requestJson<TokenUsageSummary>(`/api/v1/tasks/${encodeURIComponent(taskId)}/token-usage`),
  todos: (sessionId?: string, taskId?: string, limit = 100) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (sessionId) params.set('sessionId', sessionId);
    if (taskId) params.set('taskId', taskId);
    return requestJson<TodoItem[]>(`/api/v1/todos?${params.toString()}`);
  },
  sessions: (limit = 30) => requestJson<AgentSession[]>(`/api/v1/sessions?limit=${limit}`),
  sessionTasks: (sessionId: string, limit = 50) =>
    requestJson<AgentTask[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/tasks?limit=${limit}`),
  sessionMessages: (sessionId: string, limit = 100) =>
    requestJson<AgentMessage[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/messages?limit=${limit}`),
  sessionEvents: (sessionId: string, limit = 100) =>
    requestJson<AgentEvent[]>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/events?limit=${limit}`),
  sessionTokenUsage: (sessionId: string, limit = 1000) =>
    requestJson<TokenUsageSummary>(`/api/v1/sessions/${encodeURIComponent(sessionId)}/token-usage?limit=${limit}`),
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
  tools: () => requestJson<ToolDefinition[]>('/api/v1/tools'),
  mcpServers: () => requestJson<McpServerRegistration[]>('/api/v1/mcp/servers'),
  importMcpServers: (json: string) =>
    requestJson<McpServerRegistration[]>('/api/v1/mcp/import', { method: 'POST', body: { json } }),
  registerMcpServer: (body: McpServerConfig) =>
    requestJson<McpServerRegistration>('/api/v1/mcp/servers', { method: 'POST', body }),
  testMcpServer: (body: McpServerConfig) =>
    requestJson<McpServerRegistration>('/api/v1/mcp/servers/test', { method: 'POST', body }),
  connectMcpServer: (serverId: string) =>
    requestJson<McpServerRegistration>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/connect`, { method: 'POST' }),
  disconnectMcpServer: (serverId: string) =>
    requestJson<McpServerRegistration>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/disconnect`, { method: 'POST' }),
  refreshMcpTools: (serverId: string) =>
    requestJson<unknown[]>(`/api/v1/mcp/servers/${encodeURIComponent(serverId)}/refresh-tools`, { method: 'POST' }),
  skills: () => requestJson<SkillRegistration[]>('/api/v1/skills'),
  installSkill: (body: SkillInstallRequest) =>
    requestJson<SkillRegistration>('/api/v1/skills', { method: 'POST', body }),
  enableSkill: (skillId: string) =>
    requestJson<SkillRegistration>(`/api/v1/skills/${encodeURIComponent(skillId)}/enable`, { method: 'POST' }),
  disableSkill: (skillId: string) =>
    requestJson<SkillRegistration>(`/api/v1/skills/${encodeURIComponent(skillId)}/disable`, { method: 'POST' }),
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
  saveModelConfig: (body: ModelConfigUpdate) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/model', { method: 'PUT', body }),
  saveModelDefinition: (body: ModelConfigUpsertRequest) =>
    requestJson<RuntimeConfigSnapshot>('/api/v1/config/models', { method: 'POST', body }),
  testModelApi: (body: ModelApiTestRequest) =>
    requestJson<ModelApiTestResponse>('/api/v1/config/model/test', { method: 'POST', body }),
};
