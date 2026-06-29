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
  models?: Record<string, ModelConfigView>;
  configPath?: string;
  restartRequired?: boolean;
  message?: string;
};

export type ClientConfig = {
  serverUrl: string;
  edition: 'local' | 'remote';
  configPath?: string;
  configExists?: boolean;
  activeServerUrl?: string;
  startupConnectionError?: string;
};

async function json<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
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
      userId: 'local',
      workspaceId: workspace?.id || '',
      metadata: {
        workspaceId: workspace?.id || '',
        workspaceName: workspace?.name || '',
        workspaceRoot: workspace?.root || '',
      },
    }),
  }),
  todos: (sessionId?: string) => json<TodoItem[]>(API_PATHS.todos(100, sessionId || '')),
  logSources: () => json<SystemLogSource[]>(API_PATHS.logSources),
  queryLogs: (limit = 120) => json<SystemLogLine[]>(API_PATHS.logsQuery(limit)),
  runtimeConfig: () => json<RuntimeConfig>(API_PATHS.runtimeConfig),
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

export async function streamTask(
  input: string,
  session: AgentSession,
  workspace: Workspace | null,
  options: { modelId?: string; permissionMode?: string },
  onEvent: (event: string, data: Record<string, unknown>) => void,
) {
  const response = await fetch(API_PATHS.tasksStream, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      input,
      sessionId: session.id,
      channelId: 'app',
      userId: 'local',
      metadata: {
        workspaceId: workspace?.id || session.workspaceId || '',
        workspaceName: workspace?.name || session.workspaceName || '',
        workspaceRoot: workspace?.root || session.workspaceRoot || '',
        modelId: options.modelId || '',
        permissionMode: options.permissionMode || '',
      },
    }),
  });
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
