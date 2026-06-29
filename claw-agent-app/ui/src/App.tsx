import {
  ArrowDown,
  ArrowUp,
  Bot,
  ChevronRight,
  Code2,
  FileText,
  FolderOpen,
  Image,
  KeyRound,
  Loader2,
  MessageSquare,
  Package,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Paperclip,
  Plus,
  Send,
  Settings,
  TerminalSquare,
  User,
} from 'lucide-react';
import { FormEvent, PointerEvent, useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AgentEvent, AgentMessage, AgentSession, ClientConfig, RuntimeConfig, SystemLogLine, SystemLogSource, Workspace, api, streamTask } from './api';

type ChatLine = {
  role: 'user' | 'assistant' | 'system';
  content: string;
  kind?: 'message' | 'event';
  eventName?: string;
  status?: string;
  detail?: string;
  createdAt?: string;
  toolName?: string;
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
      setServerUrl?: (serverUrl: string, options?: { check?: boolean }) => Promise<ClientConfig>;
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

export function App() {
  const [runtime, setRuntime] = useState<Record<string, unknown>>({});
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfig | null>(null);
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
  const [taskStatus, setTaskStatus] = useState('');
  const [error, setError] = useState('');
  const [newChatOpen, setNewChatOpen] = useState(false);
  const [newProjectOpen, setNewProjectOpen] = useState(false);
  const [page, setPage] = useState<'chat' | 'settings' | 'logs'>('chat');
  const [settingsTab, setSettingsTab] = useState<'general' | 'connection' | 'projects' | 'logs' | 'models'>('general');
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
  const [attachments, setAttachments] = useState<AttachmentItem[]>([]);
  const [toolPickerOpen, setToolPickerOpen] = useState(false);
  const [plusOpen, setPlusOpen] = useState(false);
  const [plusMenuPage, setPlusMenuPage] = useState<'main' | 'plugins'>('main');
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

  useEffect(() => {
    void refresh();
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
    const closeFloatingMenus = (event: globalThis.PointerEvent) => {
      if (composerRef.current?.contains(event.target as Node)) return;
      const target = event.target as Element;
      if (target.closest?.('.session-context-menu')) return;
      setPlusOpen(false);
      setToolPickerOpen(false);
      setPlusMenuPage('main');
      setSessionMenu(null);
    };
    document.addEventListener('pointerdown', closeFloatingMenus, true);
    return () => document.removeEventListener('pointerdown', closeFloatingMenus, true);
  }, []);

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
        edition: 'remote',
        activeServerUrl: window.location.origin,
        configExists: false,
      };
      setClientConfig(browserConfig);
      setServerUrlDraft(browserConfig.serverUrl);
      return;
    }
    const config = await window.clawAgentApp.getClientConfig();
    setClientConfig(config);
    setServerUrlDraft(config.serverUrl);
  }

  async function saveServerUrl(check = true) {
    if (!window.clawAgentApp?.setServerUrl) {
      setServerConfigMessage('浏览器访问不能修改桌面客户端服务器地址。');
      return;
    }
    setServerConfigMessage(check ? '正在保存并切换服务器...' : '正在保存服务器地址，不做连通性检查...');
    try {
      const saved = await window.clawAgentApp.setServerUrl(serverUrlDraft, { check });
      setClientConfig(saved);
      setServerUrlDraft(saved.serverUrl);
      setServerConfigMessage(check
        ? `已切换到 ${saved.edition === 'local' ? '本地模式' : '远程模式'}：${saved.serverUrl}`
        : `已保存 ${saved.edition === 'local' ? '本地模式' : '远程模式'}地址：${saved.serverUrl}。下次启动或手动刷新后生效。`);
    } catch (error) {
      setServerConfigMessage(error instanceof Error ? error.message : '服务器地址保存失败。');
    }
  }

  async function resetServerUrl() {
    setServerUrlDraft('http://127.0.0.1:17891');
    if (!window.clawAgentApp?.setServerUrl) {
      setServerConfigMessage('已恢复输入框为本地默认地址，浏览器模式不能写入桌面客户端配置。');
      return;
    }
    setServerConfigMessage('正在恢复本地默认地址...');
    try {
      const saved = await window.clawAgentApp.setServerUrl('http://127.0.0.1:17891');
      setClientConfig(saved);
      setServerUrlDraft(saved.serverUrl);
      setServerConfigMessage(`已恢复本地模式：${saved.serverUrl}`);
    } catch (error) {
      setServerConfigMessage(error instanceof Error ? error.message : '恢复本地默认地址失败。');
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
    const [messages, events] = await Promise.all([
      api.sessionMessages(item.id, 120),
      api.sessionEvents(item.id, 200),
    ]);
    const messageLines = messages.map((message: AgentMessage): ChatLine => ({
      role: messageRole(message.role),
      content: message.content || '',
      createdAt: message.createdAt,
    }));
    const eventLines = compactEventLines(events
      .map(eventToChatLine)
      .filter((line): line is ChatLine => Boolean(line)));
    setLines([...messageLines, ...eventLines].sort((left, right) => {
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

  function appendAssistantDelta(content: string) {
    setLines((current) => {
      const next = [...current];
      for (let index = next.length - 1; index >= 0; index -= 1) {
        if (next[index].role === 'assistant' && next[index].kind !== 'event') {
          next[index] = { ...next[index], content: `${next[index].content}${content}` };
          return next;
        }
      }
      return [...next, { role: 'assistant', content }];
    });
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
    let activeSession = session;
    let completed = false;
    setPendingSessionCreate(!activeSession);
    setTaskStatus('正在提交任务...');
    setError('');
    setInput('');
    setLines((current) => [...current, { role: 'user', content: text }]);
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
          if (isVisibleSession) appendAssistantDelta(String(data.content || ''));
          return;
        }
        if (!isVisibleSession) return;
        const status = appendProcessLine(eventName, data);
        if (status) setTaskStatus(status);
      });
      setSessions(await api.sessions());
      completed = true;
    } catch (err) {
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

  function settingsSubPageLabel() {
    const labels: Record<string, string> = {
      general: '常规',
      connection: '连接',
      projects: '项目',
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
          <button className={page === 'logs' ? 'footer-button active' : 'footer-button'} onClick={() => setPage('logs')}>
            <TerminalSquare size={15} />
            服务日志
          </button>
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
                    <span><Code2 size={13} />本地模式</span>
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
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                {line.content || (currentSessionBusy && line.role === 'assistant' ? '正在处理...' : '')}
                      </ReactMarkdown>
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
                  setInput((current) => `${current.trimEnd()} #计划模式 `);
                  setPlusOpen(false);
                }}>
                  <Code2 size={14} />
                  <span>计划模式</span>
                </button>
                <button type="button" onClick={() => {
                  setInput((current) => `${current.trimEnd()} #追求目标 `);
                  setPlusOpen(false);
                }}>
                  <KeyRound size={14} />
                  <span>追求目标</span>
                </button>
                <button type="button" onClick={() => setPlusMenuPage('plugins')}>
                  <Package size={14} />
                  <span>插件</span>
                  <ChevronRight size={13} />
                </button>
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
                </>
              ) : null}
              {settingsTab === 'connection' ? (
                <>
                  <h1>连接</h1>
                  <section className="settings-block">
                    <h2>服务器地址</h2>
                    <div className="settings-kv"><span>连接类型</span><strong>{clientConfig?.edition === 'remote' ? '外部服务' : '本地默认'}</strong></div>
                    <div className="settings-kv"><span>实际运行地址</span><strong>{clientConfig?.activeServerUrl || window.location.origin}</strong></div>
                    <div className="settings-kv"><span>保存目标地址</span><strong>{clientConfig?.serverUrl || '未保存，使用当前访问地址'}</strong></div>
                    <div className="settings-kv"><span>客户端配置文件</span><strong>{clientConfig?.configExists ? clientConfig.configPath : '未创建，正在使用默认本地地址'}</strong></div>
                    {clientConfig?.startupConnectionError ? (
                      <div className="settings-alert">{clientConfig.startupConnectionError}</div>
                    ) : null}
                    <div className="server-form">
                      <input value={serverUrlDraft} onChange={(event) => setServerUrlDraft(event.target.value)} placeholder="http://127.0.0.1:17891 或企业服务地址" />
                      <div className="server-actions">
                        <button type="button" onClick={() => void saveServerUrl(true)}>保存并切换</button>
                        <button type="button" onClick={() => void saveServerUrl(false)}>仅保存不检查</button>
                        <button type="button" onClick={() => void resetServerUrl()}>恢复本地默认地址</button>
                      </div>
                      <p>本地默认保存地址是 http://127.0.0.1:17891；如果端口被占用，桌面端会启动到后续空闲端口，实际运行地址以上方为准。外部服务地址只作为连接目标保存。</p>
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
