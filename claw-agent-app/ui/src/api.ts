import { API_PATHS } from '../../shared/api-paths.mjs';

export type Workspace = {
  id: string;
  name: string;
  root: string;
  lastOpenedAt?: string;
};

export type AgentSession = {
  id: string;
  title?: string;
  workspaceId?: string;
  workspaceName?: string;
  workspaceRoot?: string;
  updatedAt?: string;
};

export type AgentMessage = {
  id: string;
  sessionId: string;
  taskId?: string;
  role: string;
  content: string;
  createdAt?: string;
};

export type AgentEvent = {
  id: string;
  sessionId: string;
  taskId?: string;
  level?: string;
  type?: string;
  message?: string;
  details?: Record<string, unknown>;
  createdAt?: string;
};

export type TodoItem = {
  id: string;
  title?: string;
  status?: string;
  itemOrder?: number;
};

export type PlanItem = {
  id: string;
  itemOrder?: number;
  title?: string;
  detail?: string;
  description?: string;
  status?: string;
  expectedTools?: string[];
  expectedFileChanges?: string[];
  riskLevel?: string;
  requiresApproval?: boolean;
};

export type PlanDraft = {
  id: string;
  sessionId?: string;
  sourceTaskId?: string;
  input?: string;
  title?: string;
  goal?: string;
  summary?: string;
  status?: string;
  outcome?: string;
  blockReason?: string;
  version?: number;
  activeFrom?: string;
  contextBoundaryAt?: string;
  items?: PlanItem[];
  revisions?: string[];
  createdAt?: string;
  updatedAt?: string;
};

export type PlanTemplateView = {
  id: string;
  title?: string;
  description?: string;
  mode?: string;
  promptHint?: string;
};

export type PlanRevisionSummaryView = {
  planId?: string;
  previousVersion?: number;
  version?: number;
  feedback?: string;
  itemCountBefore?: number;
  itemCountAfter?: number;
  addedItems?: string;
  removedItems?: string;
  changedItems?: string;
  updatedAt?: string;
};

export type SystemLogSource = {
  name: string;
  date?: string;
  size: number;
  compressed: boolean;
};

export type SystemLogLine = {
  time: string;
  level: string;
  logger: string;
  message: string;
  rawLine: string;
  sourceFile: string;
};

export type ModelConfigView = {
  provider?: string;
  baseUrl?: string;
  model?: string;
  apiKeyConfigured?: boolean;
  temperature?: number;
  timeoutSeconds?: number;
};

export type RuntimeConfig = {
  model?: {
    defaultModel?: string;
    memoryModel?: string;
    client?: string;
  };
  local?: {
    workspaceRoot?: string;
    defaultShell?: string;
    permissionMode?: string;
    approvedToolIds?: string[];
    allowedRoots?: string[];
    recentProjects?: string[];
  };
  auth?: {
    required?: boolean;
    apiTokenRequired?: boolean;
    protectedPathPatterns?: string[];
    excludedPathPatterns?: string[];
    initialized?: boolean;
    userCount?: number;
    ownerExists?: boolean;
    supportedRoles?: string[];
  };
  models?: Record<string, ModelConfigView>;
  configPath?: string;
  restartRequired?: boolean;
  message?: string;
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

export type ClientConfig = {
  serverUrl: string;
  connectionMode?: 'local' | 'remote';
  // edition 保留给旧版客户端读取，新版本统一使用 connectionMode。
  edition: 'local' | 'remote';
  configPath?: string;
  configExists?: boolean;
  activeServerUrl?: string;
  startupConnectionError?: string;
};

export type LocalUser = {
  id: string;
  username?: string;
  displayName?: string;
  role?: string;
  status?: string;
};

export type LocalUserSession = {
  sessionId: string;
  userId?: string;
  username?: string;
  displayName?: string;
  role?: string;
  status?: string;
  tokenPrefix?: string;
  expiresAt?: string;
};

export type LocalUserLoginResponse = {
  user?: LocalUser;
  session?: LocalUserSession;
  sessionToken?: string;
};

export type AuthSetupView = {
  initialized?: boolean;
  userCount?: number;
  ownerExists?: boolean;
  supportedRoles?: string[];
};

export type DeviceView = {
  id: string;
  name?: string;
  type?: string;
  status?: string;
  deviceSecretPrefix?: string;
  permissionMode?: string;
  approvedToolIds?: string[];
  boundUserId?: string;
  boundUsername?: string;
};

export type DevicePairResponse = {
  device?: DeviceView;
  deviceSecret?: string;
};

export type DeviceSecretVerifyResponse = {
  deviceId?: string;
  verified?: boolean;
  status?: string;
};

export const AUTH_SESSION_STORAGE_KEY = 'clawagent.auth.sessionToken';
export const AUTH_USER_ID_STORAGE_KEY = 'clawagent.auth.userId';
export const AUTH_USERNAME_STORAGE_KEY = 'clawagent.auth.username';
export const DEVICE_ID_STORAGE_KEY = 'clawagent.device.id';
export const DEVICE_SECRET_STORAGE_KEY = 'clawagent.device.secret';
export const DEVICE_NAME_STORAGE_KEY = 'clawagent.device.name';
export const DEVICE_TYPE_STORAGE_KEY = 'clawagent.device.type';
export const DEVICE_SECRET_PREFIX_STORAGE_KEY = 'clawagent.device.secretPrefix';

function deviceCredentialAllowed(path: string) {
  return path.startsWith('/api/v1/tasks')
    || path.startsWith('/api/v1/steps')
    || path.startsWith('/api/v1/agents')
    || path.startsWith('/api/v1/todos')
    || path.startsWith('/api/v1/plans')
    || path.startsWith('/api/v1/sessions')
    || path.startsWith('/api/v1/attachments');
}

function authHeaders(path = ''): Record<string, string> {
  const token = window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY)?.trim();
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  const deviceId = window.localStorage.getItem(DEVICE_ID_STORAGE_KEY)?.trim();
  const deviceSecret = window.localStorage.getItem(DEVICE_SECRET_STORAGE_KEY)?.trim();
  if (deviceId && deviceSecret && deviceCredentialAllowed(path)) {
    // 未登录但已配对的桌面端可用设备凭证访问任务类接口；设备管理接口仍走公开配对/校验流程。
    return {
      'X-ClawAgent-Device-Id': deviceId,
      'X-ClawAgent-Device-Secret': deviceSecret,
    };
  }
  return {};
}

export function currentLocalUserId() {
  return window.localStorage.getItem(AUTH_USER_ID_STORAGE_KEY)
    || window.localStorage.getItem(AUTH_USERNAME_STORAGE_KEY)
    || 'local';
}

function currentAuthMetadata() {
  const userId = window.localStorage.getItem(AUTH_USER_ID_STORAGE_KEY);
  const username = window.localStorage.getItem(AUTH_USERNAME_STORAGE_KEY);
  return {
    ...(userId ? { localUserId: userId } : {}),
    ...(username ? { 'auth.username': username } : {}),
  };
}

export function currentDeviceMetadata() {
  const deviceId = window.localStorage.getItem(DEVICE_ID_STORAGE_KEY);
  const deviceName = window.localStorage.getItem(DEVICE_NAME_STORAGE_KEY);
  const deviceType = window.localStorage.getItem(DEVICE_TYPE_STORAGE_KEY);
  return {
    ...(deviceId ? { deviceId, 'device.id': deviceId, 'client.deviceId': deviceId } : {}),
    ...(deviceName ? { 'device.name': deviceName } : {}),
    ...(deviceType ? { 'device.type': deviceType } : {}),
  };
}

async function json<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...authHeaders(path),
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
  }
  const text = await response.text();
  if (!text.trim()) {
    return null as T;
  }
  return JSON.parse(text) as T;
}

export const api = {
  runtime: () => json<Record<string, unknown>>(API_PATHS.appRuntime),
  recentWorkspaces: () => json<Workspace[]>(API_PATHS.appRecentWorkspaces),
  currentWorkspace: () => json<Workspace | null>(API_PATHS.appCurrentWorkspace),
  openWorkspace: (path: string) => json<Workspace>(API_PATHS.appOpenWorkspace, {
    method: 'POST',
    body: JSON.stringify({ path }),
  }),
  switchWorkspace: (workspaceId: string) => json<Workspace>(API_PATHS.appSwitchWorkspace, {
    method: 'POST',
    body: JSON.stringify({ workspaceId }),
  }),
  renameWorkspace: (workspaceId: string, name: string) => json<Workspace>(API_PATHS.appWorkspace(workspaceId), {
    method: 'PATCH',
    body: JSON.stringify({ name }),
  }),
  sessions: () => json<AgentSession[]>(API_PATHS.sessionsList(50)),
  sessionMessages: (sessionId: string, limit = 100) => json<AgentMessage[]>(API_PATHS.sessionMessages(sessionId, limit)),
  sessionEvents: (sessionId: string, limit = 200) => json<AgentEvent[]>(API_PATHS.sessionEvents(sessionId, limit)),
  updateSessionTitle: (sessionId: string, title: string) => json<AgentSession>(API_PATHS.session(sessionId), {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  }),
  deleteSession: (sessionId: string) => json<{ success: boolean; sessionId: string }>(API_PATHS.session(sessionId), {
    method: 'DELETE',
  }),
  createSession: (workspace: Workspace | null, title: string) => json<AgentSession>(API_PATHS.sessions, {
    method: 'POST',
    body: JSON.stringify({
      title,
      channelId: 'app',
      userId: currentLocalUserId(),
      workspaceId: workspace?.id || '',
      metadata: {
        ...currentAuthMetadata(),
        ...currentDeviceMetadata(),
        workspaceId: workspace?.id || '',
        workspaceName: workspace?.name || '',
        workspaceRoot: workspace?.root || '',
      },
    }),
  }),
  todos: (sessionId?: string) => json<TodoItem[]>(API_PATHS.todos(100, sessionId || '')),
  plans: (sessionId?: string, limit = 100) =>
    json<PlanDraft[]>(`/api/v1/plans?limit=${encodeURIComponent(limit)}${sessionId ? `&sessionId=${encodeURIComponent(sessionId)}` : ''}`),
  planTemplates: () => json<PlanTemplateView[]>('/api/v1/plans/templates'),
  plan: (planId: string) => json<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}`),
  planRevisionSummary: (planId: string) => json<PlanRevisionSummaryView | null>(`/api/v1/plans/${encodeURIComponent(planId)}/revision-summary`),
  createPlan: (body: { input: string; sessionId?: string; mode?: string; templateId?: string; metadata?: Record<string, string> }) =>
    json<PlanDraft>('/api/v1/plans', { method: 'POST', body: JSON.stringify(body) }),
  revisePlan: (planId: string, feedback: string) =>
    json<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/revise`, { method: 'POST', body: JSON.stringify({ feedback }) }),
  approvePlan: (planId: string) =>
    json<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/approve`, { method: 'POST' }),
  cancelPlan: (planId: string) =>
    json<PlanDraft>(`/api/v1/plans/${encodeURIComponent(planId)}/cancel`, { method: 'POST' }),
  logSources: () => json<SystemLogSource[]>(API_PATHS.logSources),
  queryLogs: (limit = 120) => json<SystemLogLine[]>(API_PATHS.logsQuery(limit)),
  runtimeConfig: () => json<RuntimeConfig>(API_PATHS.runtimeConfig),
  localHealth: (deep = false) => json<LocalHealthView>(API_PATHS.localHealth(deep)),
  authSetupStatus: () => json<AuthSetupView>(API_PATHS.authSetup),
  loginLocalUser: (username: string, password: string) => json<LocalUserLoginResponse>(API_PATHS.authLogin, {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  }),
  currentLocalUser: (sessionToken: string) => json<LocalUserLoginResponse>(API_PATHS.authMe, {
    headers: { Authorization: `Bearer ${sessionToken}` },
  }),
  logoutLocalUser: (sessionToken: string) => json<{ success: boolean }>(API_PATHS.authLogout, {
    method: 'POST',
    headers: { Authorization: `Bearer ${sessionToken}` },
  }),
  pairDevice: (code: string, metadata: Record<string, string>) => json<DevicePairResponse>(API_PATHS.devicesPair, {
    method: 'POST',
    body: JSON.stringify({ code, metadata }),
  }),
  verifyDeviceSecret: (deviceId: string, deviceSecret: string) => json<DeviceSecretVerifyResponse>(API_PATHS.deviceVerify(deviceId), {
    method: 'POST',
    body: JSON.stringify({ deviceSecret }),
  }),
  heartbeatDevice: (deviceId: string) => json<DeviceView>(API_PATHS.deviceHeartbeat(deviceId), {
    method: 'POST',
  }),
  saveModelDefinition: (body: {
    id: string;
    provider: string;
    baseUrl: string;
    model: string;
    apiKey?: string;
    temperature?: number;
    timeoutSeconds?: number;
  }) => json<RuntimeConfig>(API_PATHS.configModels, {
    method: 'POST',
    body: JSON.stringify(body),
  }),
};

async function readSse(response: Response, onEvent: (event: string, data: Record<string, unknown>) => void) {
  if (!response.ok || !response.body) {
    throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split('\n\n');
    buffer = chunks.pop() || '';
    for (const chunk of chunks) {
      const event = chunk.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message';
      const dataLine = chunk.split('\n').find((line) => line.startsWith('data:'));
      if (!dataLine) continue;
      try {
        onEvent(event, JSON.parse(dataLine.slice(5).trim()));
      } catch {
        onEvent(event, { raw: dataLine.slice(5).trim() });
      }
    }
  }
}

export async function streamTask(
  input: string,
  session: AgentSession,
  workspace: Workspace | null,
  options: { modelId?: string; permissionMode?: string },
  onEvent: (event: string, data: Record<string, unknown>) => void,
) {
  const response = await fetch(API_PATHS.tasksStream, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders(API_PATHS.tasksStream) },
    body: JSON.stringify({
      input,
      sessionId: session.id,
      channelId: 'app',
      userId: currentLocalUserId(),
      metadata: {
        ...currentAuthMetadata(),
        ...currentDeviceMetadata(),
        workspaceId: workspace?.id || session.workspaceId || '',
        workspaceName: workspace?.name || session.workspaceName || '',
        workspaceRoot: workspace?.root || session.workspaceRoot || '',
        modelId: options.modelId || '',
        permissionMode: options.permissionMode || '',
      },
    }),
  });
  await readSse(response, onEvent);
}

export async function streamPlan(
  planId: string,
  workspace: Workspace | null,
  session: AgentSession,
  options: { modelId?: string; permissionMode?: string },
  onEvent: (event: string, data: Record<string, unknown>) => void,
) {
  const response = await fetch(`/api/v1/plans/${encodeURIComponent(planId)}/run/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders(`/api/v1/plans/${encodeURIComponent(planId)}/run/stream`) },
    body: JSON.stringify({
      channelId: 'app',
      userId: currentLocalUserId(),
      metadata: {
        ...currentAuthMetadata(),
        ...currentDeviceMetadata(),
        workspaceId: workspace?.id || session.workspaceId || '',
        workspaceName: workspace?.name || session.workspaceName || '',
        workspaceRoot: workspace?.root || session.workspaceRoot || '',
        modelId: options.modelId || '',
        permissionMode: options.permissionMode || '',
      },
    }),
  });
  await readSse(response, onEvent);
}
