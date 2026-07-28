import {
  ArrowDown,
  ArrowUp,
  Bot,
  Check,
  ChevronRight,
  Code2,
  FileText,
  FolderOpen,
  Image,
  KeyRound,
  Loader2,
  MessageSquare,
  Monitor,
  Package,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Paperclip,
  Plus,
  ScrollText,
  Send,
  Settings,
  TerminalSquare,
  User,
} from 'lucide-react';
import { FormEvent, PointerEvent, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AUTH_SESSION_STORAGE_KEY,
  AUTH_USER_ID_STORAGE_KEY,
  AUTH_USERNAME_STORAGE_KEY,
  DEVICE_ID_STORAGE_KEY,
  DEVICE_NAME_STORAGE_KEY,
  DEVICE_SECRET_PREFIX_STORAGE_KEY,
  DEVICE_SECRET_STORAGE_KEY,
  DEVICE_TYPE_STORAGE_KEY,
  AgentEvent,
  AgentMessage,
  AgentSession,
  ClientConfig,
  DevicePairResponse,
  DeviceView,
  LocalHealthView,
  LocalUser,
  LocalUserLoginResponse,
  LocalUserSession,
  PlanDraft,
  PlanItem,
  PlanRevisionSummaryView,
  PlanTemplateView,
  RuntimeConfig,
  SystemLogLine,
  SystemLogSource,
  Workspace,
  api,
  streamPlan,
  streamTask,
} from './api';

type ChatLine = {
  role: 'user' | 'assistant' | 'system';
  content: string;
  /** 当前页面流式回复的临时关联标识，不持久化到会话消息。 */
  streamId?: string;
  kind?: 'message' | 'event';
  eventName?: string;
  status?: string;
  detail?: string;
  createdAt?: string;
  toolName?: string;
  planId?: string;
  plan?: PlanDraft;
};

type AssistantRunLine = {
  type: 'assistantRun';
  events: ChatLine[];
  message?: ChatLine;
};

type AttachmentItem = {
  id: string;
  name: string;
  type: string;
  size: number;
};

declare global {
  interface Window {
    clawAgentApp?: {
      platform: string;
      desktop: boolean;
      onOpenSettings?: (callback: () => void) => void;
      selectDirectory?: () => Promise<string | null>;
      getClientConfig?: () => Promise<ClientConfig>;
      setServerUrl?: (serverUrl: string, options?: { check?: boolean; connectionMode?: 'local' | 'remote' }) => Promise<ClientConfig>;
    };
  }
}

const quickPrompts = [
  '分析当前项目结构',
  '规划下一步开发任务',
  '检查最近文件变更',
];

const permissionOptions = [
  { value: 'ask', label: '询问确认' },
  { value: 'auto', label: '自动审核' },
  { value: 'full', label: '完全访问' },
  { value: 'custom', label: '自定义工具' },
];

function shortServerUrl(value?: string) {
  if (!value) return '-';
  try {
    const url = new URL(value);
    return `${url.hostname}${url.port ? `:${url.port}` : ''}`;
  } catch {
    return value;
  }
}

function formatTime(value?: string) {
  if (!value) return '';
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    }).format(new Date(value));
  } catch {
    return '';
  }
}

function messageRole(role?: string): ChatLine['role'] {
  return role === 'user' || role === 'assistant' || role === 'system' ? role : 'system';
}

function planStatusText(status?: string) {
  const value = (status || 'DRAFT').toUpperCase();
  if (value === 'DRAFT') return '已创建';
  if (value === 'APPROVED') return '已创建';
  if (value === 'RUNNING') return '执行中';
  if (value === 'BLOCKED') return '已阻塞';
  if (value === 'DONE') return '已完成';
  return value;
}

function compactRevisionText(value?: string) {
  const text = (value || '').replace(/^\[|\]$/g, '').trim();
  return text || '无';
}

function localHealthClass(status?: string) {
  const normalized = (status || '').toUpperCase();
  if (normalized === 'UP' || normalized === 'OK') return 'success';
  if (normalized === 'DOWN' || normalized === 'ERROR') return 'danger';
  if (normalized === 'DEGRADED' || normalized === 'WARNING') return 'warning';
  return 'neutral';
}

function localHealthText(status?: string) {
  const normalized = (status || '').toUpperCase();
  if (normalized === 'UP' || normalized === 'OK') return '正常';
  if (normalized === 'DOWN' || normalized === 'ERROR') return '异常';
  if (normalized === 'DEGRADED' || normalized === 'WARNING') return '需检查';
  return '未知';
}

function planToLine(plan: PlanDraft): ChatLine {
  return {
    role: 'assistant',
    content: '',
    planId: plan.id,
    plan,
    status: planStatusText(plan.status),
    createdAt: plan.createdAt || plan.updatedAt,
  };
}

function PlanCard({
  plan,
  busy,
  onRevise,
  onRun,
  onCancel,
}: {
  plan: PlanDraft;
  busy?: boolean;
  onRevise: (planId: string, feedback: string) => void;
  onRun: (planId: string) => void;
  onCancel: (planId: string) => void;
}) {
  const [feedback, setFeedback] = useState('');
  const [revisionSummary, setRevisionSummary] = useState<PlanRevisionSummaryView | null>(null);
  const status = (plan.status || 'DRAFT').toUpperCase();
  const editable = status === 'BLOCKED';
  const runnable = status === 'BLOCKED';
  const items = (plan.items || []).slice().sort((left, right) => (left.itemOrder || 0) - (right.itemOrder || 0));
  useEffect(() => {
    if (!plan.id || (plan.version || 1) <= 1) {
      setRevisionSummary(null);
      return;
    }
    let cancelled = false;
    api.planRevisionSummary(plan.id)
      .then((summary) => {
        if (!cancelled) setRevisionSummary(summary);
      })
      .catch(() => {
        if (!cancelled) setRevisionSummary(null);
      });
    return () => {
      cancelled = true;
    };
  }, [plan.id, plan.version]);
  return (
    <section className="plan-card">
      <header>
        <div>
          <strong><ScrollText size={15} />任务计划</strong>
          <span className={`plan-status status-${status.toLowerCase()}`}>{planStatusText(plan.status)}</span>
        </div>
        <em>v{plan.version || 1}</em>
      </header>
      {plan.goal ? (
        <div className="plan-section">
          <span className="plan-section-title">目标</span>
          <p>{plan.goal}</p>
        </div>
      ) : null}
      {plan.summary ? <p>{plan.summary}</p> : null}
      <div className="plan-execution-strip">
        <span>状态：{planStatusText(plan.status)}</span>
        {plan.outcome ? <span>结果：{plan.outcome}</span> : null}
        {plan.blockReason ? <span>阻塞：{plan.blockReason}</span> : null}
        {status === 'APPROVED' || status === 'DRAFT' ? <strong>已自动进入执行队列</strong> : null}
        {status === 'BLOCKED' ? <strong>可修订后继续执行</strong> : null}
      </div>
      <ol>
        {items.map((item) => <PlanStep item={item} key={item.id || item.itemOrder || item.title} />)}
      </ol>
      {revisionSummary ? (
        <details className="plan-revisions" open>
          <summary>计划差异 v{revisionSummary.previousVersion || '-'} → v{revisionSummary.version || plan.version}</summary>
          <div className="plan-diff-grid">
            <span>步骤数</span>
            <strong>{revisionSummary.itemCountBefore ?? '-'} → {revisionSummary.itemCountAfter ?? '-'}</strong>
            <span>新增</span>
            <code>{compactRevisionText(revisionSummary.addedItems)}</code>
            <span>移除</span>
            <code>{compactRevisionText(revisionSummary.removedItems)}</code>
            <span>变更</span>
            <code>{compactRevisionText(revisionSummary.changedItems)}</code>
          </div>
          {revisionSummary.feedback ? <p>反馈：{revisionSummary.feedback}</p> : null}
        </details>
      ) : null}
      {editable ? (
        <div className="plan-revise">
          <input value={feedback} onChange={(event) => setFeedback(event.target.value)} placeholder="输入计划修改意见" />
          <button type="button" disabled={busy || !feedback.trim()} onClick={() => {
            onRevise(plan.id, feedback);
            setFeedback('');
          }}>修订</button>
        </div>
      ) : null}
      <footer>
        {runnable ? <button type="button" disabled={busy} onClick={() => onRun(plan.id)}>{busy ? '处理中...' : '继续执行'}</button> : null}
        {editable ? <button type="button" disabled={busy} onClick={() => onCancel(plan.id)}>取消</button> : null}
      </footer>
    </section>
  );
}

function PlanStep({ item }: { item: PlanItem }) {
  const detail = item.detail || item.description;
  return (
    <li>
      <span className="plan-dot" />
      <div>
        <strong>{item.itemOrder || '-'}. {item.title || '未命名步骤'}</strong>
        {detail ? <p>{detail}</p> : null}
        {item.expectedTools?.length ? (
          <div className="plan-tools">
            {item.expectedTools.slice(0, 6).map((tool) => <code key={tool}>{tool}</code>)}
          </div>
        ) : null}
      </div>
    </li>
  );
}

export function App() {
  const [runtime, setRuntime] = useState<Record<string, unknown>>({});
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig | null>(null);
  const [localHealth, setLocalHealth] = useState<LocalHealthView | null>(null);
  const [localHealthLoading, setLocalHealthLoading] = useState(false);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [expandedProjects, setExpandedProjects] = useState<Record<string, boolean>>({});
  const [sessions, setSessions] = useState<AgentSession[]>([]);
  const [session, setSession] = useState<AgentSession | null>(null);
  const [sessionMenu, setSessionMenu] = useState<{ x: number; y: number; session: AgentSession } | null>(null);
  const [runningSessionIds, setRunningSessionIds] = useState<Record<string, boolean>>({});
  const [unreadSessionIds, setUnreadSessionIds] = useState<Record<string, boolean>>({});
  const [pendingSessionCreate, setPendingSessionCreate] = useState(false);
  const [input, setInput] = useState('');
  const [lines, setLines] = useState<ChatLine[]>([]);
  const [planMode, setPlanMode] = useState(() => localStorage.getItem('clawagent.app.planMode') === 'true');
  const [planBusyId, setPlanBusyId] = useState<string>();
  const [planTemplates, setPlanTemplates] = useState<PlanTemplateView[]>([]);
  const [selectedPlanTemplateId, setSelectedPlanTemplateId] = useState(() => localStorage.getItem('clawagent.app.planTemplateId') || '');
  const [taskStatus, setTaskStatus] = useState('');
  const [error, setError] = useState('');
  const [newChatOpen, setNewChatOpen] = useState(false);
  const [newProjectOpen, setNewProjectOpen] = useState(false);
  const [page, setPage] = useState<'chat' | 'settings' | 'logs'>('chat');
  const [settingsTab, setSettingsTab] = useState<'general' | 'connection' | 'projects' | 'device' | 'logs' | 'models'>('general');
  const [logSources, setLogSources] = useState<SystemLogSource[]>([]);
  const [logLines, setLogLines] = useState<SystemLogLine[]>([]);
  const [theme, setTheme] = useState<'idea-light' | 'idea-dark'>(() => {
    return (localStorage.getItem('clawagent.theme') as 'idea-light' | 'idea-dark') || 'idea-light';
  });
  const [leftWidth, setLeftWidth] = useState(286);
  const [rightWidth, setRightWidth] = useState(360);
  const [showLeft, setShowLeft] = useState(true);
  const [showRight, setShowRight] = useState(true);
  const [rightTab, setRightTab] = useState<'review' | 'files'>('review');
  const [model, setModel] = useState('');
  const [permission, setPermission] = useState('ask');
  const [authMenuOpen, setAuthMenuOpen] = useState(false);
  const [localUser, setLocalUser] = useState<LocalUser>();
  const [localSession, setLocalSession] = useState<LocalUserSession>();
  const [localSessionToken, setLocalSessionToken] = useState(() => window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY) || '');
  const [loginUsername, setLoginUsername] = useState(() => window.localStorage.getItem(AUTH_USERNAME_STORAGE_KEY) || '');
  const [loginPassword, setLoginPassword] = useState('');
  const [authLoading, setAuthLoading] = useState(false);
  const [authMessage, setAuthMessage] = useState('');
  const [deviceId, setDeviceId] = useState(() => window.localStorage.getItem(DEVICE_ID_STORAGE_KEY) || '');
  const [deviceName, setDeviceName] = useState(() => window.localStorage.getItem(DEVICE_NAME_STORAGE_KEY) || '');
  const [deviceType, setDeviceType] = useState(() => window.localStorage.getItem(DEVICE_TYPE_STORAGE_KEY) || '');
  const [deviceSecret, setDeviceSecret] = useState(() => window.localStorage.getItem(DEVICE_SECRET_STORAGE_KEY) || '');
  const [deviceSecretPrefix, setDeviceSecretPrefix] = useState(() => window.localStorage.getItem(DEVICE_SECRET_PREFIX_STORAGE_KEY) || '');
  const [deviceStatus, setDeviceStatus] = useState(deviceId ? '未校验' : '未配对');
  const [devicePairingCode, setDevicePairingCode] = useState('');
  const [deviceLoading, setDeviceLoading] = useState(false);
  const [deviceMessage, setDeviceMessage] = useState('');
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [toolPickerOpen, setToolPickerOpen] = useState(false);
  const [plusOpen, setPlusOpen] = useState(false);
  const [plusMenuPage, setPlusMenuPage] = useState<'main' | 'planTemplates' | 'plugins'>('main');
  const [directoryMessage, setDirectoryMessage] = useState('');
  const [scrollState, setScrollState] = useState({ up: false, down: false });
  const composerRef = useRef<HTMLFormElement | null>(null);
  const conversationRef = useRef<HTMLElement | null>(null);
  const currentPageRef = useRef<'chat' | 'settings' | 'logs'>('chat');
  const currentSessionIdRef = useRef<string | null>(null);
  const directoryInputRef = useRef<HTMLInputElement | null>(null);
  const [newModel, setNewModel] = useState({
    id: '',
    provider: '',
    baseUrl: '',
    model: '',
    apiKey: '',
  });
  const [clientConfig, setClientConfig] = useState<ClientConfig | null>(null);
  const [serverUrlDraft, setServerUrlDraft] = useState('http://127.0.0.1:17891');
  const [serverConfigMessage, setServerConfigMessage] = useState('');
  const [connectionModeDraft, setConnectionModeDraft] = useState<'local' | 'remote'>('local');

  const activeConnectionUrl = clientConfig?.activeServerUrl || window.location.origin;
  const hasStartupFallback = Boolean(clientConfig?.startupConnectionError);
  // 连接模式由用户配置决定，本机地址也可以作为远程服务连接。
  const connectionMode = clientConfig?.connectionMode || clientConfig?.edition || (window.clawAgentApp?.desktop ? 'local' : 'remote');
  // 远程启动失败时 Electron 会临时回落本地服务，当前运行态不应再要求远程身份。
  const isLocalConnection = connectionMode === 'local' || hasStartupFallback;
  const isRemoteConnection = !isLocalConnection;
  const connectionLabel = isLocalConnection ? '本地模式' : '远程模式';
  // 本地服务端口可能因占用自动变化，界面必须展示实际运行地址而非默认尝试地址。
  const localRuntimeUrl = isLocalConnection ? activeConnectionUrl : '切换后由桌面端自动启动本地服务';

  useEffect(() => {
    void refresh();
  }, []);

  useEffect(() => {
    api.planTemplates()
      .then(setPlanTemplates)
      .catch(() => setPlanTemplates([]));
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem('clawagent.theme', theme);
  }, [theme]);

  useEffect(() => {
    currentPageRef.current = page;
  }, [page]);

  useEffect(() => {
    currentSessionIdRef.current = session?.id || null;
  }, [session?.id]);

  useEffect(() => {
    window.clawAgentApp?.onOpenSettings?.(() => setPage('settings'));
    void loadClientConfig();
  }, []);

  useEffect(() => {
    if (page !== 'logs') return;
    void refreshLogs();
  }, [page]);

  useEffect(() => {
    if (page !== 'settings') return;
    void refreshLocalHealth(false);
  }, [page]);

  useEffect(() => {
    const closeFloatingMenus = (event: globalThis.PointerEvent) => {
      if (composerRef.current?.contains(event.target as Node)) return;
      const target = event.target as Element;
      if (target.closest?.('.session-context-menu')) return;
      if (target.closest?.('.auth-menu')) return;
      if (target.closest?.('.identity-panel')) return;
      setPlusOpen(false);
      setToolPickerOpen(false);
      setPlusMenuPage('main');
      setSessionMenu(null);
      setAuthMenuOpen(false);
    };
    document.addEventListener('pointerdown', closeFloatingMenus, true);
    return () => document.removeEventListener('pointerdown', closeFloatingMenus, true);
  }, []);

  useEffect(() => {
    const token = localSessionToken.trim();
    if (isLocalConnection || !token) {
      setLocalUser(undefined);
      setLocalSession(undefined);
      return;
    }
    let cancelled = false;
    api.currentLocalUser(token)
      .then((response) => {
        if (cancelled) return;
        setLocalUser(response.user);
        setLocalSession(response.session);
        setAuthMessage('');
      })
      .catch(() => {
        if (cancelled) return;
        // 本地会话失效时同步清理缓存，避免后续任务继续携带过期身份。
        clearLocalAuth('登录已过期，请重新登录。');
      });
    return () => {
      cancelled = true;
    };
  }, [isLocalConnection, localSessionToken]);

  useEffect(() => {
    if (!isRemoteConnection || !deviceId || !deviceSecret) return;
    void verifyCurrentDevice(false);
  }, [deviceId, deviceSecret, isRemoteConnection]);

  const plainSessions = useMemo(() => sessions.filter((item) => !item.workspaceId), [sessions]);

  const projectSessionGroups = useMemo(() => {
    return workspaces.map((item) => ({
      workspace: item,
      sessions: sessions.filter((sessionItem) => sessionItem.workspaceId === item.id),
    }));
  }, [sessions, workspaces]);

  const appMode = String(runtime.mode || 'server');
  const appUrl = String(runtime.appUrl || '/app/');
  const modelOptions = useMemo(() => {
    const configured = Object.entries(runtimeConfig?.models || {}).map(([id, config]) => ({
      id,
      label: id,
      title: config.model ? `${id} · ${config.model}` : id,
    }));
    return configured.length ? configured : [];
  }, [runtimeConfig]);
  const currentSessionBusy = Boolean(session?.id && runningSessionIds[session.id]);
  const composerBusy = pendingSessionCreate || currentSessionBusy;

  async function refresh() {
    const [runtimeInfo, recent, current, sessionList, config] = await Promise.all([
      api.runtime(),
      api.recentWorkspaces(),
      api.currentWorkspace(),
      api.sessions(),
      api.runtimeConfig().catch(() => null),
    ]);
    setRuntime(runtimeInfo);
    setRuntimeConfig(config);
    const defaultModel = config?.model?.defaultModel;
    if (defaultModel) {
      setModel(defaultModel);
    } else if (Object.keys(config?.models || {}).length) {
      setModel(Object.keys(config?.models || {})[0]);
    } else {
      setModel('');
    }
    const permissionMode = config?.local?.permissionMode;
    if (permissionMode && permissionOptions.some((item) => item.value === permissionMode)) {
      setPermission(permissionMode);
    }
    setWorkspaces(recent);
    setWorkspace(current || recent[0] || null);
    setSessions(sessionList);
  }

  async function refreshLocalHealth(deep = false) {
    setLocalHealthLoading(true);
    try {
      setLocalHealth(await api.localHealth(deep));
    } catch {
      setLocalHealth({
        status: 'DOWN',
        items: [{
          key: 'local-health',
          label: '本地健康检查',
          status: 'error',
          summary: '健康检查接口不可用',
          detail: '请确认后端服务已启动，并检查 /api/v1/config/local/health。',
        }],
      });
    } finally {
      setLocalHealthLoading(false);
    }
  }

  function updateConversationScrollState() {
    const element = conversationRef.current;
    if (!element) {
      setScrollState({ up: false, down: false });
      return;
    }
    const maxTop = element.scrollHeight - element.clientHeight;
    setScrollState({
      up: element.scrollTop > 8,
      down: maxTop - element.scrollTop > 8,
    });
  }

  function scrollConversationTo(edge: 'top' | 'bottom', behavior: ScrollBehavior = 'smooth') {
    const element = conversationRef.current;
    if (!element) return;
    element.scrollTo({
      top: edge === 'top' ? 0 : element.scrollHeight,
      behavior,
    });
  }

  useEffect(() => {
    requestAnimationFrame(() => {
      scrollConversationTo('bottom', 'auto');
      updateConversationScrollState();
    });
  }, [session?.id]);

  useEffect(() => {
    requestAnimationFrame(() => {
      scrollConversationTo('bottom', 'auto');
      updateConversationScrollState();
    });
  }, [lines]);

  async function loadClientConfig() {
    if (!window.clawAgentApp?.getClientConfig) {
      const browserConfig: ClientConfig = {
        serverUrl: window.location.origin,
        connectionMode: 'remote',
        edition: 'remote',
        activeServerUrl: window.location.origin,
        configExists: false,
      };
      setClientConfig(browserConfig);
      setServerUrlDraft(browserConfig.serverUrl);
      setConnectionModeDraft('remote');
      return;
    }
    const config = await window.clawAgentApp.getClientConfig();
    setClientConfig(config);
    setServerUrlDraft(config.serverUrl);
    setConnectionModeDraft(config.connectionMode || config.edition || 'local');
  }

  async function saveServerUrl(check = true) {
    if (!window.clawAgentApp?.setServerUrl) {
      setServerConfigMessage('浏览器访问不能修改桌面客户端服务器地址。');
      return;
    }
    setServerConfigMessage(connectionModeDraft === 'remote' ? '正在校验并切换远程服务...' : '正在启动并切换本地服务...');
    try {
      const saved = await window.clawAgentApp.setServerUrl(serverUrlDraft, { check, connectionMode: connectionModeDraft });
      setClientConfig(saved);
      setServerUrlDraft(saved.serverUrl);
      setConnectionModeDraft(saved.connectionMode || saved.edition);
      setServerConfigMessage(`已切换到 ${saved.connectionMode === 'remote' ? '远程模式' : '本地模式'}：${saved.activeServerUrl || saved.serverUrl}`);
    } catch (error) {
      setServerConfigMessage(error instanceof Error ? error.message : '服务器地址保存失败。');
    }
  }

  function selectConnectionMode(mode: 'local' | 'remote') {
    setConnectionModeDraft(mode);
    if (mode === 'local') setServerUrlDraft('http://127.0.0.1:17891');
    setServerConfigMessage('');
  }

  function rememberLocalAuth(response: LocalUserLoginResponse) {
    const token = response.sessionToken || '';
    const userId = response.user?.id || '';
    const username = response.user?.username || '';
    if (token) window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY, token);
    if (userId) window.localStorage.setItem(AUTH_USER_ID_STORAGE_KEY, userId);
    if (username) window.localStorage.setItem(AUTH_USERNAME_STORAGE_KEY, username);
    setLocalSessionToken(token);
    setLocalUser(response.user);
    setLocalSession(response.session);
    setLoginUsername(username || loginUsername);
  }

  function clearLocalAuth(message = '') {
    window.localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
    window.localStorage.removeItem(AUTH_USER_ID_STORAGE_KEY);
    window.localStorage.removeItem(AUTH_USERNAME_STORAGE_KEY);
    setLocalSessionToken('');
    setLocalUser(undefined);
    setLocalSession(undefined);
    setLoginPassword('');
    setAuthMessage(message);
  }

  async function loginLocalUser(event: FormEvent) {
    event.preventDefault();
    const username = loginUsername.trim();
    if (!username || !loginPassword || authLoading) return;
    setAuthLoading(true);
    setAuthMessage('正在登录...');
    try {
      rememberLocalAuth(await api.loginLocalUser(username, loginPassword));
      setLoginPassword('');
      setAuthMessage('已登录。');
      setAuthMenuOpen(false);
    } catch (err) {
      setAuthMessage(err instanceof Error ? err.message : '登录失败。');
    } finally {
      setAuthLoading(false);
    }
  }

  async function logoutLocalUser() {
    const token = localSessionToken.trim();
    setAuthLoading(true);
    setAuthMessage('正在退出...');
    try {
      if (token) await api.logoutLocalUser(token).catch(() => null);
      clearLocalAuth('已退出。');
      setAuthMenuOpen(false);
    } finally {
      setAuthLoading(false);
    }
  }

  function rememberDevicePair(response: DevicePairResponse) {
    const paired = response.device;
    const secret = response.deviceSecret || '';
    if (!paired?.id || !secret) {
      throw new Error('设备配对响应缺少 deviceId 或 deviceSecret。');
    }
    window.localStorage.setItem(DEVICE_ID_STORAGE_KEY, paired.id);
    window.localStorage.setItem(DEVICE_SECRET_STORAGE_KEY, secret);
    window.localStorage.setItem(DEVICE_NAME_STORAGE_KEY, paired.name || 'ClawAgent App');
    window.localStorage.setItem(DEVICE_TYPE_STORAGE_KEY, paired.type || 'desktop');
    window.localStorage.setItem(DEVICE_SECRET_PREFIX_STORAGE_KEY, paired.deviceSecretPrefix || '');
    setDeviceId(paired.id);
    setDeviceSecret(secret);
    setDeviceName(paired.name || 'ClawAgent App');
    setDeviceType(paired.type || 'desktop');
    setDeviceSecretPrefix(paired.deviceSecretPrefix || '');
    setDeviceStatus(paired.status || 'active');
  }

  function clearDevicePair(message = '') {
    window.localStorage.removeItem(DEVICE_ID_STORAGE_KEY);
    window.localStorage.removeItem(DEVICE_SECRET_STORAGE_KEY);
    window.localStorage.removeItem(DEVICE_NAME_STORAGE_KEY);
    window.localStorage.removeItem(DEVICE_TYPE_STORAGE_KEY);
    window.localStorage.removeItem(DEVICE_SECRET_PREFIX_STORAGE_KEY);
    setDeviceId('');
    setDeviceSecret('');
    setDeviceName('');
    setDeviceType('');
    setDeviceSecretPrefix('');
    setDeviceStatus('未配对');
    setDeviceMessage(message);
  }

  async function pairCurrentDevice() {
    const code = devicePairingCode.trim();
    if (!code || deviceLoading) return;
    setDeviceLoading(true);
    setDeviceMessage('正在配对设备...');
    try {
      const response = await api.pairDevice(code, {
        source: 'claw-agent-app',
        platform: window.clawAgentApp?.platform || 'browser',
        desktop: String(Boolean(window.clawAgentApp?.desktop)),
        ...(localUser?.id ? { localUserId: localUser.id } : {}),
        ...(localUser?.username ? { username: localUser.username } : {}),
      });
      // deviceSecret 只在客户端保存，用于后续校验；任务 metadata 只携带 deviceId。
      rememberDevicePair(response);
      setDevicePairingCode('');
      setDeviceMessage('设备已配对，后续任务会携带 deviceId 进入权限策略合并。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : '设备配对失败。');
    } finally {
      setDeviceLoading(false);
    }
  }

  async function verifyCurrentDevice(report = true) {
    if (!deviceId || !deviceSecret || deviceLoading) {
      if (report) setDeviceMessage('当前没有可校验的设备密钥。');
      return;
    }
    if (report) {
      setDeviceLoading(true);
      setDeviceMessage('正在校验设备密钥...');
    }
    try {
      const response = await api.verifyDeviceSecret(deviceId, deviceSecret);
      if (!response.verified) {
        setDeviceStatus(response.status || '校验失败');
        if (report) setDeviceMessage('设备密钥校验失败，请重新配对。');
        return;
      }
      const heartbeat = await api.heartbeatDevice(deviceId).catch(() => undefined as DeviceView | undefined);
      setDeviceStatus(heartbeat?.status || response.status || 'active');
      setDeviceName(heartbeat?.name || deviceName);
      setDeviceType(heartbeat?.type || deviceType);
      setDeviceSecretPrefix(heartbeat?.deviceSecretPrefix || deviceSecretPrefix);
      if (heartbeat?.name) window.localStorage.setItem(DEVICE_NAME_STORAGE_KEY, heartbeat.name);
      if (heartbeat?.type) window.localStorage.setItem(DEVICE_TYPE_STORAGE_KEY, heartbeat.type);
      if (heartbeat?.deviceSecretPrefix) window.localStorage.setItem(DEVICE_SECRET_PREFIX_STORAGE_KEY, heartbeat.deviceSecretPrefix);
      if (report) setDeviceMessage('设备密钥有效，心跳已更新。');
    } catch (err) {
      if (report) setDeviceMessage(err instanceof Error ? err.message : '设备校验失败。');
    } finally {
      if (report) setDeviceLoading(false);
    }
  }

  async function openWorkspace(path: string) {
    const targetPath = path.trim();
    if (!targetPath) return null;
    const opened = await api.openWorkspace(targetPath);
    const sessionList = await api.sessions();
    setWorkspaces((current) => [opened, ...current.filter((item) => item.id !== opened.id)]);
    setWorkspace(opened);
    setExpandedProjects((current) => ({ ...current, [opened.id]: true }));
    setSessions(sessionList);
    return opened;
  }

  async function browseWorkspace(startChat = false) {
    setDirectoryMessage('');
    if (!window.clawAgentApp?.selectDirectory) {
      directoryInputRef.current?.click();
      return;
    }
    try {
      const selectedPath = await window.clawAgentApp.selectDirectory();
      if (!selectedPath) {
        setDirectoryMessage('没有选择项目目录。');
        return;
      }
      setDirectoryMessage('正在添加项目目录...');
      const opened = await openWorkspace(selectedPath);
      if (opened && startChat) startConversation(opened);
      if (opened && !startChat) setNewProjectOpen(false);
    } catch (err) {
      setDirectoryMessage(err instanceof Error ? err.message : '选择项目目录失败。');
    }
  }

  function handleBrowserDirectorySelection(files: FileList | null) {
    const firstFile = files?.[0] as (File & { webkitRelativePath?: string }) | undefined;
    const folderName = firstFile?.webkitRelativePath?.split('/')[0] || '已选择目录';
    setDirectoryMessage(`浏览器已选择 ${folderName}，但 Web 安全限制不提供本机绝对路径。请在 Electron 桌面端添加项目目录。`);
  }

  async function saveNewModel() {
    if (!newModel.id.trim() || !newModel.model.trim()) return;
    const saved = await api.saveModelDefinition({
      id: newModel.id.trim(),
      provider: newModel.provider.trim(),
      baseUrl: newModel.baseUrl.trim(),
      model: newModel.model.trim(),
      apiKey: newModel.apiKey.trim(),
      temperature: 0.2,
      timeoutSeconds: 60,
    });
    setRuntimeConfig(saved);
    setModel(newModel.id.trim());
    setNewModel({ id: '', provider: '', baseUrl: '', model: '', apiKey: '' });
  }

  async function switchWorkspace(id: string) {
    const selected = await api.switchWorkspace(id);
    setWorkspace(selected);
    setSession(null);
    setLines([]);
  }

  async function renameWorkspace(item: Workspace) {
    const name = window.prompt('修改项目名称', item.name)?.trim();
    if (!name || name === item.name) return;
    const updated = await api.renameWorkspace(item.id, name);
    setWorkspaces((current) => current.map((workspaceItem) => workspaceItem.id === item.id ? updated : workspaceItem));
    setSessions((current) => current.map((sessionItem) => sessionItem.workspaceId === item.id ? {
      ...sessionItem,
      workspaceName: updated.name,
    } : sessionItem));
    if (workspace?.id === item.id) setWorkspace(updated);
  }

  async function openSession(item: AgentSession) {
    setWorkspace(item.workspaceId ? workspaces.find((workspaceItem) => workspaceItem.id === item.workspaceId) || null : null);
    setSession(item);
    setUnreadSessionIds((current) => {
      if (!current[item.id]) return current;
      const next = { ...current };
      delete next[item.id];
      return next;
    });
    setPage('chat');
    setTaskStatus('');
    const [messages, events, plans] = await Promise.all([
      api.sessionMessages(item.id, 120),
      api.sessionEvents(item.id, 200),
      api.plans(item.id, 100).catch(() => [] as PlanDraft[]),
    ]);
    const messageLines = messages.map((message: AgentMessage): ChatLine => ({
      role: messageRole(message.role),
      content: message.content || '',
      createdAt: message.createdAt,
    }));
    const eventLines = compactEventLines(events
      .map(eventToChatLine)
      .filter((line): line is ChatLine => Boolean(line)));
    const planLines = plans.map(planToLine);
    setLines([...messageLines, ...eventLines, ...planLines].sort((left, right) => {
      if (!left.createdAt || !right.createdAt) return 0;
      return left.createdAt.localeCompare(right.createdAt);
    }));
  }

  async function renameSession(item: AgentSession) {
    const title = window.prompt('修改对话名称', item.title || '未命名对话')?.trim();
    if (!title || title === item.title) return;
    const updated = await api.updateSessionTitle(item.id, title);
    setSessions((current) => current.map((sessionItem) => sessionItem.id === item.id ? updated : sessionItem));
    if (session?.id === item.id) setSession(updated);
  }

  async function deleteSession(item: AgentSession) {
    const confirmed = window.confirm(`删除对话“${item.title || '未命名对话'}”？`);
    if (!confirmed) return;
    await api.deleteSession(item.id);
    setSessions((current) => current.filter((sessionItem) => sessionItem.id !== item.id));
    if (session?.id === item.id) {
      setSession(null);
      setLines([]);
      setTaskStatus('');
    }
    setSessionMenu(null);
  }

  function toggleProject(projectId: string) {
    setExpandedProjects((current) => ({ ...current, [projectId]: !(current[projectId] ?? true) }));
  }

  function startConversation(selected?: Workspace | null) {
    setWorkspace(selected || null);
    setSession(null);
    setLines([]);
    setInput('');
    setTaskStatus('');
    setNewChatOpen(false);
    setNewProjectOpen(false);
    setPage('chat');
  }

  function formatTaskStatus(eventName: string, data: Record<string, unknown>) {
    const text = String(data.toolId || data.name || data.title || data.message || data.status || eventName).trim();
    if (eventName === 'task.started') return '任务已开始';
    if (eventName === 'task.done' || eventName === 'task.completed') return '任务已完成';
    if (eventName === 'tool.started') return `正在运行 ${text}`;
    if (eventName === 'tool.done' || eventName === 'tool.completed' || eventName === 'tool.succeeded') return `已运行 ${text}`;
    if (eventName === 'tool.failed' || eventName === 'tool.error') return `运行失败 ${text}`;
    if (eventName.startsWith('todo.')) return text ? `Todo 更新：${text}` : 'Todo 已更新';
    if (eventName.startsWith('llm.')) return '';
    return text;
  }

  function formatEventDetail(data: Record<string, unknown>) {
    const sections = [
      ['Input', data.input],
      ['Arguments', data.arguments || data.params],
      ['Output', data.output || data.result || data.content],
      ['Error', data.error],
      ['Raw', data.raw],
      ['Message', data.message],
    ].filter(([, value]) => value !== undefined && value !== null && value !== '');
    if (!sections.length) return '';
    const text = sections.map(([label, value]) => {
      const detail = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
      return `${label}\n${detail}`;
    }).join('\n\n');
    return text.length > 2400 ? `${text.slice(0, 2400)}\n...` : text;
  }

  function eventToChatLine(event: AgentEvent): ChatLine | null {
    const eventName = event.type || event.level || 'event';
    const details: Record<string, unknown> = event.details && typeof event.details === 'object' ? event.details : {};
    const data = {
      ...details,
      message: event.message || details.message,
      status: event.level || details.status,
    } as Record<string, unknown>;
    return createEventLine(eventName, data, event.createdAt);
  }

  function createEventLine(eventName: string, data: Record<string, unknown>, createdAt?: string): ChatLine | null {
    if (!eventName.startsWith('tool.')) return null;
    const status = formatTaskStatus(eventName, data);
    if (!status) return null;
    const toolName = String(data.toolId || data.name || data.title || data.message || '').trim();
    return {
      role: 'system',
      kind: 'event',
      content: '',
      eventName,
      status,
      detail: formatEventDetail(data),
      createdAt,
      toolName,
    };
  }

  function isTerminalToolEvent(eventName = '') {
    return eventName === 'tool.succeeded'
      || eventName === 'tool.done'
      || eventName === 'tool.completed'
      || eventName === 'tool.failed'
      || eventName === 'tool.error';
  }

  function isFailedToolEvent(eventName = '', status = '') {
    return eventName === 'tool.failed' || eventName === 'tool.error' || /失败|failed|error/i.test(status);
  }

  function isRunningToolEvent(eventName = '', status = '') {
    return eventName === 'tool.started' || /正在|started|running/i.test(status);
  }

  function mergeToolDetail(previous?: string, next?: string) {
    if (previous && next && previous !== next) return `${previous}\n\n---\n\n${next}`;
    return next || previous || '';
  }

  function compactEventLines(eventLines: ChatLine[]) {
    const compacted: ChatLine[] = [];
    for (const line of eventLines) {
      if (isTerminalToolEvent(line.eventName)) {
        let runningIndex = -1;
        for (let index = compacted.length - 1; index >= 0; index -= 1) {
          const candidate = compacted[index];
          if (
            candidate.kind === 'event'
            && candidate.toolName
            && candidate.toolName === line.toolName
            && isRunningToolEvent(candidate.eventName, candidate.status)
          ) {
            runningIndex = index;
            break;
          }
        }
        if (runningIndex >= 0) {
          const previous = compacted[runningIndex];
          compacted[runningIndex] = {
            ...previous,
            ...line,
            detail: mergeToolDetail(previous.detail, line.detail),
          };
          continue;
        }
      }
      compacted.push(line);
    }
    return compacted;
  }

  function groupChatLines(items: ChatLine[]): Array<ChatLine | AssistantRunLine> {
    const grouped: Array<ChatLine | AssistantRunLine> = [];
    let pendingEvents: ChatLine[] = [];
    const flushEvents = () => {
      if (!pendingEvents.length) return;
      const last = grouped[grouped.length - 1];
      if (last && 'events' in last) {
        last.events = [...last.events, ...pendingEvents];
        pendingEvents = [];
        return;
      }
      if (last && last.role === 'assistant') {
        grouped[grouped.length - 1] = { type: 'assistantRun', events: pendingEvents, message: last };
        pendingEvents = [];
        return;
      }
      grouped.push({ type: 'assistantRun', events: pendingEvents });
      pendingEvents = [];
    };

    for (const item of items) {
      if (item.kind === 'event') {
        pendingEvents.push(item);
        continue;
      }
      if (item.role === 'assistant' && pendingEvents.length) {
        grouped.push({ type: 'assistantRun', events: pendingEvents, message: item });
        pendingEvents = [];
        continue;
      }
      flushEvents();
      grouped.push(item);
    }
    flushEvents();
    return grouped;
  }

  function appendAssistantDelta(streamId: string, content: string) {
    setLines((current) => {
      const next = [...current];
      for (let index = next.length - 1; index >= 0; index -= 1) {
        if (next[index].streamId === streamId) {
          next[index] = { ...next[index], content: `${next[index].content}${content}` };
          return next;
        }
      }
      return [...next, { role: 'assistant', content, streamId, createdAt: new Date().toISOString() }];
    });
  }

  function applyAssistantResult(streamId: string, answer: string) {
    if (!answer) return;
    setLines((current) => {
      const next = [...current];
      for (let index = next.length - 1; index >= 0; index -= 1) {
        if (next[index].streamId === streamId) {
          // 正常流式场景已通过 delta 写入；只在模型没有下发 delta 时补最终答案。
          if (!next[index].content) next[index] = { ...next[index], content: answer };
          return next;
        }
      }
      return [...next, { role: 'assistant', content: answer, streamId, createdAt: new Date().toISOString() }];
    });
  }

  function discardEmptyAssistantLine(streamId: string) {
    setLines((current) => current.filter((line) => line.streamId !== streamId || Boolean(line.content)));
  }

  function appendProcessLine(eventName: string, data: Record<string, unknown>) {
    const line = createEventLine(eventName, data);
    if (!line) return '';
    setLines((current) => {
      if (isTerminalToolEvent(eventName)) {
        const next = [...current];
        for (let index = next.length - 1; index >= 0; index -= 1) {
          const candidate = next[index];
          if (
            candidate.kind === 'event'
            && candidate.toolName
            && candidate.toolName === line.toolName
            && isRunningToolEvent(candidate.eventName, candidate.status)
          ) {
            next[index] = {
              ...candidate,
              ...line,
              detail: mergeToolDetail(candidate.detail, line.detail),
            };
            return next;
          }
        }
      }
      return [...current, line];
    });
    return line.status || '';
  }

  async function refreshLogs() {
    const [sources, logs] = await Promise.all([api.logSources(), api.queryLogs(180)]);
    setLogSources(sources);
    setLogLines(logs.reverse());
  }

  function startResize(side: 'left' | 'right', event: PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const startX = event.clientX;
    const startLeft = leftWidth;
    const startRight = rightWidth;
    const onMove = (moveEvent: globalThis.PointerEvent) => {
      if (side === 'left') {
        setLeftWidth(Math.min(520, Math.max(180, startLeft + moveEvent.clientX - startX)));
      } else {
        setRightWidth(Math.min(720, Math.max(240, startRight - (moveEvent.clientX - startX))));
      }
    };
    const onUp = () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
    };
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    const text = input.trim();
    if (!text || composerBusy) return;
    if (planMode || text.startsWith('/plan ')) {
      await createPlan(text.startsWith('/plan ') ? text.slice(6).trim() : text, text);
      return;
    }
    let activeSession = session;
    let completed = false;
    setPendingSessionCreate(!activeSession);
    setTaskStatus('正在提交任务...');
    setError('');
    setInput('');
    const streamId = `task-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    // 每个任务预先占用自己的 assistant 消息槽，防止流式内容误追加到历史回复。
    setLines((current) => [...current,
      { role: 'user', content: text },
      { role: 'assistant', content: '', streamId, createdAt: new Date().toISOString() },
    ]);
    try {
      if (!activeSession) {
        activeSession = await api.createSession(workspace, text.slice(0, 30) || '新会话');
        setSessions((current) => [activeSession!, ...current.filter((item) => item.id !== activeSession!.id)]);
      }
      setSession(activeSession);
      setPendingSessionCreate(false);
      setRunningSessionIds((current) => ({ ...current, [activeSession!.id]: true }));
      await streamTask(text, activeSession, workspace, { modelId: model, permissionMode: permission }, (eventName, data) => {
        const isVisibleSession = currentSessionIdRef.current === activeSession!.id && currentPageRef.current === 'chat';
        if (eventName === 'llm.delta') {
          if (isVisibleSession) appendAssistantDelta(streamId, String(data.content || ''));
          return;
        }
        if (eventName === 'result') {
          if (isVisibleSession) applyAssistantResult(streamId, String(data.answer || ''));
          return;
        }
        if (!isVisibleSession) return;
        const status = appendProcessLine(eventName, data);
        if (status) setTaskStatus(status);
      });
      setSessions(await api.sessions());
      completed = true;
    } catch (err) {
      discardEmptyAssistantLine(streamId);
      if (!activeSession?.id || currentSessionIdRef.current === activeSession.id) {
        setError(err instanceof Error ? err.message : String(err));
      }
    } finally {
      setPendingSessionCreate(false);
      if (activeSession?.id) {
        setRunningSessionIds((current) => {
          if (!current[activeSession!.id]) return current;
          const next = { ...current };
          delete next[activeSession!.id];
          return next;
        });
        if (currentSessionIdRef.current === activeSession.id) setTaskStatus('');
        if (completed && (currentSessionIdRef.current !== activeSession.id || currentPageRef.current !== 'chat')) {
          setUnreadSessionIds((current) => ({ ...current, [activeSession!.id]: true }));
        }
      }
    }
  }

  async function createPlan(text: string, visibleText = text) {
    if (!text.trim() || composerBusy) return;
    let activeSession = session;
    setPendingSessionCreate(!activeSession);
    setTaskStatus('正在生成计划...');
    setError('');
    setInput('');
    setLines((current) => [...current, { role: 'user', content: visibleText }]);
    try {
      if (!activeSession) {
        activeSession = await api.createSession(workspace, text.slice(0, 30) || '新会话');
        setSessions((current) => [activeSession!, ...current.filter((item) => item.id !== activeSession!.id)]);
      }
      setSession(activeSession);
      setPendingSessionCreate(false);
      const plan = await api.createPlan({
        input: text,
        sessionId: activeSession.id,
        mode: 'grounded',
        templateId: selectedPlanTemplateId || undefined,
        metadata: {
          workspaceId: workspace?.id || activeSession.workspaceId || '',
          workspaceName: workspace?.name || activeSession.workspaceName || '',
          workspaceRoot: workspace?.root || activeSession.workspaceRoot || '',
          modelId: model || '',
          permissionMode: permission || '',
        },
      });
      setLines((current) => [...current, planToLine(plan)]);
      setSessions(await api.sessions());
      setPendingSessionCreate(false);
      setTaskStatus('');
      await runPlan(plan.id, activeSession, true);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPendingSessionCreate(false);
      setTaskStatus('');
    }
  }

  function updatePlanLine(plan: PlanDraft) {
    setLines((current) => {
      let replaced = false;
      const next = current.map((line) => {
        if (line.planId !== plan.id) return line;
        replaced = true;
        return { ...line, ...planToLine(plan) };
      });
      return replaced ? next : [...next, planToLine(plan)];
    });
  }

  async function revisePlan(planId: string, feedback: string) {
    if (!feedback.trim()) return;
    setPlanBusyId(planId);
    try {
      updatePlanLine(await api.revisePlan(planId, feedback.trim()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPlanBusyId(undefined);
    }
  }

  async function cancelPlan(planId: string) {
    setPlanBusyId(planId);
    try {
      updatePlanLine(await api.cancelPlan(planId));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPlanBusyId(undefined);
    }
  }

  async function runPlan(planId: string, sessionOverride?: AgentSession, ignoreBusy = false) {
    const targetSession = sessionOverride || session;
    if ((!ignoreBusy && composerBusy) || !targetSession) return;
    const streamId = `plan-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setPlanBusyId(planId);
    setRunningSessionIds((current) => ({ ...current, [targetSession.id]: true }));
    setTaskStatus('正在执行计划...');
    try {
      const latest = await api.plan(planId);
      if ((latest.status || '').toUpperCase() === 'DRAFT') {
        updatePlanLine(await api.approvePlan(planId));
      }
      setLines((current) => [...current, { role: 'assistant', content: '', streamId, createdAt: new Date().toISOString() }]);
      await streamPlan(planId, workspace, targetSession, { modelId: model, permissionMode: permission }, (eventName, data) => {
        const isVisibleSession = currentSessionIdRef.current === targetSession.id && currentPageRef.current === 'chat';
        if (eventName === 'llm.delta') {
          if (isVisibleSession) appendAssistantDelta(streamId, String(data.content || ''));
          return;
        }
        if (eventName === 'result') {
          if (isVisibleSession) applyAssistantResult(streamId, String(data.answer || ''));
          return;
        }
        if (!isVisibleSession) return;
        const status = appendProcessLine(eventName, data);
        if (status) setTaskStatus(status);
      });
      updatePlanLine(await api.plan(planId));
      setSessions(await api.sessions());
    } catch (err) {
      discardEmptyAssistantLine(streamId);
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      if (targetSession.id) {
        setRunningSessionIds((current) => {
          const next = { ...current };
          delete next[targetSession.id];
          return next;
        });
      }
      setPlanBusyId(undefined);
      setTaskStatus('');
    }
  }

  function addFiles(files: FileList | File[]) {
    const incoming = Array.from(files).map((file) => ({
      id: `${file.name}-${file.size}-${file.lastModified}-${Math.random().toString(16).slice(2)}`,
      name: file.name || (file.type.startsWith('image/') ? 'pasted-image.png' : 'clipboard-file'),
      type: file.type || 'application/octet-stream',
      size: file.size,
    }));
    setAttachments((current) => [...current, ...incoming]);
  }

  function onInputChange(value: string) {
    setInput(value);
    const cursorToken = value.split(/\s/).pop() || '';
    setToolPickerOpen(cursorToken.startsWith('@'));
    if (cursorToken.startsWith('@')) setPlusOpen(false);
  }

  function insertTool(tool: string) {
    setInput((current) => `${current.replace(/@\S*$/, '').trimEnd()} @${tool} `);
    setToolPickerOpen(false);
    setPlusOpen(false);
    setPlusMenuPage('main');
  }

  function togglePlanMode() {
    setPlanMode((current) => {
      const next = !current;
      localStorage.setItem('clawagent.app.planMode', String(next));
      return next;
    });
  }

  function changePlanTemplate(templateId: string) {
    setSelectedPlanTemplateId(templateId);
    localStorage.setItem('clawagent.app.planTemplateId', templateId);
  }

  function planTemplateLabel() {
    if (!selectedPlanTemplateId) return '默认计划';
    return planTemplates.find((template) => template.id === selectedPlanTemplateId)?.title || '默认计划';
  }

  function selectPlanTemplate(templateId: string) {
    changePlanTemplate(templateId);
    setPlanMode(true);
    localStorage.setItem('clawagent.app.planMode', 'true');
    setPlusOpen(false);
    setPlusMenuPage('main');
  }

  function settingsSubPageLabel() {
    const labels: Record<string, string> = {
      general: '常规',
      connection: '连接',
      projects: '项目',
      device: '设备',
      logs: '日志',
      models: '模型与权限',
    };
    return labels[settingsTab] || '常规';
  }

  function renderBreadcrumb(menu: string, subPage?: string) {
    return (
      <div className="crumb">
        <button className="crumb-link" type="button" onClick={() => setPage('chat')}>首页</button>
        <ChevronRight size={13} />
        <strong>{menu}</strong>
        {subPage ? (
          <>
            <ChevronRight size={13} />
            <span>{subPage}</span>
          </>
        ) : null}
      </div>
    );
  }

  function renderIdentityPanel() {
    if (isLocalConnection) {
      return (
        <div className="identity-panel local">
          <div className="identity-main">
            <Monitor size={15} />
            <span>
              <strong>本地模式</strong>
              <em>{shortServerUrl(activeConnectionUrl)}</em>
            </span>
          </div>
          <p>当前连接的是本机服务，无需登录或设备配对。</p>
          {hasStartupFallback ? <p className="identity-warning">远程服务不可用，已临时回到本地模式。</p> : null}
        </div>
      );
    }

    const userLabel = localUser?.displayName || localUser?.username || '未登录';
    const deviceLabel = deviceId
      ? `${deviceName || '已配对设备'} · ${deviceStatus || 'active'}`
      : '设备未配对';

    return (
      <div className="identity-panel remote">
        <button className={localUser ? 'identity-main signed-in' : 'identity-main'} type="button" onClick={() => setAuthMenuOpen((open) => !open)}>
          <User size={15} />
          <span>
            <strong>{userLabel}</strong>
            <em>{shortServerUrl(activeConnectionUrl)}</em>
          </span>
        </button>
        <button type="button" className={deviceId ? 'identity-device paired' : 'identity-device'} onClick={() => { setPage('settings'); setSettingsTab('device'); }}>
          <Monitor size={13} />
          <span>{deviceLabel}</span>
        </button>
        {authMenuOpen ? (
          <div className="auth-popover">
            {localUser ? (
              <div className="auth-user-card">
                <strong>{localUser.displayName || localUser.username || '本地用户'}</strong>
                <span>{localUser.role || 'user'} · {localSession?.status || localUser.status || 'active'}</span>
                <button type="button" disabled={authLoading} onClick={() => void logoutLocalUser()}>退出登录</button>
              </div>
            ) : (
              <form className="auth-form" onSubmit={loginLocalUser}>
                <label>
                  <span>用户名</span>
                  <input value={loginUsername} onChange={(event) => setLoginUsername(event.target.value)} placeholder="admin" autoComplete="username" />
                </label>
                <label>
                  <span>密码</span>
                  <input type="password" value={loginPassword} onChange={(event) => setLoginPassword(event.target.value)} placeholder="请输入密码" autoComplete="current-password" />
                </label>
                <button type="submit" disabled={authLoading || !loginUsername.trim() || !loginPassword}>{authLoading ? '登录中...' : '登录'}</button>
              </form>
            )}
            {authMessage ? <p className="auth-message">{authMessage}</p> : null}
          </div>
        ) : null}
      </div>
    );
  }

  function renderSessionButton(item: AgentSession) {
    const isRunning = Boolean(runningSessionIds[item.id]);
    const isUnread = Boolean(unreadSessionIds[item.id]) && session?.id !== item.id;
    return (
      <button key={item.id} className={session?.id === item.id ? 'session active' : 'session'} onClick={() => void openSession(item)} onContextMenu={(event) => {
        event.preventDefault();
        setSessionMenu({ x: event.clientX, y: event.clientY, session: item });
      }}>
        <span className="session-title-row">
          <strong>{item.title || '未命名对话'}</strong>
          {isRunning ? <span className="session-state running" title="任务执行中" /> : null}
          {!isRunning && isUnread ? <span className="session-state unread" title="有新回复" /> : null}
        </span>
        <em>{formatTime(item.updatedAt) || item.workspaceName || '最近对话'}</em>
      </button>
    );
  }

  function toolDisplayName(line: ChatLine) {
    return line.toolName
      || line.status?.replace(/^(正在运行|已运行|运行失败)\s*/, '')
      || line.eventName
      || '工具调用';
  }

  function renderToolRunRow(line: ChatLine, index: number) {
    const isFailed = isFailedToolEvent(line.eventName, line.status);
    const isRunning = isRunningToolEvent(line.eventName, line.status);
    const stateLabel = isFailed ? '调用失败' : isRunning ? '正在调用' : '调用成功';
    return (
      <details key={`${line.eventName}-${line.createdAt || index}-${index}`} className={isFailed ? 'tool-run-row failed' : isRunning ? 'tool-run-row running' : 'tool-run-row'} open={isFailed}>
        <summary>
          <span className="tool-run-state" />
          <strong>{stateLabel}</strong>
          <span className="tool-name">{toolDisplayName(line)}</span>
          <em>{line.eventName}</em>
        </summary>
        {line.detail ? (
          <pre><code>{line.detail}</code></pre>
        ) : null}
      </details>
    );
  }

  function renderAssistantRun(run: AssistantRunLine, index: number) {
    const events = run.events;
    const failedCount = events.filter((item) => isFailedToolEvent(item.eventName, item.status)).length;
    const runningCount = events.filter((item) => isRunningToolEvent(item.eventName, item.status)).length;
    const finishedCount = events.length - runningCount;
    const lastEventTime = run.message?.createdAt || events[events.length - 1]?.createdAt;
    return (
      <article key={`assistant-run-${index}`} className="message assistant assistant-run-message">
        <div className="avatar"><Bot size={15} /></div>
        <div className="assistant-run-card">
          <details className={failedCount ? 'assistant-tool-details failed' : runningCount ? 'assistant-tool-details running' : 'assistant-tool-details'}>
            <summary className="assistant-run-header">
              <span>Assistant</span>
              {lastEventTime ? <time>{formatTime(lastEventTime)}</time> : null}
              <em>已运行 {finishedCount || events.length} 条命令</em>
            </summary>
            <div className="tool-run-list">
              {events.map(renderToolRunRow)}
            </div>
          </details>
          {run.message ? (
            <div className="assistant-run-answer">
              <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {run.message.content || (currentSessionBusy ? '正在处理...' : '')}
              </ReactMarkdown>
            </div>
          ) : null}
          {!run.message && currentSessionBusy ? <div className="assistant-run-answer muted">正在处理...</div> : null}
        </div>
      </article>
    );
  }

  return (
    <main
      className={page === 'chat' ? 'app-shell' : 'app-shell page-mode'}
      style={{
        gridTemplateColumns: page === 'chat'
          ? `${showLeft ? leftWidth : 0}px ${showLeft ? 6 : 0}px minmax(0, 1fr) ${showRight ? 6 : 0}px ${showRight ? rightWidth : 0}px`
          : `${showLeft ? leftWidth : 0}px ${showLeft ? 6 : 0}px minmax(0, 1fr)`,
      }}
    >
      <aside className={showLeft ? 'left-nav' : 'left-nav hidden-pane'}>
        <button className="new-task" onClick={() => setNewChatOpen(true)}>
          <Plus size={15} />
          新建对话
        </button>

        <section className="sidebar-section">
          <div className="section-title">
            <span>项目</span>
            <button title="添加项目" onClick={() => setNewProjectOpen(true)}><Plus size={13} /></button>
          </div>
          <div className="project-list project-tree">
            {projectSessionGroups.map((group) => {
              const expanded = expandedProjects[group.workspace.id] ?? true;
              return (
                <div className="project-tree-item" key={group.workspace.id}>
                  <div className={workspace?.id === group.workspace.id ? 'project-row active' : 'project-row'}>
                    <button className={expanded ? 'project-toggle expanded' : 'project-toggle'} onClick={() => toggleProject(group.workspace.id)} aria-label={expanded ? '收起项目' : '展开项目'}>›</button>
                    <button className="project project-name" onClick={() => toggleProject(group.workspace.id)} onContextMenu={(event) => {
                      event.preventDefault();
                      void renameWorkspace(group.workspace);
                    }}>
                      <FolderOpen size={14} />
                      <span>{group.workspace.name}</span>
                    </button>
                    <button className="project-session-add" title="在此项目中新建对话" onClick={() => {
                      setExpandedProjects((current) => ({ ...current, [group.workspace.id]: true }));
                      startConversation(group.workspace);
                    }}><Plus size={12} /></button>
                  </div>
                  {expanded ? (
                    <div className="project-session-list">
                      {group.sessions.map(renderSessionButton)}
                      {!group.sessions.length ? <div className="empty-list compact">暂无项目对话。</div> : null}
                    </div>
                  ) : null}
                </div>
              );
            })}
            {!projectSessionGroups.length ? <div className="empty-list">暂无项目。</div> : null}
          </div>
        </section>

        <section className="sidebar-section conversation-section">
          <div className="section-title">
            <span>对话</span>
            <button onClick={() => setNewChatOpen(true)}><Plus size={13} /></button>
          </div>
          <div className="session-list">
            {plainSessions.map(renderSessionButton)}
            {!plainSessions.length ? <div className="empty-list compact">暂无普通对话。</div> : null}
          </div>
        </section>

        <div className="sidebar-footer">
          {renderIdentityPanel()}
          <button className={page === 'settings' ? 'footer-button active' : 'footer-button'} onClick={() => setPage('settings')}>
            <Settings size={15} />
            设置
          </button>
        </div>
      </aside>

      {showLeft ? <div className="resize-handle left-resize" onPointerDown={(event) => startResize('left', event)} /> : <div className="resize-handle left-resize hidden-pane" />}
      {!showLeft ? (
        <button className="floating-pane-toggle left" title="显示左侧栏" onClick={() => setShowLeft(true)}>
          <PanelLeftOpen size={15} />
        </button>
      ) : null}

      {sessionMenu ? (
        <div className="session-context-menu" style={{ left: sessionMenu.x, top: sessionMenu.y }}>
          <button onClick={() => {
            const item = sessionMenu.session;
            setSessionMenu(null);
            void renameSession(item);
          }}>重命名</button>
          <button className="danger" onClick={() => void deleteSession(sessionMenu.session)}>删除</button>
        </div>
      ) : null}

      {page === 'chat' ? (
        <section className="workbench">
          <header className="workbench-top">
            <div className="crumb">
              <button className="pane-toggle" title={showLeft ? '隐藏左侧栏' : '显示左侧栏'} onClick={() => setShowLeft((visible) => !visible)}>
                {showLeft ? <PanelLeftClose size={15} /> : <PanelLeftOpen size={15} />}
              </button>
              {workspace ? (
                <>
                  <strong>{workspace.name}</strong>
                  <ChevronRight size={13} />
                  <span>{session?.title || '新对话'}</span>
                </>
              ) : (
                <strong>{session?.title || '新对话'}</strong>
              )}
            </div>
            <div className="top-actions">
              <button className="pane-toggle" title={showRight ? '隐藏右侧栏' : '显示右侧栏'} onClick={() => setShowRight((visible) => !visible)}>
                {showRight ? <PanelRightClose size={15} /> : <PanelRightOpen size={15} />}
              </button>
            </div>
          </header>

          <div className="conversation-frame">
            <section ref={conversationRef} className="conversation" onScroll={updateConversationScrollState}>
              {lines.length === 0 ? (
                  <div className="starter">
                    <h2>我们该构建什么？</h2>
                    <div className="starter-context">
                      {workspace ? <span><FolderOpen size={13} />{workspace.name}</span> : null}
                    <span><Code2 size={13} />{connectionLabel}</span>
                    </div>
                  <div className="prompt-grid">
                    {quickPrompts.map((prompt) => <button key={prompt} onClick={() => setInput(prompt)}>{prompt}</button>)}
                  </div>
                </div>
              ) : groupChatLines(lines).map((line, index) => (
                'events' in line ? renderAssistantRun(line, index) : (
                  <article key={index} className={`message ${line.role}`}>
                    <div className="avatar">{line.role === 'user' ? <User size={14} /> : <Bot size={15} />}</div>
                    <div className="message-body">
                      {line.plan ? (
                        <PlanCard
                          plan={line.plan}
                          busy={planBusyId === line.plan.id}
                          onRevise={revisePlan}
                          onRun={runPlan}
                          onCancel={cancelPlan}
                        />
                      ) : (
                        <ReactMarkdown remarkPlugins={[remarkGfm]}>
                          {line.content || (currentSessionBusy && line.role === 'assistant' ? '正在处理...' : '')}
                        </ReactMarkdown>
                      )}
                    </div>
                  </article>
                )
              ))}
            </section>
            {scrollState.up ? (
              <button className="conversation-scroll-button top" title="滚动到顶部" onClick={() => scrollConversationTo('top')}>
                <ArrowUp size={15} />
              </button>
            ) : null}
            {scrollState.down ? (
              <button className="conversation-scroll-button bottom" title="滚动到底部" onClick={() => scrollConversationTo('bottom')}>
                <ArrowDown size={15} />
              </button>
            ) : null}
          </div>

          {error ? <div className="error-banner">{error}</div> : null}
          {taskStatus ? <div className="task-status">{taskStatus}</div> : null}

          <form ref={composerRef} className="composer-card" onSubmit={submit}>
            <textarea
              value={input}
              onChange={(event) => onInputChange(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  event.currentTarget.form?.requestSubmit();
                }
              }}
              onDragOver={(event) => event.preventDefault()}
              onDrop={(event) => {
                event.preventDefault();
                addFiles(event.dataTransfer.files);
              }}
              onPaste={(event) => {
                if (event.clipboardData.files.length) {
                  addFiles(event.clipboardData.files);
                }
              }}
              placeholder={workspace ? '要求后续变更，ClawAgent 会在当前项目中执行' : '输入消息，未选择项目时作为普通会话'}
            />
            {toolPickerOpen ? (
              <div className="tool-picker">
                <header>
                  <span>@ 选择工具</span>
                </header>
                {['mcp:filesystem', 'mcp:browser', 'mcp:github', 'skill:frontend-design', 'skill:security-scan'].map((tool) => (
                  <button key={tool} type="button" onClick={() => insertTool(tool)}>
                    {tool.startsWith('mcp:') ? <Package size={14} /> : <Code2 size={14} />}
                    <span>{tool}</span>
                  </button>
                ))}
              </div>
            ) : null}
            {plusOpen && plusMenuPage === 'main' ? (
              <div className="tool-picker">
                <header><span>+ 添加上下文</span></header>
                <label className="tool-picker-file">
                  <Paperclip size={14} />
                  <span>添加文件或图片</span>
                  <input type="file" multiple onChange={(event) => {
                    if (event.target.files) addFiles(event.target.files);
                    setPlusOpen(false);
                  }} />
                </label>
                <button type="button" onClick={() => {
                  setPlusMenuPage('planTemplates');
                }}>
                  <ScrollText size={14} />
                  <span>{planMode ? `计划：${planTemplateLabel()}` : '计划'}</span>
                  <ChevronRight size={13} />
                </button>
                <button type="button" onClick={() => {
                  setInput((current) => `${current.trimEnd()} #目标 `);
                  setPlusOpen(false);
                }}>
                  <KeyRound size={14} />
                  <span>目标</span>
                </button>
                <button type="button" onClick={() => setPlusMenuPage('plugins')}>
                  <Package size={14} />
                  <span>插件</span>
                  <ChevronRight size={13} />
                </button>
              </div>
            ) : null}
            {plusOpen && plusMenuPage === 'planTemplates' ? (
              <div className="tool-picker">
                <header>
                  <button type="button" className="menu-back" onClick={() => setPlusMenuPage('main')}>返回</button>
                  <span>选择计划类型</span>
                </header>
                <button type="button" onClick={() => selectPlanTemplate('')}>
                  <ScrollText size={14} />
                  <span>默认计划</span>
                  {!selectedPlanTemplateId && <Check size={13} />}
                </button>
                {planTemplates.map((template) => (
                  <button key={template.id} type="button" onClick={() => selectPlanTemplate(template.id)} title={template.description || template.title}>
                    <ScrollText size={14} />
                    <span>{template.title || template.id}</span>
                    {selectedPlanTemplateId === template.id && <Check size={13} />}
                  </button>
                ))}
                {planMode ? (
                  <button type="button" onClick={() => {
                    togglePlanMode();
                    setPlusOpen(false);
                    setPlusMenuPage('main');
                  }}>
                    <ScrollText size={14} />
                    <span>关闭计划</span>
                  </button>
                ) : null}
              </div>
            ) : null}
            {plusOpen && plusMenuPage === 'plugins' ? (
              <div className="tool-picker">
                <header>
                  <button type="button" className="menu-back" onClick={() => setPlusMenuPage('main')}>返回</button>
                  <span>插件</span>
                </header>
                {['mcp:filesystem', 'mcp:browser', 'mcp:github', 'skill:frontend-design', 'skill:security-scan'].map((tool) => (
                  <button key={tool} type="button" onClick={() => insertTool(tool)}>
                    {tool.startsWith('mcp:') ? <Package size={14} /> : <Code2 size={14} />}
                    <span>{tool}</span>
                  </button>
                ))}
              </div>
            ) : null}
            {attachments.length ? (
              <div className="attachment-strip">
                {attachments.map((file) => (
                  <span key={file.id}>
                    {file.type.startsWith('image/') ? <Image size={13} /> : <Paperclip size={13} />}
                    {file.name}
                    <button type="button" onClick={() => setAttachments((current) => current.filter((item) => item.id !== file.id))}>x</button>
                  </span>
                ))}
              </div>
            ) : null}
            <footer>
              <div className="context-pills">
                <button
                  type="button"
                  className="composer-plus"
                  onClick={() => {
                    setPlusOpen((open) => !open);
                    setToolPickerOpen(false);
                    setPlusMenuPage('main');
                  }}
                >
                  <Plus size={15} />
                </button>
                <label><KeyRound size={13} /><select value={permission} onChange={(event) => setPermission(event.target.value)}>
                  {permissionOptions.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
                </select></label>
                {workspace ? <span><FolderOpen size={13} />{workspace.name}</span> : null}
                {planMode ? (
                  <button
                    type="button"
                    className="plan-toggle active"
                    onClick={() => {
                      setPlusOpen(true);
                      setToolPickerOpen(false);
                      setPlusMenuPage('planTemplates');
                    }}
                    title={`当前计划：${planTemplateLabel()}`}
                  >
                    <ScrollText size={13} />
                    {`计划：${planTemplateLabel()}`}
                  </button>
                ) : null}
              </div>
              <div className="send-controls">
                <label className="model-select"><Bot size={13} /><select value={model} onChange={(event) => setModel(event.target.value)}>
                  {modelOptions.length ? modelOptions.map((item) => <option key={item.id} value={item.id} title={item.title}>{item.label}</option>) : <option value="">未配置模型</option>}
                </select></label>
                <button disabled={!input.trim() || composerBusy}>
                  {composerBusy ? <Loader2 className="spin" size={17} /> : <Send size={17} />}
                </button>
              </div>
            </footer>
          </form>
        </section>
      ) : null}

      {page === 'logs' ? (
        <section className="workbench page-view">
          <header className="workbench-top">
            {renderBreadcrumb('服务日志', 'Spring Boot 后台日志')}
            <div className="top-actions">
              <button onClick={() => void refreshLogs()}>刷新</button>
              <button onClick={() => setPage('chat')}>返回应用</button>
            </div>
          </header>
          <section className="logs-page">
            <div className="log-console">
              {logLines.map((line, index) => (
                <div key={`${line.time}-${index}`} className={`log-line ${line.level?.toLowerCase() || ''}`}>
                  <span>{line.time}</span>
                  <em>{line.level}</em>
                  <strong>{line.logger}</strong>
                  <p>{line.message || line.rawLine}</p>
                </div>
              ))}
              {!logLines.length ? <div className="empty-list">暂无日志内容。</div> : null}
            </div>
          </section>
        </section>
      ) : null}

      {page === 'settings' ? (
        <section className="workbench page-view">
          <header className="workbench-top">
            {renderBreadcrumb('设置', settingsSubPageLabel())}
            <div className="top-actions">
              <button onClick={() => setPage('chat')}>返回应用</button>
            </div>
          </header>
          <section className="settings-page">
            <aside className="settings-nav">
              <button className={settingsTab === 'general' ? 'active' : ''} onClick={() => setSettingsTab('general')}><Settings size={15} />常规</button>
              <button className={settingsTab === 'connection' ? 'active' : ''} onClick={() => setSettingsTab('connection')}><TerminalSquare size={15} />连接</button>
              <button className={settingsTab === 'projects' ? 'active' : ''} onClick={() => setSettingsTab('projects')}><FolderOpen size={15} />项目</button>
              <button className={settingsTab === 'device' ? 'active' : ''} onClick={() => setSettingsTab('device')}><Monitor size={15} />设备</button>
              <button className={settingsTab === 'logs' ? 'active' : ''} onClick={() => setSettingsTab('logs')}><TerminalSquare size={15} />日志</button>
              <button className={settingsTab === 'models' ? 'active' : ''} onClick={() => setSettingsTab('models')}><Code2 size={15} />模型与权限</button>
            </aside>
            <div className="settings-content">
      {settingsTab === 'general' ? (
                <>
                  <h1>常规</h1>
                  <section className="settings-block">
                    <h2>外观</h2>
                    <div className="settings-row">
                      <div>
                        <strong>主题</strong>
                        <span>IDEA 风格浅色和 Darcula 主题</span>
                      </div>
                      <div className="theme-toggle">
                        <button className={theme === 'idea-light' ? 'active' : ''} onClick={() => setTheme('idea-light')}>Light</button>
                        <button className={theme === 'idea-dark' ? 'active' : ''} onClick={() => setTheme('idea-dark')}>Darcula</button>
                      </div>
                    </div>
                  </section>
                  <section className="settings-block">
                    <h2>运行环境</h2>
                    <div className="settings-kv"><span>模式</span><strong>{appMode}</strong></div>
                    <div className="settings-kv"><span>实际应用地址</span><strong>{appUrl}</strong></div>
                    <div className="settings-kv"><span>后台配置文件</span><strong>{runtimeConfig?.configPath || '-'}</strong></div>
                  </section>
                  <section className="settings-block">
                    <div className="settings-block-title">
                      <h2>本地健康检查</h2>
                      <div className="settings-actions">
                        <span className={`status-pill ${localHealthClass(localHealth?.status)}`}>{localHealthText(localHealth?.status)}</span>
                        <button type="button" onClick={() => void refreshLocalHealth(false)} disabled={localHealthLoading}>
                          {localHealthLoading ? '检查中...' : '重新检查'}
                        </button>
                      </div>
                    </div>
                    <div className="local-health-list">
                      {(localHealth?.items || []).map((item) => (
                        <div className="local-health-row" key={item.key || item.label}>
                          <span className={`status-pill ${localHealthClass(item.status)}`}>{localHealthText(item.status)}</span>
                          <div>
                            <strong>{item.label || item.key || '-'}</strong>
                            <p>{item.summary || '-'}</p>
                            {item.detail ? <code>{item.detail}</code> : null}
                          </div>
                        </div>
                      ))}
                      {!localHealth?.items?.length ? (
                        <div className="empty-list">{localHealthLoading ? '正在检查本地配置...' : '暂无健康检查结果。'}</div>
                      ) : null}
                    </div>
                  </section>
                </>
              ) : null}
              {settingsTab === 'connection' ? (
                <>
                  <h1>连接</h1>
                  <section className="settings-block">
                    <h2>服务器地址</h2>
                    <div className="settings-kv"><span>连接类型</span><strong>{connectionLabel}</strong></div>
                    <div className="settings-kv"><span>{isLocalConnection ? '本地服务地址' : '实际运行地址'}</span><strong>{activeConnectionUrl}</strong></div>
                    {!isLocalConnection ? (
                      <div className="settings-kv"><span>保存目标地址</span><strong>{clientConfig?.serverUrl || '未保存，使用当前访问地址'}</strong></div>
                    ) : null}
                    <div className="settings-kv"><span>客户端配置文件</span><strong>{clientConfig?.configExists ? clientConfig.configPath : '未创建，正在使用默认本地地址'}</strong></div>
                    {clientConfig?.startupConnectionError ? (
                      <div className="settings-alert">{clientConfig.startupConnectionError}</div>
                    ) : null}
                    <div className="server-form">
                      <div className="connection-mode-picker" role="group" aria-label="连接模式">
                        <button
                          type="button"
                          className={connectionModeDraft === 'local' ? 'active' : ''}
                          onClick={() => selectConnectionMode('local')}
                        >本地模式</button>
                        <button
                          type="button"
                          className={connectionModeDraft === 'remote' ? 'active' : ''}
                          onClick={() => selectConnectionMode('remote')}
                        >远程模式</button>
                      </div>
                      <input
                        value={connectionModeDraft === 'local' ? localRuntimeUrl : serverUrlDraft}
                        onChange={(event) => setServerUrlDraft(event.target.value)}
                        disabled={connectionModeDraft === 'local'}
                        placeholder="http://127.0.0.1:17891 或企业服务地址"
                      />
                      <div className="server-actions">
                        <button type="button" onClick={() => void saveServerUrl(true)}>保存并切换</button>
                      </div>
                      <p>{connectionModeDraft === 'local'
                        ? '本地服务由桌面端托管，地址不可修改；端口被占用时会自动选择空闲端口。'
                        : '远程模式只连接填写的 ClawAgent Server，不会启动或重启本地服务。'}</p>
                      {serverConfigMessage ? <p className="settings-message">{serverConfigMessage}</p> : null}
                    </div>
                  </section>
                </>
              ) : null}
              {settingsTab === 'projects' ? (
                <>
                  <h1>项目</h1>
                  <section className="settings-block">
                    <h2>最近项目</h2>
                    {workspaces.map((item) => (
                      <div className="settings-kv" key={item.id}><span>{item.name}</span><strong>{item.root}</strong></div>
                    ))}
                    <div className="settings-row"><button className="primary-setting-button" onClick={() => void browseWorkspace(false)}>选择项目目录</button></div>
                  </section>
                </>
              ) : null}
              {settingsTab === 'device' ? (
                <>
                  <h1>设备</h1>
                  {isLocalConnection ? (
                    <section className="settings-block local-mode-block">
                      <h2>本地模式</h2>
                      <div className="settings-row">
                        <div>
                          <strong>当前连接的是本机服务</strong>
                          <span>本地桌面端默认信任本机用户，不需要登录或设备配对即可使用。</span>
                        </div>
                        <span className="status-pill success">本地</span>
                      </div>
                      <div className="settings-kv"><span>实际运行地址</span><strong>{activeConnectionUrl}</strong></div>
                    </section>
                  ) : (
                    <>
                      <section className="settings-block">
                        <h2>当前设备</h2>
                        <div className="settings-kv"><span>设备状态</span><strong>{deviceStatus}</strong></div>
                        <div className="settings-kv"><span>设备 ID</span><strong>{deviceId || '未配对'}</strong></div>
                        <div className="settings-kv"><span>设备名称</span><strong>{deviceName || '-'}</strong></div>
                        <div className="settings-kv"><span>设备类型</span><strong>{deviceType || '-'}</strong></div>
                        <div className="settings-kv"><span>密钥前缀</span><strong>{deviceSecretPrefix || '-'}</strong></div>
                        <div className="settings-row">
                          <div>
                            <strong>设备权限策略</strong>
                            <span>远程模式配对后，App 发起的会话和任务会携带 deviceId，由后端合并设备级权限。</span>
                          </div>
                          <button type="button" className="primary-setting-button" disabled={!deviceId || !deviceSecret || deviceLoading} onClick={() => void verifyCurrentDevice(true)}>校验设备</button>
                        </div>
                      </section>
                      <section className="settings-block">
                        <h2>配对设备</h2>
                        <div className="device-form">
                          <input value={devicePairingCode} onChange={(event) => setDevicePairingCode(event.target.value)} placeholder="输入管理台生成的设备配对码" />
                          <div className="server-actions">
                            <button type="button" disabled={!devicePairingCode.trim() || deviceLoading} onClick={() => void pairCurrentDevice()}>完成配对</button>
                            <button type="button" disabled={!deviceId || deviceLoading} onClick={() => clearDevicePair('已解除本地设备配对。')}>解除本地配对</button>
                          </div>
                          <p>配对码在管理台“设备”页面生成。设备密钥只保存在当前客户端，用于校验设备身份，不会随任务发送给模型或写入任务 metadata。</p>
                          {deviceMessage ? <p className="settings-message">{deviceMessage}</p> : null}
                        </div>
                      </section>
                    </>
                  )}
                </>
              ) : null}
              {settingsTab === 'logs' ? (
                <>
                  <h1>日志</h1>
                  <section className="settings-block">
                    <h2>服务日志</h2>
                    <div className="settings-kv"><span>日志目录</span><strong>{String(runtime.logDir || '-')}</strong></div>
                    <div className="settings-kv"><span>日志文件</span><strong>{logSources.map((source) => source.name).join('、') || '-'}</strong></div>
                    <div className="settings-row"><button className="primary-setting-button" onClick={() => setPage('logs')}>查看服务日志</button></div>
                  </section>
                </>
              ) : null}
              {settingsTab === 'models' ? (
                <>
                  <h1>模型与权限</h1>
                  <section className="settings-block">
                    <h2>现有模型</h2>
                    {modelOptions.map((item) => (
                      <div className="settings-kv" key={item.id}><span>{item.id}</span><strong>{item.title}</strong></div>
                    ))}
                    {!modelOptions.length ? <div className="settings-row"><span>暂无模型配置，请先添加模型。</span></div> : null}
                  </section>
                  <section className="settings-block">
                    <h2>权限配置</h2>
                    <div className="settings-kv"><span>当前模式</span><strong>{permissionOptions.find((item) => item.value === permission)?.label || permission}</strong></div>
                    <div className="settings-kv"><span>后台字段</span><strong>clawagent.local.permission-mode = {runtimeConfig?.local?.permissionMode || '-'}</strong></div>
                  </section>
                  <section className="settings-block">
                    <h2>添加模型</h2>
                    <div className="model-form">
                      <input placeholder="模型 ID，例如 deepseek-v4" value={newModel.id} onChange={(event) => setNewModel({ ...newModel, id: event.target.value })} />
                      <input placeholder="供应商，例如 deepseek" value={newModel.provider} onChange={(event) => setNewModel({ ...newModel, provider: event.target.value })} />
                      <input placeholder="Base URL" value={newModel.baseUrl} onChange={(event) => setNewModel({ ...newModel, baseUrl: event.target.value })} />
                      <input placeholder="模型名称" value={newModel.model} onChange={(event) => setNewModel({ ...newModel, model: event.target.value })} />
                      <input placeholder="API Key，可留空" type="password" value={newModel.apiKey} onChange={(event) => setNewModel({ ...newModel, apiKey: event.target.value })} />
                      <button type="button" onClick={() => void saveNewModel()}>保存模型</button>
                    </div>
                  </section>
                </>
              ) : null}
            </div>
          </section>
        </section>
      ) : null}

      {page === 'chat' ? (
        <>
          {showRight ? <div className="resize-handle right-resize" onPointerDown={(event) => startResize('right', event)} /> : <div className="resize-handle right-resize hidden-pane" />}
          {!showRight ? (
            <button className="floating-pane-toggle right" title="显示右侧栏" onClick={() => setShowRight(true)}>
              <PanelRightOpen size={15} />
            </button>
          ) : null}
          <aside className={showRight ? 'right-panel' : 'right-panel hidden-pane'}>
            <header className="right-tabs">
              <button className={rightTab === 'review' ? 'active' : ''} onClick={() => setRightTab('review')}>文件审查</button>
              <button className={rightTab === 'files' ? 'active' : ''} onClick={() => setRightTab('files')}>文件查看</button>
            </header>
            {rightTab === 'review' ? (
              <section className="file-pane">
                <div className="empty-file-state">
                  <FileText size={22} />
                  <strong>暂无文件变更</strong>
                  <span>Agent 修改文件后，diff 和审查项会显示在这里。</span>
                </div>
              </section>
            ) : (
              <section className="file-pane">
                <div className="empty-file-state">
                  <Code2 size={22} />
                  <strong>暂无打开文件</strong>
                  <span>选择任务产物或文件后，在这里预览内容。</span>
                </div>
              </section>
            )}
          </aside>
        </>
      ) : null}

      {newChatOpen ? (
        <div className="modal-backdrop" onClick={() => setNewChatOpen(false)}>
          <section className="modal-card new-chat-card" onClick={(event) => event.stopPropagation()}>
            <header>
              <div>
                <span className="mini-label">New Conversation</span>
                <h2>新建对话</h2>
              </div>
              <button onClick={() => setNewChatOpen(false)}>关闭</button>
            </header>
            <p>新建对话只创建普通聊天，不绑定项目目录；需要绑定本地代码目录时，请使用“添加项目”。</p>
            <button className="conversation-start-card" onClick={() => startConversation(null)}>
              <MessageSquare size={16} />
              <span>
                <strong>开始普通对话</strong>
                <em>不读取 workspace 文件，不附带本地命令上下文</em>
              </span>
            </button>
          </section>
        </div>
      ) : null}

      {newProjectOpen ? (
        <div className="modal-backdrop" onClick={() => setNewProjectOpen(false)}>
          <section className="modal-card" onClick={(event) => event.stopPropagation()}>
            <header>
              <div>
                <span className="mini-label">New Project</span>
                <h2>添加项目</h2>
              </div>
              <button onClick={() => setNewProjectOpen(false)}>关闭</button>
            </header>
            <p>选择本地项目目录。桌面端会打开系统文件夹选择器；浏览器访问时没有 Electron 权限，不能直接拉起本地文件管理器。</p>
            {directoryMessage ? <p className="modal-warning">{directoryMessage}</p> : null}
            <div className="modal-open-row">
              <button className="wide-open-button" onClick={() => void browseWorkspace(true)}>
                <FolderOpen size={16} />
                选择项目目录
              </button>
            </div>
          </section>
        </div>
      ) : null}
      <input
        ref={directoryInputRef}
        className="hidden-directory-input"
        type="file"
        multiple
        onChange={(event) => handleBrowserDirectorySelection(event.target.files)}
        {...{ webkitdirectory: '', directory: '' }}
      />
    </main>
  );
}
