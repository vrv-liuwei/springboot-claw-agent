import {
  BarChart3,
  Bot,
  Check,
  CheckCircle,
  ChevronDown,
  ChevronRight,
  Copy,
  ChevronUp,
  Circle,
  Clock,
  Database,
  Download,
  File as FileIcon,
  FileText,
  Link2,
  Loader2,
  MessageSquare,
  Monitor,
  PanelLeftClose,
  PanelLeftOpen,
  PanelRightClose,
  PanelRightOpen,
  Plug,
  Play,
  Plus,
  RefreshCw,
  Search,
  ScrollText,
  Send,
  Settings,
  ShieldCheck,
  Square,
  Trash2,
  Upload,
  Wrench,
  X,
  XCircle,
  Zap,
} from 'lucide-react';
import Editor, { DiffEditor } from '@monaco-editor/react';
import { Fragment, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { api } from './api';
import type {
  AgentEvent,
  AgentMessage,
  AgentOrchestrationGraphView,
  AgentSession,
  AgentStep,
  AgentTask,
  ApiTokenCreateResponse,
  ApiTokenView,
  AttachmentParseResult,
  AutomationDefinition,
  AutomationRun,
  AutomationUpsertRequest,
  ChannelConnectivityStatus,
  ChannelAdapterDescriptor,
  ChannelDefinition,
  ChannelInboundResult,
  ChannelOutboundTestResponse,
  ChannelStreamStatus,
  ChannelUserBindingView,
  CostConfigView,
  CostRuleView,
  FailureAnalysisView,
  FileChangeView,
  FileReviewView,
  DevelopmentTaskSummary,
  DevicePairingCodeResponse,
  DeviceView,
  HealthStatus,
  KnowledgeDocument,
  KnowledgeProviderView,
  LocalHealthView,
  LocalUserSessionView,
  LocalUserView,
  KnowledgeSearchHit,
  MemoryHitLog,
  MemoryItem,
  MemorySearchHit,
  MemoryUpsertRequest,
  ManagedProcessLogsView,
  ManagedProcessView,
  McpServerConfig,
  ModelApiTestResponse,
  ModelConfigUpsertRequest,
  McpServerRegistration,
  ModelConfigUpdate,
  PlanDraft,
  PlanItem,
  PlanRevisionSummaryView,
  PlanTemplateView,
  PolicyConfigUpdate,
  ResumeStateView,
  RuntimeConfigSnapshot,
  SkillInstallRequest,
  SkillRegistration,
  SubAgentTaskRequest,
  TaskAuditView,
  SystemLogLine,
  SystemLogSource,
  TodoItem,
  TokenUsageSummary,
  ToolDefinition,
  VerificationCommandView,
  VectorStatusView,
} from './types';

const BRAND_LOGO_URL = '/admin/brand/clawagent-logo.svg';
const AGENT_AVATAR_URL = '/admin/brand/clawagent-avatar.svg';
const USER_AVATAR_URL = '/admin/brand/user-avatar.svg';
const CHAT_HISTORY_PAGE_SIZE = 100;
const TASK_SEARCH_FILTER_STORAGE_KEY = 'clawagent.taskSearch.filter';
const AUTH_SESSION_STORAGE_KEY = 'clawagent.auth.sessionToken';
const AUTH_USER_ID_STORAGE_KEY = 'clawagent.auth.userId';
const AUTH_USERNAME_STORAGE_KEY = 'clawagent.auth.username';
const AUTOMATION_RETRY_MAX_ATTEMPTS = 'retry.maxAttempts';
const AUTOMATION_RETRY_BACKOFF_SECONDS = 'retry.backoffSeconds';
const AUTOMATION_RETRY_PAUSE_AFTER_EXHAUSTED = 'retry.pauseAfterExhausted';
const AUTOMATION_RETRY_CURRENT_ATTEMPT = 'retry.currentAttempt';
const AUTOMATION_RETRY_LAST_ERROR = 'retry.lastError';
const SLASH_COMMANDS: SlashCommandDefinition[] = [
  { id: 'clear', title: '清模型上下文', description: '清掉后续模型可见的旧会话消息，历史仍可回放' },
  { id: 'compact', title: '压缩当前上下文', description: '生成会话摘要，后续任务带摘要继续执行', hint: '120' },
  { id: 'context', title: '查看上下文占用', description: '查看上下文版本、活跃消息数和 Token 估算' },
  { id: 'status', title: '查看运行状态', description: '汇总会话、任务、权限、MCP、工具和 Todo 状态' },
  { id: 'resume', title: '恢复任务', description: '从最近一个可恢复任务继续执行' },
  { id: 'plan', title: '计划模式', description: '先生成执行计划，再按步骤运行', hint: '实现一个功能并验证' },
  { id: 'workspace', title: '查看或切换工作区', description: '查看当前项目目录；带路径时切换当前聊天项目目录', hint: 'D:\\workspace\\project' },
  { id: 'approval', title: '权限模式', description: '查看或切换 ask/auto/full/custom 权限模式', hint: 'auto' },
  { id: 'mcp', title: 'MCP 状态', description: '查看 MCP 服务连接状态' },
  { id: 'tools', title: '工具列表', description: '查看当前注册工具' },
  { id: 'todo', title: 'Todo 状态', description: '查看当前会话 Todo' },
  { id: 'diff', title: '文件变更', description: '查看最近任务文件变更列表' },
  { id: 'logs', title: '日志', description: '查看当前会话相关服务日志' },
];
const SLASH_COMMAND_MAP = new Map(SLASH_COMMANDS.map((command) => [command.id, command]));
const BUILTIN_CAPABILITIES: BuiltinCapability[] = [
  {
    id: 'agent',
    title: 'Agent 任务',
    description: '创建任务、读取任务状态、恢复任务和汇总执行结果。',
    defaultParams: 'sessionId、taskId、metadata',
    auditPolicy: '记录任务事件、恢复点、最终结果和失败原因。',
    toolIds: ['builtin.task.', 'builtin.agent.'],
  },
  {
    id: 'todo',
    title: 'Todo 规划',
    description: '创建计划、更新步骤状态，并把工具调用和 Todo 绑定。',
    defaultParams: 'taskId、todoId、itemOrder、status',
    auditPolicy: '记录 todo.create_plan / todo.update_item 事件。',
    toolIds: ['builtin.todo.'],
  },
  {
    id: 'read',
    title: '文件读取',
    description: '读取工作区文件、列目录和查看文件元信息。',
    defaultParams: 'path、cwd、allowedRoots、ignorePatterns',
    auditPolicy: '记录工具输入摘要和访问路径；敏感路径由 filesystem 拦截。',
    toolIds: ['builtin.filesystem.read', 'builtin.filesystem.list', 'builtin.filesystem.info'],
  },
  {
    id: 'search',
    title: '工作区搜索',
    description: '按文件名或文本内容搜索项目，默认遵守忽略规则。',
    defaultParams: 'query、path、maxResults、ignorePatterns',
    auditPolicy: '记录搜索范围和命中摘要，不把大结果直接塞入聊天正文。',
    toolIds: ['builtin.filesystem.search'],
  },
  {
    id: 'edit',
    title: '文件编辑',
    description: '写文件、生成备份、记录 diff，并支持任务内 rollback。',
    defaultParams: 'path、content、backup、blockedPatterns',
    auditPolicy: '记录 file.changed / rollback 事件，文件审查页展示 diff。',
    toolIds: ['builtin.filesystem.write', 'builtin.filesystem.rollback'],
  },
  {
    id: 'execute',
    title: '本机执行',
    description: '执行前台命令和管理后台进程，用于编译、测试、启动服务。',
    defaultParams: 'command、args、cwd、timeout、allowedRoots',
    auditPolicy: '记录命令、cwd、风险、退出码、stdout/stderr 摘要和进程日志。',
    toolIds: ['builtin.execute.', 'builtin.process.'],
  },
  {
    id: 'web',
    title: 'Web 提取',
    description: '通过内置 Web fetch 或 MCP/Skill 提取外部信息。',
    defaultParams: 'url、method、timeout、allowList',
    auditPolicy: '记录 URL、状态码和安全拦截结果，默认拒绝内网 SSRF。',
    toolIds: ['builtin.web.', 'web.', 'fetch'],
  },
  {
    id: 'browser',
    title: '浏览器交互',
    description: '为后续桌面/浏览器自动化预留页面交互能力域。',
    defaultParams: 'url、selector、action、screenshot',
    auditPolicy: '后续接入时记录页面动作、截图 artifact 和用户确认结果。',
    toolIds: ['builtin.browser.', 'browser.'],
  },
  {
    id: 'vscode',
    title: 'IDE 能力',
    description: '为后续 VS Code/桌面端联动预留编辑器能力域。',
    defaultParams: 'workspace、file、range、command',
    auditPolicy: '后续接入时记录 IDE 命令和文件范围。',
    toolIds: ['builtin.vscode.', 'vscode.'],
  },
];

type NavKey = 'chat' | 'overview' | 'sessions' | 'automations' | 'knowledge' | 'memory' | 'skills' | 'channels' | 'auth' | 'devices' | 'config' | 'logs' | 'audit' | 'nodes';
type DetailTab = 'tasks' | 'messages' | 'events' | 'todos' | 'tokens';
type ToolStatus = 'running' | 'completed' | 'failed';
type KnowledgeSearchMode = 'keyword' | 'vector' | 'hybrid';
type MemorySearchMode = 'keyword' | 'vector' | 'hybrid';
type TaskSearchMode = 'tasks' | 'steps';
type ConfigTab = 'models' | 'embedding' | 'memory' | 'cost' | 'capabilities' | 'local';
type SkillTab = 'tools' | 'mcp' | 'skills';
type SetupWizardStep = { label: string; done: boolean; detail: string };

type BuiltinCapability = {
  id: string;
  title: string;
  description: string;
  defaultParams: string;
  auditPolicy: string;
  toolIds: string[];
};

type SystemLogFilter = {
  from: string;
  to: string;
  level: string;
  keyword: string;
  logger: string;
  userId: string;
  sessionId: string;
  taskId: string;
  toolId: string;
  riskLevel: string;
  limit: number;
};

type AuditEventFilter = {
  from: string;
  to: string;
  level: string;
  type: string;
  sessionId: string;
  taskId: string;
  userId: string;
  channelId: string;
  toolId: string;
  riskLevel: string;
  detailKey: string;
  detailValue: string;
  q: string;
  limit: number;
};

type TaskStepSearchFilter = {
  mode: TaskSearchMode;
  query: string;
  status: string;
  channelId: string;
  userId: string;
  sessionId: string;
  taskId: string;
  toolId: string;
  riskLevel: string;
  limit: number;
};

type ToolCallView = {
  stepId?: string;
  toolId?: string;
  status: ToolStatus;
  approvalRequested?: boolean;
  approvalKey?: string;
  riskLevel?: string;
  output?: string;
  outputPreview?: string;
  outputLength?: number;
  elapsedMs?: number;
  error?: string;
  todoId?: string;
  todoOrder?: string;
  todoTitle?: string;
  message?: string;
};

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  requestInput?: string;
  attachments?: ChatAttachment[];
  taskId?: string;
  planId?: string;
  plan?: PlanDraft;
  status?: string;
  progress?: string;
  createdAt: number;
  finishedAt?: number;
  durationMs?: number;
  tokenUsage?: TokenUsageSummary;
  toolCalls: ToolCallView[];
  toolsCollapsed: boolean;
};

type SubmitOptions = {
  inputOverride?: string;
  extraApprovedToolIds?: string[];
  showUserMessage?: boolean;
  assistantProgress?: string;
  resumeTaskId?: string;
  projectPathOverride?: string;
};

type AutomationRunDetail = {
  run: AutomationRun;
  task?: AgentTask;
  messages: AgentMessage[];
  events: AgentEvent[];
  loading: boolean;
  error?: string;
};

type TaskDetail = {
  task?: AgentTask;
  messages: AgentMessage[];
  events: AgentEvent[];
  tokenUsage?: TokenUsageSummary;
  developmentSummary?: DevelopmentTaskSummary;
  taskAudit?: TaskAuditView;
  subTasks?: AgentTask[];
  orchestrationGraph?: AgentOrchestrationGraphView;
  loading: boolean;
  error?: string;
};

type ApprovalSettings = {
  allowHighRiskTools: boolean;
  approvedToolIds: string[];
};

type ApprovalMode = 'ask' | 'auto' | 'full' | 'custom';

type SlashCommandId =
  | 'clear'
  | 'compact'
  | 'context'
  | 'status'
  | 'resume'
  | 'plan'
  | 'workspace'
  | 'approval'
  | 'mcp'
  | 'tools'
  | 'todo'
  | 'diff'
  | 'logs';

type SlashCommandDefinition = {
  id: SlashCommandId;
  title: string;
  description: string;
  hint?: string;
};

type ComposerAttachment = {
  id: string;
  name: string;
  size: number;
  type: string;
  file: File;
  previewUrl?: string;
};

type ChatAttachment = {
  id: string;
  name: string;
  size: number;
  kind?: string;
  contentType?: string;
  previewUrl?: string;
  viewUrl?: string;
  downloadUrl?: string;
  message?: string;
  knowledgeDocumentId?: string;
  knowledgeProvider?: string;
  providerDocumentId?: string;
};

function emptyTokenUsage(): TokenUsageSummary {
  return {
    callCount: 0,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    byModel: {},
    byPhase: {},
  };
}

function todayInputValue() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

function defaultSystemLogFilter(): SystemLogFilter {
  const today = todayInputValue();
  return {
    from: today,
    to: today,
    level: '',
    keyword: '',
    logger: '',
    userId: '',
    sessionId: '',
    taskId: '',
    toolId: '',
    riskLevel: '',
    limit: 100,
  };
}

function defaultAuditEventFilter(): AuditEventFilter {
  const today = todayInputValue();
  return {
    from: today,
    to: today,
    level: '',
    type: '',
    sessionId: '',
    taskId: '',
    userId: '',
    channelId: '',
    toolId: '',
    riskLevel: '',
    detailKey: '',
    detailValue: '',
    q: '',
    limit: 100,
  };
}

function defaultTaskStepSearchFilter(): TaskStepSearchFilter {
  return {
    mode: 'tasks',
    query: '',
    status: '',
    channelId: '',
    userId: '',
    sessionId: '',
    taskId: '',
    toolId: '',
    riskLevel: '',
    limit: 100,
  };
}

function readTaskStepSearchFilter(): TaskStepSearchFilter {
  const fallback = defaultTaskStepSearchFilter();
  try {
    const raw = window.localStorage.getItem(TASK_SEARCH_FILTER_STORAGE_KEY);
    if (!raw) return fallback;
    const saved = JSON.parse(raw) as Partial<TaskStepSearchFilter>;
    // 历史版本可能只保存了部分字段，这里按白名单合并，避免脏 JSON 或未知字段污染检索请求。
    return {
      ...fallback,
      mode: saved.mode === 'steps' ? 'steps' : 'tasks',
      query: typeof saved.query === 'string' ? saved.query : fallback.query,
      status: typeof saved.status === 'string' ? saved.status : fallback.status,
      channelId: typeof saved.channelId === 'string' ? saved.channelId : fallback.channelId,
      userId: typeof saved.userId === 'string' ? saved.userId : fallback.userId,
      sessionId: typeof saved.sessionId === 'string' ? saved.sessionId : fallback.sessionId,
      taskId: typeof saved.taskId === 'string' ? saved.taskId : fallback.taskId,
      toolId: typeof saved.toolId === 'string' ? saved.toolId : fallback.toolId,
      riskLevel: typeof saved.riskLevel === 'string' ? saved.riskLevel : fallback.riskLevel,
      limit: typeof saved.limit === 'number' && Number.isFinite(saved.limit)
        ? Math.min(Math.max(saved.limit, 1), 500)
        : fallback.limit,
    };
  } catch {
    return fallback;
  }
}

function defaultChannelDraft(): ChannelDefinition {
  return {
    id: '',
    name: '',
    type: 'api',
    enabled: true,
    approvalMode: 'ask',
    approvedToolIds: [],
    inboundPath: '',
    metadata: {},
  };
}

const DEFAULT_PAGE_LIMIT = 100;
const CHAT_TASK_EVENT_LIMIT = 100;

type SseEvent = {
  name: string;
  data: Record<string, string>;
};

const navGroups: Array<{ title: string; items: Array<{ key: NavKey; label: string; icon: typeof MessageSquare }> }> = [
  { title: '对话', items: [{ key: 'chat', label: '聊天', icon: MessageSquare }] },
  {
    title: '运行',
    items: [
      { key: 'overview', label: '概览', icon: BarChart3 },
      { key: 'sessions', label: '会话', icon: FileText },
      { key: 'automations', label: '定时任务', icon: Clock },
    ],
  },
  {
    title: '能力',
    items: [
      { key: 'knowledge', label: '知识库', icon: Database },
      { key: 'memory', label: '记忆', icon: ScrollText },
      { key: 'skills', label: '技能', icon: Zap },
      { key: 'channels', label: '通道', icon: Link2 },
      { key: 'auth', label: '授权', icon: ShieldCheck },
      { key: 'devices', label: '设备', icon: Monitor },
    ],
  },
  {
    title: '系统',
    items: [
      { key: 'config', label: '配置', icon: Settings },
      { key: 'logs', label: '日志', icon: ScrollText },
      { key: 'audit', label: '审计', icon: ShieldCheck },
      { key: 'nodes', label: '节点', icon: Monitor },
    ],
  },
];

const pageMeta: Record<NavKey, { title: string; subtitle: string }> = {
  chat: { title: '聊天', subtitle: '当前会话、工具调用链路和 Todo 执行进度。' },
  overview: { title: '概览', subtitle: 'Runtime 健康状态、能力数量、会话与异常任务概览。' },
  sessions: { title: '会话', subtitle: '查看历史会话、消息、任务、日志和 Token 使用量。' },
  automations: { title: '定时任务', subtitle: '管理智能体定时任务、周期自动化和手动触发记录。' },
  knowledge: { title: '知识库', subtitle: '管理本地文件入库、向量检索调试、下载和删除。' },
  memory: { title: '记忆', subtitle: '维护长期记忆、候选审核、命中记录和检索调试。' },
  skills: { title: '技能', subtitle: '统一查看系统工具、MCP Server 和本地 Skill。' },
  channels: { title: '通道', subtitle: '管理 WebUI、API、飞书、钉钉等接入通道和审批策略。' },
  auth: { title: '授权', subtitle: '管理本地主服务 API Token；完整用户和设备接入后续增强。' },
  devices: { title: '设备', subtitle: '登记本地客户端、桌面壳和外部接入端，为后续设备配对做准备。' },
  config: { title: '配置', subtitle: '运行配置与本地 .clawagent 目录状态。' },
  logs: { title: '日志', subtitle: '任务事件和运行日志入口。' },
  audit: { title: '审计', subtitle: '按任务、会话、类型和级别查询 Agent 执行事件。' },
  nodes: { title: '节点', subtitle: '未来用于展示 Worker、执行节点和隔离运行环境。' },
};

function formatDateTime(value?: string | number) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}

function formatClock(value?: number) {
  if (!value) return '';
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}

function formatDuration(ms?: number) {
  if (ms == null) return '';
  if (ms < 1000) return `${ms}ms`;
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}秒`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}分${seconds % 60}秒`;
}

function formatTokenCount(value?: number) {
  return new Intl.NumberFormat('zh-CN').format(value || 0);
}

function formatCost(value?: number, currency = 'USD') {
  if (value == null || !Number.isFinite(value)) return '-';
  return `${currency} ${value.toFixed(value >= 1 ? 2 : 4)}`;
}

function estimateTokenCost(usage: TokenUsageSummary, rule?: CostRuleView) {
  if (!rule) return undefined;
  return ((usage.promptTokens || 0) / 1_000_000) * (rule.inputPerMillion || 0)
    + ((usage.completionTokens || 0) / 1_000_000) * (rule.outputPerMillion || 0);
}

function findCostRule(model: string, cost?: CostConfigView) {
  const rules = cost?.rules || {};
  if (rules[model]) return rules[model];
  const normalized = model.toLowerCase();
  return Object.entries(rules).find(([key]) => key.toLowerCase() === normalized)?.[1];
}

function estimateUsageCost(usage?: TokenUsageSummary, cost?: CostConfigView) {
  if (!usage || !cost?.rules) return undefined;
  const byModel = Object.entries(usage.byModel || {});
  if (byModel.length) {
    const values = byModel
      .map(([model, item]) => estimateTokenCost(item, findCostRule(model, cost)))
      .filter((value): value is number => value != null);
    return values.length ? values.reduce((sum, value) => sum + value, 0) : undefined;
  }
  const directRule = Object.values(cost.rules)[0];
  return estimateTokenCost(usage, directRule);
}

function formatQuality(value?: string | number) {
  if (value == null || value === '') return '-';
  const numeric = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numeric)) return String(value);
  return `${Math.round(Math.max(0, Math.min(1, numeric)) * 100)}%`;
}

function riskClass(risk?: string) {
  if (!risk) return 'neutral';
  if (risk.toLowerCase() === 'high') return 'danger';
  if (risk.toLowerCase() === 'medium') return 'warning';
  return 'success';
}

function short(value?: string, max = 80) {
  if (!value) return '-';
  return value.length > max ? `${value.slice(0, max)}...` : value;
}

function searchPreview(value?: string, query?: string, max = 80) {
  if (!value) return '-';
  const text = value.replace(/\s+/g, ' ').trim();
  const keyword = (query || '').trim();
  if (!keyword) return short(text, max);
  const index = text.toLowerCase().indexOf(keyword.toLowerCase());
  if (index < 0 || text.length <= max) return short(text, max);
  // 结果表空间有限，围绕命中点裁剪片段，比固定截取开头更适合排查历史任务。
  const padding = Math.max(8, Math.floor((max - keyword.length) / 2));
  const start = Math.max(0, index - padding);
  const end = Math.min(text.length, start + max);
  return `${start > 0 ? '...' : ''}${text.slice(start, end)}${end < text.length ? '...' : ''}`;
}

function highlightSearchText(value?: string, query?: string) {
  const text = value || '-';
  const keyword = (query || '').trim();
  if (!keyword) return text;
  const lowerText = text.toLowerCase();
  const lowerKeyword = keyword.toLowerCase();
  const nodes = [];
  let cursor = 0;
  let matchIndex = lowerText.indexOf(lowerKeyword);
  while (matchIndex >= 0) {
    if (matchIndex > cursor) {
      nodes.push(text.slice(cursor, matchIndex));
    }
    const matched = text.slice(matchIndex, matchIndex + keyword.length);
    nodes.push(<mark className="search-highlight" key={`${matchIndex}-${matched}`}>{matched}</mark>);
    cursor = matchIndex + keyword.length;
    matchIndex = lowerText.indexOf(lowerKeyword, cursor);
  }
  if (cursor < text.length) {
    nodes.push(text.slice(cursor));
  }
  return nodes;
}

function countBy<T>(items: T[], selector: (item: T) => string | undefined) {
  return items.reduce<Record<string, number>>((result, item) => {
    const key = selector(item) || 'unknown';
    result[key] = (result[key] || 0) + 1;
    return result;
  }, {});
}

function topCountLabel(values: Record<string, number>) {
  const [key, count] = Object.entries(values).sort((left, right) => right[1] - left[1])[0] || [];
  return key ? `${key} ${count}` : '-';
}

function capabilityTools(capability: BuiltinCapability, tools: ToolDefinition[]) {
  return tools.filter((tool) => capability.toolIds.some((prefix) => tool.id.startsWith(prefix) || tool.id.includes(prefix)));
}

function capabilityRisk(tools: ToolDefinition[]) {
  if (tools.some((tool) => (tool.riskLevel || '').toLowerCase() === 'high')) return 'high';
  if (tools.some((tool) => (tool.riskLevel || '').toLowerCase() === 'medium')) return 'medium';
  if (tools.length) return 'low';
  return 'planned';
}

function capabilityEnabled(tools: ToolDefinition[]) {
  return tools.length > 0;
}

function toolPermissionLabel(tool: ToolDefinition, mode?: string, approvedToolIds: string[] = []) {
  const risk = (tool.riskLevel || '').toLowerCase();
  if (mode === 'full') return '完全访问';
  if (mode === 'auto') return risk === 'high' ? '自动批准高危' : '默认允许';
  if (mode === 'custom') return risk === 'high' ? (approvedToolIds.includes(tool.id) ? '白名单允许' : '需要审批') : '默认允许';
  return risk === 'high' ? '需要审批' : '默认允许';
}

function formatCheckpointPreview(value?: string, max = 480) {
  if (!value) return '';
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(0, 10)
    .join('\n')
    .slice(0, max);
}

function maskSecretValue(value?: string) {
  if (!value) return '-';
  if (value.length <= 10) return '***';
  return `${value.slice(0, 6)}...${value.slice(-4)}`;
}

function maskSecretObject(value: unknown): unknown {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value, (key, current) => {
    if (typeof current === 'string' && /authorization|api[_-]?key|token|secret|password/i.test(key)) {
      return maskSecretValue(current);
    }
    return current;
  }));
}

function prettyJson(value: unknown) {
  return JSON.stringify(maskSecretObject(value), null, 2);
}

function statusText(status?: string) {
  if (!status) return '';
  const normalized = status.toUpperCase();
  if (normalized === 'COMPLETED') return '已完成';
  if (normalized === 'CONTINUATION_REQUIRED') return '需继续';
  if (normalized === 'FAILED') return '失败';
  if (normalized === 'CANCELLED') return '已停止';
  if (normalized === 'RUNNING') return '执行中';
  return status;
}

function todoStatusText(status?: string) {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'completed') return '执行完成';
  if (normalized === 'running') return '执行中';
  if (normalized === 'failed') return '失败';
  return '未执行';
}

function normalizeEscapedNewlines(value: string) {
  return value
    .replace(/(^|[^:])\\\\r\\\\n/g, '$1\n')
    .replace(/(^|[^:])\\\\n/g, '$1\n')
    .replace(/(^|[^:])\\r\\n/g, '$1\n')
    .replace(/(^|[^:])\\n/g, '$1\n');
}

function parseSlashCommand(value: string): { id: SlashCommandId; args: string; raw: string } | undefined {
  const raw = value.trim();
  if (!raw || raw.includes('\n')) return undefined;
  const match = raw.match(/^\/([a-z][a-z0-9_-]*)(?:\s+(.+))?$/i);
  if (!match) return undefined;
  const id = match[1].toLowerCase() as SlashCommandId;
  if (!SLASH_COMMAND_MAP.has(id)) return undefined;
  return { id, args: (match[2] || '').trim(), raw };
}

function slashCommandQuery(value: string) {
  const raw = value.trim();
  if (!raw.startsWith('/') || raw.includes('\n')) return undefined;
  const match = raw.match(/^\/([a-z0-9_-]*)$/i);
  return match ? match[1].toLowerCase() : undefined;
}

function stripCommandQuotes(value: string) {
  return value.trim().replace(/^['"]|['"]$/g, '');
}

function markdownRows(rows: Array<[string, string | number | boolean | undefined | null]>) {
  return rows
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => `- **${key}**：${String(value)}`)
    .join('\n');
}

function commandMarkdown(title: string, rows: Array<[string, string | number | boolean | undefined | null]>, extra = '') {
  return [`### ${title}`, markdownRows(rows), extra].filter(Boolean).join('\n\n');
}

function todoExecutionLabel(data: Record<string, string | number | boolean | undefined>, fallback: string) {
  const title = typeof data.todoTitle === 'string' ? data.todoTitle : '';
  const order = data.todoOrder == null ? '' : String(data.todoOrder);
  if (!title) return fallback;
  return `正在执行 Todo ${order || '-'}：${title}`;
}

function isContinuationRequiredMessage(message: ChatMessage) {
  const status = (message.status || '').toUpperCase();
  return message.role === 'assistant'
    && Boolean(message.taskId)
    && (status === '需继续' || status === 'CONTINUATION_REQUIRED' || /达到单次执行上限|继续执行|未完成 Todo/.test(message.content));
}

function nextResumeTodo(todos: TodoItem[]) {
  return todos.find((todo) => ['running', 'pending', 'failed'].includes((todo.status || '').toLowerCase()));
}

function resumeTodoFromState(state?: ResumeStateView): TodoItem | undefined {
  if (!state?.canResume || !state.todoId) return undefined;
  return {
    id: state.todoId,
    taskId: state.resumeFromTaskId || state.taskId,
    itemOrder: state.todoOrder ? Number(state.todoOrder) : undefined,
    title: state.todoTitle,
    status: state.todoStatus,
  };
}

function taskIdListFromMessages(messages: ChatMessage[]) {
  return Array.from(new Set(
    messages
      .map((message) => (message.role === 'assistant' ? message.taskId : undefined))
      .filter((taskId): taskId is string => Boolean(taskId)),
  ));
}

function resumeTaskIdListFromMessages(messages: ChatMessage[]) {
  return Array.from(new Set(
    messages
      .filter(isContinuationRequiredMessage)
      .map((message) => message.taskId)
      .filter((taskId): taskId is string => Boolean(taskId)),
  ));
}

function groupTodosByTask(todos: TodoItem[]) {
  return todos.reduce<Record<string, TodoItem[]>>((result, todo) => {
    if (!todo.taskId) return result;
    result[todo.taskId] = [...(result[todo.taskId] || []), todo];
    return result;
  }, {});
}

function detailString(details: Record<string, unknown> | undefined, key: string) {
  const value = details?.[key];
  if (value == null || value === '') return undefined;
  return String(value);
}

function detailNumber(details: Record<string, unknown> | undefined, key: string) {
  const value = details?.[key];
  if (value == null || value === '') return undefined;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : undefined;
}

function toolCallFromEvent(event: AgentEvent): ToolCallView | undefined {
  const details = event.details || {};
  const type = event.type || '';
  if (!type.startsWith('tool.')) return undefined;
  const toolId = detailString(details, 'toolId') || event.message || '未知工具';
  const base: ToolCallView = {
    stepId: detailString(details, 'stepId') || event.id,
    toolId,
    status: 'running',
    riskLevel: detailString(details, 'riskLevel'),
    output: detailString(details, 'output'),
    outputPreview: detailString(details, 'outputPreview'),
    outputLength: detailNumber(details, 'outputLength'),
    elapsedMs: detailNumber(details, 'elapsedMs'),
    error: detailString(details, 'error'),
    todoId: detailString(details, 'todoId'),
    todoOrder: detailString(details, 'todoOrder'),
    todoTitle: detailString(details, 'todoTitle'),
    message: event.message,
  };
  if (type === 'tool.succeeded') return { ...base, status: 'completed', message: `调用成功 ${toolId}` };
  if (type === 'tool.failed') return { ...base, status: 'failed', error: base.error || event.message, message: `调用失败 ${toolId}` };
  if (type === 'tool.approval_requested') {
    return {
      ...base,
      approvalRequested: true,
      approvalKey: detailString(details, 'approvalKey'),
      message: `等待审批 ${toolId}`,
    };
  }
  if (type === 'tool.approval_granted') {
    return { ...base, approvalRequested: false, message: `已审批，继续调用 ${toolId}` };
  }
  if (type === 'tool.approval_rejected') {
    return { ...base, approvalRequested: false, status: 'failed', message: `已拒绝调用 ${toolId}` };
  }
  if (type === 'tool.started') return { ...base, message: `正在调用 ${toolId}` };
  return undefined;
}

function mergeToolCalls(calls: ToolCallView[]) {
  const merged = new Map<string, ToolCallView>();
  calls.forEach((call, index) => {
    const key = call.stepId || `${call.toolId || 'tool'}-${index}`;
    merged.set(key, { ...(merged.get(key) || {} as ToolCallView), ...call });
  });
  return Array.from(merged.values());
}

function toolCallsFromEvents(events: AgentEvent[]) {
  return mergeToolCalls(events.map(toolCallFromEvent).filter((call): call is ToolCallView => Boolean(call)));
}

function summarizeFileChanges(changes: FileChangeView[] = []) {
  const created = changes.filter((change) => (change.changeType || '').toLowerCase() === 'create').length;
  const modified = changes.filter((change) => ['modify', 'append', 'rollback'].includes((change.changeType || '').toLowerCase())).length;
  return { created, modified, total: changes.length };
}

function latestFileChanges(changes: FileChangeView[] = []) {
  const grouped = new Map<string, FileChangeView[]>();
  [...changes]
    .sort((left, right) => new Date(left.createdAt || 0).getTime() - new Date(right.createdAt || 0).getTime())
    .forEach((change) => {
      const key = normalizeFileChangePath(change.path);
      if (!key) return;
      grouped.set(key, [...(grouped.get(key) || []), change]);
    });
  return Array.from(grouped.values())
    .map((group) => {
      const latest = group[group.length - 1];
      return {
        ...latest,
        reviewStatus: latest.reviewStatus || fileReviewStatus(latest),
        supersededCount: latest.supersededCount ?? Math.max(0, group.length - 1),
      };
    })
    .sort((left, right) => new Date(right.createdAt || 0).getTime() - new Date(left.createdAt || 0).getTime());
}

function sameFileChanges(left?: FileChangeView[], right?: FileChangeView[]) {
  const first = left || [];
  const second = right || [];
  if (first.length !== second.length) return false;
  return first.every((change, index) => {
    const other = second[index];
    return Boolean(other)
      && change.id === other.id
      && change.path === other.path
      && change.changeType === other.changeType
      && change.createdAt === other.createdAt
      && change.reviewStatus === other.reviewStatus
      && change.supersededCount === other.supersededCount;
  });
}

function normalizeFileChangePath(path?: string) {
  return (path || '').replace(/\\/g, '/').toLowerCase();
}

function normalizeStreamChunk(value?: string) {
  if (!value) return '';
  const trimmed = value.trim();
  if (!trimmed || trimmed.toLowerCase() === 'null') return '';
  return value.replace(/^(null)+/i, '');
}

function readApprovalSettings(): ApprovalSettings {
  const rawToolIds = window.localStorage.getItem('clawagent.approval.approvedToolIds');
  let approvedToolIds: string[] = [];
  try {
    approvedToolIds = rawToolIds ? JSON.parse(rawToolIds) as string[] : [];
  } catch {
    approvedToolIds = [];
  }
  return {
    allowHighRiskTools: window.localStorage.getItem('clawagent.approval.allowHighRiskTools') === 'true',
    approvedToolIds: approvedToolIds.filter(Boolean),
  };
}

function readApprovalMode(): ApprovalMode {
  const saved = window.localStorage.getItem('clawagent.approval.mode');
  if (saved === 'ask' || saved === 'auto' || saved === 'full' || saved === 'custom') return saved;
  return window.localStorage.getItem('clawagent.approval.allowHighRiskTools') === 'true' ? 'full' : 'ask';
}

function isApprovalMode(value?: string): value is ApprovalMode {
  return value === 'ask' || value === 'auto' || value === 'full' || value === 'custom';
}

function allowHighRiskForMode(mode: ApprovalMode) {
  return mode === 'auto' || mode === 'full';
}

function approvalBlockedTool(message: ChatMessage): ToolCallView | undefined {
  return message.toolCalls.find((call) => call.approvalRequested && !!call.toolId && !!call.stepId)
    || message.toolCalls.find((call) => (
    call.status === 'failed'
    && (call.riskLevel || '').toLowerCase() === 'high'
    && /高危工具未审批/.test(call.error || call.message || '')
    && !!call.toolId
  ));
}

function formatFileSize(size: number) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function formatAttachmentName(name: string, maxLength = 30) {
  if (name.length <= maxLength) return name;
  const dotIndex = name.lastIndexOf('.');
  const extension = dotIndex > 0 ? name.slice(dotIndex) : '';
  const tail = extension && extension.length <= 10 ? extension : name.slice(-8);
  const headLength = Math.max(8, maxLength - tail.length - 3);
  return `${name.slice(0, headLength)}...${tail}`;
}

function toChatAttachments(attachments: ComposerAttachment[], parsed: AttachmentParseResult[] = []): ChatAttachment[] {
  return attachments.map((attachment, index) => {
    const result = parsed[index] || parsed.find((item) => item.name === attachment.name);
    const attachmentId = result?.id || attachment.id;
    const kind = result?.kind || (attachment.type.startsWith('image/') ? 'image' : undefined);
    const contentType = result?.contentType || attachment.type;
    const hasServerFile = Boolean(result?.id);
    const image = isImageAttachment({ kind, contentType });
    return {
      id: attachmentId,
      name: result?.name || attachment.name,
      size: result?.size ?? attachment.size,
      kind,
      contentType,
      previewUrl: hasServerFile && image ? api.attachmentViewUrl(attachmentId) : attachment.previewUrl,
      viewUrl: hasServerFile ? api.attachmentViewUrl(attachmentId) : attachment.previewUrl,
      downloadUrl: hasServerFile ? api.attachmentDownloadUrl(attachmentId) : undefined,
      message: result?.message,
      knowledgeDocumentId: result?.knowledgeDocumentId,
      knowledgeProvider: result?.knowledgeProvider,
      providerDocumentId: result?.providerDocumentId,
    };
  });
}

function attachmentMetadata(attachments: AttachmentParseResult[]) {
  return attachments.map((attachment) => ({
    type: attachment.kind || (attachment.contentType?.startsWith('image/') ? 'image' : 'file'),
    attachmentId: attachment.id,
    fileName: attachment.name,
    mimeType: attachment.contentType,
    source: 'admin',
    id: attachment.id,
    name: attachment.name,
    contentType: attachment.contentType,
    size: attachment.size,
    kind: attachment.kind,
    storagePath: attachment.storagePath,
    knowledgeDocumentId: attachment.knowledgeDocumentId,
    provider: attachment.knowledgeProvider,
    providerDocumentId: attachment.providerDocumentId,
  }));
}

function attachmentsFromMessageMetadata(metadata?: Record<string, string>): ChatAttachment[] {
  const raw = metadata?.attachments;
  if (!raw) return [];
  try {
    const items = JSON.parse(raw) as Array<AttachmentParseResult & {
      provider?: string;
      attachmentId?: string;
      fileName?: string;
      mimeType?: string;
      type?: string;
      localPath?: string;
      sourceUrl?: string;
      error?: string;
    }>;
    return items
      .filter((item) => item?.id || item?.attachmentId || item?.localPath || item?.sourceUrl || item?.fileName || item?.name)
      .map((item) => {
        const id = item.id || item.attachmentId || item.localPath || item.sourceUrl || item.fileName || item.name || 'attachment';
        const name = item.name || item.fileName || id;
        const kind = item.kind || item.type;
        const contentType = item.contentType || item.mimeType;
        const hasServerFile = Boolean(item.id || item.attachmentId);
        const image = isImageAttachment({ kind, contentType });
        return {
          id,
          name,
          size: item.size || 0,
          kind,
          contentType,
          previewUrl: hasServerFile && image ? api.attachmentViewUrl(item.id || item.attachmentId || id) : undefined,
          viewUrl: hasServerFile ? api.attachmentViewUrl(item.id || item.attachmentId || id) : item.sourceUrl,
          downloadUrl: hasServerFile ? api.attachmentDownloadUrl(item.id || item.attachmentId || id) : undefined,
          message: item.message || item.error,
          knowledgeDocumentId: item.knowledgeDocumentId,
          knowledgeProvider: item.knowledgeProvider || item.provider,
          providerDocumentId: item.providerDocumentId,
        };
      });
  } catch {
    return [];
  }
}

function isImageAttachment(attachment: Pick<ChatAttachment, 'kind' | 'contentType'>) {
  return attachment.kind === 'image' || Boolean(attachment.contentType?.startsWith('image/'));
}

function stripAttachmentParseBlock(content: string) {
  return content.replace(/\n\n\[附件解析结果\][\s\S]*$/u, '').trimEnd();
}

function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderMarkdown(markdown: string) {
  const lines = markdown.split(/\r?\n/);
  const html: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (!line.trim()) {
      i += 1;
      continue;
    }
    if (/^```/.test(line)) {
      const code: string[] = [];
      i += 1;
      while (i < lines.length && !/^```/.test(lines[i])) {
        code.push(lines[i]);
        i += 1;
      }
      i += 1;
      html.push(`<pre><code>${escapeHtml(code.join('\n'))}</code></pre>`);
      continue;
    }
    if (/^\|.+\|$/.test(line) && i + 1 < lines.length && /^\|[\s:-]+\|/.test(lines[i + 1])) {
      const headers = line.split('|').slice(1, -1).map((cell) => escapeHtml(cell.trim()));
      const rows: string[][] = [];
      i += 2;
      while (i < lines.length && /^\|.+\|$/.test(lines[i])) {
        rows.push(lines[i].split('|').slice(1, -1).map((cell) => escapeHtml(cell.trim())));
        i += 1;
      }
      html.push(`<table><thead><tr>${headers.map((h) => `<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${cell}</td>`).join('')}</tr>`).join('')}</tbody></table>`);
      continue;
    }
    if (/^#{1,3}\s+/.test(line)) {
      const level = Math.min(3, line.match(/^#+/)?.[0].length || 2);
      html.push(`<h${level}>${inlineMarkdown(line.replace(/^#{1,3}\s+/, ''))}</h${level}>`);
      i += 1;
      continue;
    }
    if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        items.push(`<li>${inlineMarkdown(lines[i].replace(/^\s*[-*]\s+/, ''))}</li>`);
        i += 1;
      }
      html.push(`<ul>${items.join('')}</ul>`);
      continue;
    }
    if (/^\s*\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+\.\s+/.test(lines[i])) {
        items.push(`<li>${inlineMarkdown(lines[i].replace(/^\s*\d+\.\s+/, ''))}</li>`);
        i += 1;
      }
      html.push(`<ol>${items.join('')}</ol>`);
      continue;
    }
    html.push(`<p>${inlineMarkdown(line)}</p>`);
    i += 1;
  }
  return html.join('');
}

function inlineMarkdown(value: string) {
  return escapeHtml(value)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^)]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
}

async function readSseStream(stream: ReadableStream<Uint8Array>, onEvent: (event: SseEvent) => void) {
  const reader = stream.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() || '';
    parts.forEach((part) => {
      const event = parseSseEvent(part);
      if (event) onEvent(event);
    });
  }
  if (buffer.trim()) {
    const event = parseSseEvent(buffer);
    if (event) onEvent(event);
  }
}

function parseSseEvent(chunk: string): SseEvent | null {
  let name = 'message';
  const dataLines: string[] = [];
  chunk.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('event:')) name = line.substring(6).trim();
    if (line.startsWith('data:')) dataLines.push(line.substring(5).trim());
  });
  if (!dataLines.length) return null;
  const raw = dataLines.join('\n');
  try {
    return { name, data: JSON.parse(raw) as Record<string, string> };
  } catch {
    return { name, data: { message: raw } };
  }
}

function draftFromConfig(config?: RuntimeConfigSnapshot): ModelConfigUpdate {
  const model = config?.model || {};
  const effective = config?.effectiveModel || {};
  const embedding = config?.embedding || {};
  const memoryExtraction = config?.memoryExtraction || {};
  const memoryGovernance = config?.memoryGovernance || {};
  const cost = config?.cost || {};
  const local = config?.local || {};
  return {
    mode: model.mode || 'llm',
    client: model.client || 'openai-compatible',
    defaultModel: model.defaultModel || effective.model || '',
    memoryModel: model.memoryModel || model.defaultModel || effective.model || '',
    visionModel: model.visionModel || '',
    planner: model.planner || 'react',
    provider: effective.provider || '',
    baseUrl: effective.baseUrl || '',
    model: effective.model || model.defaultModel || '',
    apiKey: effective.apiKey || '',
    temperature: effective.temperature ?? 0.2,
    timeoutSeconds: effective.timeoutSeconds ?? 60,
    vision: effective.vision ?? false,
    embeddingProvider: embedding.provider || '',
    embeddingBaseUrl: embedding.baseUrl || '',
    embeddingModel: embedding.model || '',
    embeddingApiKey: embedding.apiKey || '',
    embeddingDimensions: embedding.dimensions ?? 0,
    embeddingTimeoutSeconds: embedding.timeoutSeconds ?? 60,
    memoryExtractionEnabled: memoryExtraction.enabled ?? true,
    memoryExtractionMode: memoryExtraction.mode || 'after-task-async',
    memoryExtractionIntervalSeconds: memoryExtraction.intervalSeconds ?? 60,
    memoryExtractionBatchSize: memoryExtraction.batchSize ?? 100,
    memoryGovernanceStaleAfterDays: memoryGovernance.staleAfterDays ?? 30,
    memoryGovernanceVeryStaleAfterDays: memoryGovernance.veryStaleAfterDays ?? 180,
    memoryGovernanceAutoArchiveEnabled: memoryGovernance.autoArchiveEnabled ?? false,
    memoryGovernanceArchiveAfterDays: memoryGovernance.archiveAfterDays ?? 365,
    memoryGovernanceArchiveBelowQuality: memoryGovernance.archiveBelowQuality ?? 0.15,
    costCurrency: cost.currency || 'USD',
    costRules: cost.rules || {},
    localWorkspaceRoot: local.workspaceRoot || '.clawagent/workspace',
    localDefaultShell: local.defaultShell || defaultShellOption(),
    localPermissionMode: local.permissionMode || 'ask',
    localApprovedToolIds: local.approvedToolIds || [],
    localAllowedRoots: local.allowedRoots?.length ? local.allowedRoots : [local.workspaceRoot || '.clawagent/workspace'],
    localRecentProjects: local.recentProjects || [],
    localTestCommands: local.testCommands || [],
    localProjectTestCommands: local.projectTestCommands || {},
    localIgnorePatterns: local.ignorePatterns || [],
    localSensitivePathPatterns: local.sensitivePathPatterns || [],
  };
}

function defaultAutomationDraft(): AutomationUpsertRequest {
  return {
    name: '',
    prompt: '',
    scheduleType: 'INTERVAL',
    intervalSeconds: 3600,
    timezone: 'Asia/Shanghai',
    status: 'ENABLED',
    channelId: 'automation',
    userId: 'automation',
    metadata: {},
  };
}

function defaultShellOption() {
  return /win/i.test(navigator.platform || navigator.userAgent) ? 'powershell' : 'sh';
}

function defaultMemoryDraft(): MemoryUpsertRequest {
  return {
    userId: 'console',
    scopeType: 'session',
    scopeId: '',
    type: 'fact',
    status: 'pending',
    content: '',
    summary: '',
    importance: 0.5,
    confidence: 0.7,
    metadata: {},
  };
}

function memoryDraftFromItem(item: MemoryItem): MemoryUpsertRequest {
  return {
    id: item.id,
    userId: item.userId || 'console',
    scopeType: item.scopeType || 'session',
    scopeId: item.scopeId || '',
    type: item.type || 'fact',
    status: item.status || 'pending',
    content: item.content || '',
    summary: item.summary || '',
    sourceSessionId: item.sourceSessionId || '',
    sourceTaskId: item.sourceTaskId || '',
    importance: item.importance ?? 0.5,
    confidence: item.confidence ?? 0.7,
    metadata: item.metadata || {},
  };
}

function automationDraftFromDefinition(automation: AutomationDefinition): AutomationUpsertRequest {
  return {
    name: automation.name || '',
    prompt: automation.prompt || '',
    sessionId: automation.sessionId || '',
    channelId: automation.channelId || 'automation',
    userId: automation.userId || 'automation',
    scheduleType: automation.scheduleType || 'INTERVAL',
    cronExpression: automation.cronExpression || '',
    intervalSeconds: automation.intervalSeconds ?? 3600,
    timezone: automation.timezone || 'Asia/Shanghai',
    nextRunAt: automation.nextRunAt || '',
    status: automation.status || 'ENABLED',
    metadata: automation.metadata || {},
  };
}

function automationMetadataValue(source: AutomationUpsertRequest | AutomationDefinition, key: string) {
  return source.metadata?.[key] || '';
}

function automationMetadataBoolean(source: AutomationUpsertRequest | AutomationDefinition, key: string) {
  return automationMetadataValue(source, key) === 'true';
}

function withAutomationMetadata(draft: AutomationUpsertRequest, key: string, value?: string) {
  const metadata = { ...(draft.metadata || {}) };
  if (!value || !value.trim()) {
    delete metadata[key];
  } else {
    metadata[key] = value.trim();
  }
  return { ...draft, metadata };
}

export function App() {
  const [active, setActive] = useState<NavKey>('chat');
  const [navSearch, setNavSearch] = useState('');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => window.localStorage.getItem('clawagent.sidebarCollapsed') === 'true');
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>(() => {
    const saved = window.localStorage.getItem('clawagent.sidebarGroups');
    if (!saved) return {};
    try {
      return JSON.parse(saved) as Record<string, boolean>;
    } catch {
      return {};
    }
  });
  const [detailTab, setDetailTab] = useState<DetailTab>('tasks');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [health, setHealth] = useState<HealthStatus>({});
  const [sessions, setSessions] = useState<AgentSession[]>([]);
  const [selectedSessionId, setSelectedSessionId] = useState<string>();
  const [currentSessionId, setCurrentSessionId] = useState<string>();
  const [activeProjectPath, setActiveProjectPath] = useState(() => window.localStorage.getItem('clawagent.chat.activeProjectPath') || '');
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [tokenUsage, setTokenUsage] = useState<TokenUsageSummary>();
  const [taskTokenUsages, setTaskTokenUsages] = useState<Record<string, TokenUsageSummary>>({});
  const [sessionTodos, setSessionTodos] = useState<TodoItem[]>([]);
  const [tools, setTools] = useState<ToolDefinition[]>([]);
  const [mcpServers, setMcpServers] = useState<McpServerRegistration[]>([]);
  const [skills, setSkills] = useState<SkillRegistration[]>([]);
  const [mcpUpdating, setMcpUpdating] = useState<string>();
  const [mcpImportJson, setMcpImportJson] = useState('');
  const [mcpMessage, setMcpMessage] = useState<string>();
  const [skillUpdating, setSkillUpdating] = useState<string>();
  const [skillInstallText, setSkillInstallText] = useState('');
  const [skillMessage, setSkillMessage] = useState<string>();
  const [channels, setChannels] = useState<ChannelDefinition[]>([]);
  const [channelAdapters, setChannelAdapters] = useState<ChannelAdapterDescriptor[]>([]);
  const [channelDraft, setChannelDraft] = useState<ChannelDefinition>(() => defaultChannelDraft());
  const [channelLoading, setChannelLoading] = useState(false);
  const [channelSaving, setChannelSaving] = useState(false);
  const [channelAdapterReloading, setChannelAdapterReloading] = useState(false);
  const [channelAdapterUploading, setChannelAdapterUploading] = useState(false);
  const [channelAdapterDeleting, setChannelAdapterDeleting] = useState<string>();
  const [channelMessage, setChannelMessage] = useState<string>();
  const [channelTestText, setChannelTestText] = useState('测试通道入站消息');
  const [channelTestResult, setChannelTestResult] = useState<ChannelInboundResult>();
  const [channelOutboundConversationId, setChannelOutboundConversationId] = useState('');
  const [channelOutboundText, setChannelOutboundText] = useState('ClawAgent 通道出站测试');
  const [channelOutboundResult, setChannelOutboundResult] = useState<ChannelOutboundTestResponse>();
  const [channelHealth, setChannelHealth] = useState<ChannelConnectivityStatus>();
  const [channelStreamStatus, setChannelStreamStatus] = useState<ChannelStreamStatus>();
  const [channelUserBindings, setChannelUserBindings] = useState<ChannelUserBindingView[]>([]);
  const [channelBindingExternalUserId, setChannelBindingExternalUserId] = useState('');
  const [channelBindingExternalUsername, setChannelBindingExternalUsername] = useState('');
  const [channelBindingLocalUserId, setChannelBindingLocalUserId] = useState('');
  const [channelBindingLoading, setChannelBindingLoading] = useState(false);
  const [apiTokens, setApiTokens] = useState<ApiTokenView[]>([]);
  const [apiTokenName, setApiTokenName] = useState('Default API Token');
  const [apiTokenLoading, setApiTokenLoading] = useState(false);
  const [apiTokenSaving, setApiTokenSaving] = useState(false);
  const [apiTokenMessage, setApiTokenMessage] = useState<string>();
  const [createdApiToken, setCreatedApiToken] = useState<ApiTokenCreateResponse>();
  const [localUsers, setLocalUsers] = useState<LocalUserView[]>([]);
  const [currentLocalUser, setCurrentLocalUser] = useState<LocalUserView>();
  const [currentLocalSession, setCurrentLocalSession] = useState<LocalUserSessionView>();
  const [localSessionToken, setLocalSessionToken] = useState(() => window.localStorage.getItem(AUTH_SESSION_STORAGE_KEY) || '');
  const [localLoginUsername, setLocalLoginUsername] = useState('');
  const [localLoginPassword, setLocalLoginPassword] = useState('');
  const [localUserUsername, setLocalUserUsername] = useState('');
  const [localUserPassword, setLocalUserPassword] = useState('');
  const [localUserDisplayName, setLocalUserDisplayName] = useState('');
  const [localUserRole, setLocalUserRole] = useState('user');
  const [localUserPermissionMode, setLocalUserPermissionMode] = useState('ask');
  const [localUserApprovedToolIds, setLocalUserApprovedToolIds] = useState('');
  const [localUserLoading, setLocalUserLoading] = useState(false);
  const [localUserSaving, setLocalUserSaving] = useState(false);
  const [localUserMessage, setLocalUserMessage] = useState<string>();
  const [localUserSessions, setLocalUserSessions] = useState<LocalUserSessionView[]>([]);
  const [localUserSessionLoading, setLocalUserSessionLoading] = useState(false);
  const [devices, setDevices] = useState<DeviceView[]>([]);
  const [deviceName, setDeviceName] = useState('Local Device');
  const [deviceType, setDeviceType] = useState('desktop');
  const [devicePermissionMode, setDevicePermissionMode] = useState('ask');
  const [deviceApprovedToolIds, setDeviceApprovedToolIds] = useState('');
  const [devicePairingTtlSeconds, setDevicePairingTtlSeconds] = useState(600);
  const [createdDevicePairing, setCreatedDevicePairing] = useState<DevicePairingCodeResponse>();
  const [deviceLoading, setDeviceLoading] = useState(false);
  const [deviceSaving, setDeviceSaving] = useState(false);
  const [deviceMessage, setDeviceMessage] = useState<string>();
  const [automations, setAutomations] = useState<AutomationDefinition[]>([]);
  const [selectedAutomationId, setSelectedAutomationId] = useState<string>();
  const [automationRuns, setAutomationRuns] = useState<AutomationRun[]>([]);
  const [automationDraft, setAutomationDraft] = useState<AutomationUpsertRequest>(() => defaultAutomationDraft());
  const [automationSaving, setAutomationSaving] = useState(false);
  const [automationMessage, setAutomationMessage] = useState<string>();
  const [automationRunDetail, setAutomationRunDetail] = useState<AutomationRunDetail>();
  const [taskDetail, setTaskDetail] = useState<TaskDetail>();
  const [taskDetailTab, setTaskDetailTab] = useState<'summary' | 'audit' | 'result' | 'messages' | 'events' | 'tokens'>('summary');
  const [knowledgeProviders, setKnowledgeProviders] = useState<KnowledgeProviderView[]>([]);
  const [knowledgeDocuments, setKnowledgeDocuments] = useState<KnowledgeDocument[]>([]);
  const [knowledgeVectorStatus, setKnowledgeVectorStatus] = useState<VectorStatusView[]>([]);
  const [selectedKnowledgeDocumentIds, setSelectedKnowledgeDocumentIds] = useState<string[]>([]);
  const [activeKnowledgeDocumentIds, setActiveKnowledgeDocumentIds] = useState<string[]>([]);
  const [knowledgeLoading, setKnowledgeLoading] = useState(false);
  const [knowledgeMessage, setKnowledgeMessage] = useState<string>();
  const [knowledgeSearchQuery, setKnowledgeSearchQuery] = useState('');
  const [knowledgeSearchMode, setKnowledgeSearchMode] = useState<KnowledgeSearchMode>('hybrid');
  const [knowledgeSearchHits, setKnowledgeSearchHits] = useState<KnowledgeSearchHit[]>([]);
  const [memoryItems, setMemoryItems] = useState<MemoryItem[]>([]);
  const [memoryVectorStatus, setMemoryVectorStatus] = useState<VectorStatusView[]>([]);
  const [memoryCandidates, setMemoryCandidates] = useState<MemoryItem[]>([]);
  const [memoryHits, setMemoryHits] = useState<MemoryHitLog[]>([]);
  const [memorySearchHits, setMemorySearchHits] = useState<MemorySearchHit[]>([]);
  const [memorySearchQuery, setMemorySearchQuery] = useState('');
  const [memorySearchMode, setMemorySearchMode] = useState<MemorySearchMode>('hybrid');
  const [memoryDraft, setMemoryDraft] = useState<MemoryUpsertRequest>(() => defaultMemoryDraft());
  const [selectedMemoryId, setSelectedMemoryId] = useState<string>();
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [memoryMessage, setMemoryMessage] = useState<string>();
  const [runtimeConfig, setRuntimeConfig] = useState<RuntimeConfigSnapshot>();
  const [modelDraft, setModelDraft] = useState<ModelConfigUpdate>(() => draftFromConfig());
  const [configSaving, setConfigSaving] = useState(false);
  const [configMessage, setConfigMessage] = useState<string>();
  const [configTab, setConfigTab] = useState<ConfigTab>('models');
  const [localHealth, setLocalHealth] = useState<LocalHealthView>();
  const [localHealthLoading, setLocalHealthLoading] = useState(false);
  const [setupWizardDismissed, setSetupWizardDismissed] = useState(() => window.localStorage.getItem('clawagent.setupWizard.dismissed') === 'true');
  const [setupWizardOpen, setSetupWizardOpen] = useState(false);
  const [modelTestResult, setModelTestResult] = useState<ModelApiTestResponse>();
  const [modelTesting, setModelTesting] = useState(false);
  const [systemLogFilter, setSystemLogFilter] = useState<SystemLogFilter>(() => defaultSystemLogFilter());
  const [systemLogs, setSystemLogs] = useState<SystemLogLine[]>([]);
  const [systemLogSources, setSystemLogSources] = useState<SystemLogSource[]>([]);
  const [systemLogLoading, setSystemLogLoading] = useState(false);
  const [systemLogMessage, setSystemLogMessage] = useState<string>();
  const [selectedSystemLog, setSelectedSystemLog] = useState<SystemLogLine>();
  const [auditFilter, setAuditFilter] = useState<AuditEventFilter>(() => defaultAuditEventFilter());
  const [auditEvents, setAuditEvents] = useState<AgentEvent[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);
  const [auditMessage, setAuditMessage] = useState<string>();
  const [selectedAuditEvent, setSelectedAuditEvent] = useState<AgentEvent>();
  const [taskStepSearchFilter, setTaskStepSearchFilter] = useState<TaskStepSearchFilter>(() => readTaskStepSearchFilter());
  const [taskSearchResults, setTaskSearchResults] = useState<AgentTask[]>([]);
  const [stepSearchResults, setStepSearchResults] = useState<AgentStep[]>([]);
  const [taskStepSearchLoading, setTaskStepSearchLoading] = useState(false);
  const [taskStepSearchMessage, setTaskStepSearchMessage] = useState<string>();
  const [processes, setProcesses] = useState<ManagedProcessView[]>([]);
  const [processLoading, setProcessLoading] = useState(false);
  const [processMessage, setProcessMessage] = useState<string>();
  const [selectedProcessLogs, setSelectedProcessLogs] = useState<ManagedProcessLogsView>();
  const [todos, setTodos] = useState<TodoItem[]>([]);
  const [todosByTaskId, setTodosByTaskId] = useState<Record<string, TodoItem[]>>({});
  const [toolCallsByTaskId, setToolCallsByTaskId] = useState<Record<string, ToolCallView[]>>({});
  const [resumeStateByTaskId, setResumeStateByTaskId] = useState<Record<string, ResumeStateView>>({});
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [planMode, setPlanMode] = useState(() => window.localStorage.getItem('clawagent.chat.planMode') === 'true');
  const [planBusyId, setPlanBusyId] = useState<string>();
  const [autoRunPlan, setAutoRunPlan] = useState<{ planId: string; messageId: string }>();
  const [planTemplates, setPlanTemplates] = useState<PlanTemplateView[]>([]);
  const [selectedPlanTemplateId, setSelectedPlanTemplateId] = useState(() => window.localStorage.getItem('clawagent.chat.planTemplateId') || '');
  const [chatHistoryHasMore, setChatHistoryHasMore] = useState(false);
  const [chatHistoryLoading, setChatHistoryLoading] = useState(false);
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [running, setRunning] = useState(false);
  const [approvalSettings, setApprovalSettings] = useState<ApprovalSettings>(() => readApprovalSettings());
  const [approvalMode, setApprovalMode] = useState<ApprovalMode>(() => readApprovalMode());
  const [lastTaskId, setLastTaskId] = useState<string>();
  const abortRef = useRef<AbortController>();
  const runningTaskRef = useRef<string>();
  const todoPollerRef = useRef<number>();
  const resumeStateLoadingTaskIdsRef = useRef<Set<string>>(new Set());
  const authDataLoadedRef = useRef(false);
  const attachmentPreviewUrlsRef = useRef<string[]>([]);

  const selectedSession = useMemo(
    () => sessions.find((session) => session.id === selectedSessionId),
    [selectedSessionId, sessions],
  );
  const highRiskTools = useMemo(
    () => tools.filter((tool) => (tool.riskLevel || '').toLowerCase() === 'high'),
    [tools],
  );
  const selectedAutomation = useMemo(
    () => automations.find((automation) => automation.id === selectedAutomationId),
    [automations, selectedAutomationId],
  );
  const setupWizardSteps = useMemo(
    () => buildSetupWizardSteps(modelDraft, runtimeConfig, localHealth),
    [localHealth, modelDraft, runtimeConfig],
  );
  const setupWizardComplete = setupWizardSteps.every((step) => step.done);

  const loadTodos = useCallback(async (sessionId = currentSessionId) => {
    if (!sessionId) {
      setTodos([]);
      setTodosByTaskId({});
      return;
    }
    const nextTodos = await api.todos(sessionId);
    setTodos(nextTodos);
    setTodosByTaskId((current) => ({ ...current, ...groupTodosByTask(nextTodos) }));
  }, [currentSessionId]);

  const restoreSessionMessages = useCallback(async (sessionId: string) => {
    // 聊天窗口默认恢复最近一页；更早消息由顶部滚动继续加载，避免一次性拉全量历史。
    const [history, sessionPlans] = await Promise.all([
      api.sessionMessages(sessionId, CHAT_HISTORY_PAGE_SIZE),
      api.plans(sessionId, CHAT_HISTORY_PAGE_SIZE).catch(() => [] as PlanDraft[]),
    ]);
    setChatHistoryHasMore(history.length === CHAT_HISTORY_PAGE_SIZE);
    if (!history.length && !sessionPlans.length) {
      setChatMessages([createAssistantMessage('已恢复最近会话。')]);
      return;
    }
    setChatMessages(mergePlanMessages(history.map(agentMessageToChatMessage), sessionPlans));
  }, []);

  const loadOlderChatMessages = useCallback(async () => {
    if (!currentSessionId || chatHistoryLoading || !chatHistoryHasMore || chatMessages.length === 0) return;
    const firstMessage = chatMessages[0];
    setChatHistoryLoading(true);
    try {
      const older = await api.sessionMessages(
        currentSessionId,
        CHAT_HISTORY_PAGE_SIZE,
        new Date(firstMessage.createdAt).toISOString(),
      );
      setChatHistoryHasMore(older.length === CHAT_HISTORY_PAGE_SIZE);
      setChatMessages((current) => {
        const existingIds = new Set(current.map((message) => message.id));
        // 游标分页可能遇到毫秒级时间相同的消息，按 id 去重避免重复气泡。
        const mergedOlder = older.map(agentMessageToChatMessage).filter((message) => !existingIds.has(message.id));
        return [...mergedOlder, ...current];
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setChatHistoryLoading(false);
    }
  }, [chatHistoryHasMore, chatHistoryLoading, chatMessages, currentSessionId]);

  const restoreSessionToChat = useCallback(async (sessionId: string) => {
    setCurrentSessionId(sessionId);
    setActive('chat');
    setTodosByTaskId({});
    setToolCallsByTaskId({});
    setResumeStateByTaskId({});
    await Promise.all([
      restoreSessionMessages(sessionId),
      loadTodos(sessionId),
    ]);
  }, [loadTodos, restoreSessionMessages]);

  useEffect(() => {
    const taskIds = taskIdListFromMessages(chatMessages);
    const resumeTaskIds = resumeTaskIdListFromMessages(chatMessages);
    if (!taskIds.length) return;
    const missingTodoTaskIds = taskIds.filter((taskId) => !Object.prototype.hasOwnProperty.call(todosByTaskId, taskId));
    const missingToolTaskIds = taskIds.filter((taskId) => !Object.prototype.hasOwnProperty.call(toolCallsByTaskId, taskId));
    const missingResumeTaskIds = resumeTaskIds.filter((taskId) => (
      !Object.prototype.hasOwnProperty.call(resumeStateByTaskId, taskId)
      && !resumeStateLoadingTaskIdsRef.current.has(taskId)
    ));
    if (!missingTodoTaskIds.length && !missingToolTaskIds.length && !missingResumeTaskIds.length) return;
    let cancelled = false;
    // 流式消息会频繁刷新 chatMessages；接口返回前先标记 in-flight，避免同一个 task 并发打 resume-state。
    missingResumeTaskIds.forEach((taskId) => resumeStateLoadingTaskIdsRef.current.add(taskId));
    void (async () => {
      try {
        const [todoPairs, toolPairs, resumePairs] = await Promise.all([
          Promise.all(missingTodoTaskIds.map(async (taskId) => [taskId, await api.todos(undefined, taskId).catch(() => [] as TodoItem[])] as const)),
          Promise.all(missingToolTaskIds.map(async (taskId) => [taskId, toolCallsFromEvents(await api.taskEvents(taskId, CHAT_TASK_EVENT_LIMIT).catch(() => [] as AgentEvent[]))] as const)),
          Promise.all(missingResumeTaskIds.map(async (taskId) => [taskId, await api.taskResumeState(taskId).catch(() => ({ taskId, canResume: false } as ResumeStateView))] as const)),
        ]);
        if (cancelled) return;
        if (todoPairs.length) {
          setTodosByTaskId((current) => ({
            ...current,
            ...Object.fromEntries(todoPairs),
          }));
        }
        if (toolPairs.length) {
          setToolCallsByTaskId((current) => ({
            ...current,
            ...Object.fromEntries(toolPairs),
          }));
        }
        if (resumePairs.length) {
          setResumeStateByTaskId((current) => ({
            ...current,
            ...Object.fromEntries(resumePairs),
          }));
        }
      } finally {
        missingResumeTaskIds.forEach((taskId) => resumeStateLoadingTaskIdsRef.current.delete(taskId));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chatMessages, resumeStateByTaskId, todosByTaskId, toolCallsByTaskId]);

  const loadCore = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const [healthData, sessionData, toolData] = await Promise.all([
        api.health(),
        api.sessions(DEFAULT_PAGE_LIMIT),
        api.tools(),
      ]);
      setHealth(healthData);
      setSessions(sessionData);
      setTools(toolData);
      setSelectedSessionId((current) => current || sessionData[0]?.id);
      if (!currentSessionId) {
        const latest = sessionData[0]?.id;
        if (latest) {
          setCurrentSessionId(latest);
          await restoreSessionMessages(latest);
          await loadTodos(latest);
        } else {
          const created = await api.createSessionId();
          setCurrentSessionId(created.sessionId);
          setChatHistoryHasMore(false);
          setChatMessages([createAssistantMessage('已创建新的当前会话。')]);
          setTodos([]);
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [currentSessionId, loadTodos, restoreSessionMessages]);

  const refreshKnowledgeDocuments = useCallback(async () => {
    setKnowledgeLoading(true);
    setKnowledgeMessage(undefined);
    try {
      const [providers, documents, vectorStatus] = await Promise.all([
        api.knowledgeProviders().catch(() => [] as KnowledgeProviderView[]),
        api.knowledgeDocuments('console'),
        api.knowledgeVectorStatus('console').catch(() => [] as VectorStatusView[]),
      ]);
      setKnowledgeProviders(providers);
      setKnowledgeDocuments(documents);
      setKnowledgeVectorStatus(vectorStatus);
    } catch (err) {
      setKnowledgeMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setKnowledgeLoading(false);
    }
  }, []);

  const refreshChannelUserBindings = useCallback(async (channelId = channelDraft.id) => {
    const normalizedChannelId = channelId?.trim();
    if (!normalizedChannelId) {
      setChannelUserBindings([]);
      return;
    }
    setChannelBindingLoading(true);
    try {
      setChannelUserBindings(await api.channelUserBindings(normalizedChannelId));
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelBindingLoading(false);
    }
  }, [channelDraft.id]);

  const refreshChannels = useCallback(async () => {
    setChannelLoading(true);
    setChannelMessage(undefined);
    try {
      const [data, adapters, users] = await Promise.all([
        api.channels(),
        api.channelAdapters(),
        api.localUsers().catch(() => [] as LocalUserView[]),
      ]);
      const nextDraft = channelDraft.id
        ? data.find((channel) => channel.id === channelDraft.id) || channelDraft
        : data[0] || defaultChannelDraft();
      setChannels(data);
      setChannelAdapters(adapters);
      setLocalUsers(users);
      setChannelDraft(nextDraft);
      await refreshChannelUserBindings(nextDraft.id);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelLoading(false);
    }
  }, [channelDraft, refreshChannelUserBindings]);

  const reloadChannelAdapters = useCallback(async () => {
    setChannelAdapterReloading(true);
    setChannelMessage(undefined);
    try {
      const result = await api.reloadChannelAdapters();
      setChannelAdapters(result.adapters || []);
      setChannelMessage(`Adapter 已重新扫描：候选 ${result.candidateCount ?? 0} 个，生效 ${result.activeCount ?? 0} 个。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelAdapterReloading(false);
    }
  }, []);

  const uploadChannelAdapter = useCallback(async (file: File) => {
    setChannelAdapterUploading(true);
    setChannelMessage(undefined);
    try {
      const result = await api.uploadChannelAdapter(file);
      setChannelAdapters(result.adapters || []);
      setChannelMessage(`Adapter jar 已导入：${file.name}，候选 ${result.candidateCount ?? 0} 个，生效 ${result.activeCount ?? 0} 个。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelAdapterUploading(false);
    }
  }, []);

  const deleteChannelAdapter = useCallback(async (filename: string) => {
    setChannelAdapterDeleting(filename);
    setChannelMessage(undefined);
    try {
      const result = await api.deleteChannelAdapter(filename);
      setChannelAdapters(result.reload?.adapters || []);
      setChannelMessage(`Adapter jar 已删除：${filename}。${result.streamSwitchHint || ''}`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelAdapterDeleting(undefined);
    }
  }, []);

  const selectChannel = useCallback((channel: ChannelDefinition) => {
    setChannelDraft({
      ...channel,
      approvedToolIds: [...(channel.approvedToolIds || [])],
      metadata: { ...(channel.metadata || {}) },
    });
    setChannelTestResult(undefined);
    setChannelOutboundResult(undefined);
    setChannelHealth(undefined);
    setChannelStreamStatus(undefined);
    setChannelBindingExternalUserId('');
    setChannelBindingExternalUsername('');
    setChannelBindingLocalUserId('');
    void refreshChannelUserBindings(channel.id);
  }, [refreshChannelUserBindings]);

  const newChannel = useCallback(() => {
    setChannelDraft(defaultChannelDraft());
    setChannelTestResult(undefined);
    setChannelOutboundResult(undefined);
    setChannelOutboundConversationId('');
    setChannelHealth(undefined);
    setChannelStreamStatus(undefined);
    setChannelUserBindings([]);
    setChannelBindingExternalUserId('');
    setChannelBindingExternalUsername('');
    setChannelBindingLocalUserId('');
  }, []);

  const saveChannel = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('通道 ID 不能为空。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    try {
      const saved = await api.saveChannel({
        ...channelDraft,
        id: channelDraft.id.trim(),
        type: channelDraft.type?.trim() || channelDraft.id.trim(),
        name: channelDraft.name?.trim() || channelDraft.id.trim(),
        approvalMode: channelDraft.approvalMode || 'ask',
        approvedToolIds: channelDraft.approvedToolIds || [],
        metadata: channelDraft.metadata || {},
      });
      setChannelDraft(saved);
      setChannelHealth(undefined);
      setChannelStreamStatus(undefined);
      setChannelOutboundResult(undefined);
      await refreshChannels();
      await refreshChannelUserBindings(saved.id);
      setChannelMessage(`通道 ${saved.id} 已保存。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft, refreshChannels]);

  const deleteChannel = useCallback(async (channelId?: string) => {
    if (!channelId) return;
    if (!window.confirm(`确定删除通道覆盖配置 ${channelId}？内置模板会恢复默认值。`)) {
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    try {
      await api.deleteChannel(channelId);
      setChannelDraft(defaultChannelDraft());
      setChannelHealth(undefined);
      setChannelStreamStatus(undefined);
      setChannelOutboundResult(undefined);
      setChannelUserBindings([]);
      await refreshChannels();
      setChannelMessage(`通道 ${channelId} 已删除。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [refreshChannels]);

  const bindChannelUser = useCallback(async () => {
    const channelId = channelDraft.id?.trim();
    const externalUserId = channelBindingExternalUserId.trim();
    const localUserId = channelBindingLocalUserId.trim();
    if (!channelId) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    if (!externalUserId || !localUserId) {
      setChannelMessage('外部用户 ID 和本地用户不能为空。');
      return;
    }
    const localUser = localUsers.find((user) => user.id === localUserId || user.username === localUserId);
    setChannelBindingLoading(true);
    setChannelMessage(undefined);
    try {
      await api.bindChannelUser(channelId, {
        externalUserId,
        externalUsername: channelBindingExternalUsername.trim() || undefined,
        localUserId,
        localUsername: localUser?.username || localUser?.displayName || localUserId,
      });
      await refreshChannelUserBindings(channelId);
      setChannelBindingExternalUserId('');
      setChannelBindingExternalUsername('');
      setChannelMessage(`外部用户 ${externalUserId} 已绑定到本地用户 ${localUser?.username || localUserId}。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelBindingLoading(false);
    }
  }, [
    channelBindingExternalUserId,
    channelBindingExternalUsername,
    channelBindingLocalUserId,
    channelDraft.id,
    localUsers,
    refreshChannelUserBindings,
  ]);

  const unbindChannelUser = useCallback(async (externalUserId: string) => {
    const channelId = channelDraft.id?.trim();
    if (!channelId || !externalUserId) return;
    setChannelBindingLoading(true);
    setChannelMessage(undefined);
    try {
      await api.unbindChannelUser(channelId, externalUserId);
      await refreshChannelUserBindings(channelId);
      setChannelMessage(`外部用户 ${externalUserId} 已解绑。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelBindingLoading(false);
    }
  }, [channelDraft.id, refreshChannelUserBindings]);

  const submitChannelTest = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    setChannelTestResult(undefined);
    try {
      const result = await api.submitChannelMessage({
        channelId: channelDraft.id,
        externalConversationId: `admin-test-${channelDraft.id}`,
        externalUserId: 'console',
        messageType: 'text',
        text: channelTestText || 'ping',
        metadata: { source: 'admin-channel-test' },
      });
      setChannelTestResult(result);
      setChannelMessage(`入站测试已提交，taskId=${result.taskId || '-'}`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id, channelTestText]);

  const submitChannelOutboundTest = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    setChannelOutboundResult(undefined);
    try {
      const result = await api.testChannelOutbound(channelDraft.id.trim(), {
        externalConversationId: channelOutboundConversationId,
        externalUserId: 'console',
        text: channelOutboundText || 'ClawAgent 通道出站测试',
      });
      setChannelOutboundResult(result);
      setChannelMessage(result.message || `出站测试 ${result.status || '完成'}。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id, channelOutboundConversationId, channelOutboundText]);

  const checkChannelHealth = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    setChannelHealth(undefined);
    try {
      const status = await api.checkChannelHealth(channelDraft.id.trim());
      setChannelHealth(status);
      setChannelMessage(status.message || `通道 ${channelDraft.id} 检测完成。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id]);

  const refreshChannelStreamStatus = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    try {
      const status = await api.channelStreamStatus(channelDraft.id.trim());
      setChannelStreamStatus(status);
      setChannelMessage(status.message || `通道 ${channelDraft.id} Stream 状态已刷新。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id]);

  const startChannelStream = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    try {
      const status = await api.startChannelStream(channelDraft.id.trim());
      setChannelStreamStatus(status);
      setChannelMessage(status.message || `通道 ${channelDraft.id} Stream 启动请求完成。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id]);

  const stopChannelStream = useCallback(async () => {
    if (!channelDraft.id?.trim()) {
      setChannelMessage('请先选择或保存一个通道。');
      return;
    }
    setChannelSaving(true);
    setChannelMessage(undefined);
    try {
      const status = await api.stopChannelStream(channelDraft.id.trim());
      setChannelStreamStatus(status);
      setChannelMessage(status.message || `通道 ${channelDraft.id} Stream 停止请求完成。`);
    } catch (err) {
      setChannelMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setChannelSaving(false);
    }
  }, [channelDraft.id]);

  const refreshApiTokens = useCallback(async () => {
    setApiTokenLoading(true);
    setApiTokenMessage(undefined);
    try {
      setApiTokens(await api.apiTokens());
    } catch (err) {
      setApiTokenMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setApiTokenLoading(false);
    }
  }, []);

  const createApiToken = useCallback(async () => {
    setApiTokenSaving(true);
    setApiTokenMessage(undefined);
    setCreatedApiToken(undefined);
    try {
      const response = await api.createApiToken({
        name: apiTokenName,
        ownerUserId: currentLocalUser?.id,
        ownerUsername: currentLocalUser?.username,
        permissionMode: currentLocalUser?.metadata?.permissionMode || currentLocalUser?.metadata?.toolPermissionMode || 'ask',
        approvedToolIds: splitConfigLines((currentLocalUser?.metadata?.approvedToolIds || currentLocalUser?.metadata?.toolIds || '').replace(/,/g, '\n')),
        metadata: { source: 'admin' },
      });
      setCreatedApiToken(response);
      setApiTokenMessage('API Token 已创建，明文只展示一次。');
      await refreshApiTokens();
    } catch (err) {
      setApiTokenMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setApiTokenSaving(false);
    }
  }, [apiTokenName, currentLocalUser?.id, currentLocalUser?.metadata, currentLocalUser?.username, refreshApiTokens]);

  const deleteApiToken = useCallback(async (tokenId: string) => {
    if (!window.confirm('确定删除这个 API Token？删除后无法继续使用。')) {
      return;
    }
    setApiTokenSaving(true);
    setApiTokenMessage(undefined);
    try {
      await api.deleteApiToken(tokenId);
      await refreshApiTokens();
      setApiTokenMessage('API Token 已删除。');
    } catch (err) {
      setApiTokenMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setApiTokenSaving(false);
    }
  }, [refreshApiTokens]);

  const refreshLocalUsers = useCallback(async () => {
    setLocalUserLoading(true);
    setLocalUserMessage(undefined);
    try {
      setLocalUsers(await api.localUsers());
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserLoading(false);
    }
  }, []);

  const refreshLocalUserSessions = useCallback(async () => {
    setLocalUserSessionLoading(true);
    setLocalUserMessage(undefined);
    try {
      setLocalUserSessions(await api.localUserSessions());
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSessionLoading(false);
    }
  }, []);

  const refreshCurrentLocalUser = useCallback(async (sessionToken = localSessionToken) => {
    const token = sessionToken.trim();
    if (!token) {
      setCurrentLocalUser(undefined);
      setCurrentLocalSession(undefined);
      return;
    }
    try {
      const current = await api.currentLocalUser(token);
      if (current.user?.id) window.localStorage.setItem(AUTH_USER_ID_STORAGE_KEY, current.user.id);
      if (current.user?.username) window.localStorage.setItem(AUTH_USERNAME_STORAGE_KEY, current.user.username);
      setCurrentLocalUser(current.user);
      setCurrentLocalSession(current.session);
    } catch (err) {
      // 本地 session 失效时立即清理，避免后续任务继续带过期身份。
      window.localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
      window.localStorage.removeItem(AUTH_USER_ID_STORAGE_KEY);
      window.localStorage.removeItem(AUTH_USERNAME_STORAGE_KEY);
      setLocalSessionToken('');
      setCurrentLocalUser(undefined);
      setCurrentLocalSession(undefined);
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    }
  }, [localSessionToken]);

  const loginLocalUser = useCallback(async () => {
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      const response = await api.loginLocalUser({ username: localLoginUsername, password: localLoginPassword });
      const token = response.sessionToken || '';
      window.localStorage.setItem(AUTH_SESSION_STORAGE_KEY, token);
      if (response.user?.id) window.localStorage.setItem(AUTH_USER_ID_STORAGE_KEY, response.user.id);
      if (response.user?.username) window.localStorage.setItem(AUTH_USERNAME_STORAGE_KEY, response.user.username);
      setLocalSessionToken(token);
      setCurrentLocalUser(response.user);
      setCurrentLocalSession(response.session);
      setLocalLoginPassword('');
      await refreshLocalUserSessions();
      setLocalUserMessage('本地用户已登录，后续 WebUI 任务会使用该用户身份。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [localLoginPassword, localLoginUsername, refreshLocalUserSessions]);

  const logoutLocalUser = useCallback(async () => {
    const token = localSessionToken.trim();
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      if (token) {
        await api.logoutLocalUser(token);
      }
      window.localStorage.removeItem(AUTH_SESSION_STORAGE_KEY);
      window.localStorage.removeItem(AUTH_USER_ID_STORAGE_KEY);
      window.localStorage.removeItem(AUTH_USERNAME_STORAGE_KEY);
      setLocalSessionToken('');
      setCurrentLocalUser(undefined);
      setCurrentLocalSession(undefined);
      await refreshLocalUserSessions();
      setLocalUserMessage('本地用户已退出。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [localSessionToken, refreshLocalUserSessions]);

  const createLocalUser = useCallback(async () => {
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      const approvedToolIds = splitConfigLines(localUserApprovedToolIds);
      const firstUser = localUsers.length === 0;
      const request = {
        username: localUserUsername,
        password: localUserPassword,
        displayName: localUserDisplayName,
        role: firstUser ? 'owner' : localUserRole,
        metadata: {
          source: 'admin',
          permissionMode: localUserPermissionMode,
          ...(approvedToolIds.length ? { approvedToolIds: approvedToolIds.join(',') } : {}),
        },
      };
      // 首个本地用户走 setup 接口，只允许在用户表为空时创建 owner。
      await (firstUser ? api.setupOwner(request) : api.createLocalUser(request));
      // 密码只用于本次提交，成功后立即从页面状态清掉。
      setLocalUserPassword('');
      await refreshLocalUsers();
      setLocalUserMessage(firstUser ? '本地 owner 已初始化。' : '本地用户已创建。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [localUserApprovedToolIds, localUserDisplayName, localUserPassword, localUserPermissionMode, localUserRole, localUserUsername, localUsers.length, refreshLocalUsers]);

  const updateLocalUserPermissions = useCallback(async (user: LocalUserView) => {
    const currentMode = user.metadata?.permissionMode || user.metadata?.toolPermissionMode || 'ask';
    const mode = window.prompt('请输入权限模式：ask / auto / custom / read-only / full / full-access', currentMode);
    if (!mode) {
      return;
    }
    const currentTools = (user.metadata?.approvedToolIds || user.metadata?.toolIds || '').replace(/,/g, '\n');
    const toolIds = window.prompt('请输入工具白名单，多个工具用逗号或换行分隔', currentTools);
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      await api.updateLocalUserPermissions(user.id, {
        permissionMode: mode,
        approvedToolIds: splitConfigLines((toolIds || '').replace(/,/g, '\n')),
      });
      await refreshLocalUsers();
      setLocalUserMessage('本地用户权限已更新。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [refreshLocalUsers]);

  const changeLocalUserPassword = useCallback(async (userId: string) => {
    const password = window.prompt('请输入新密码，至少 6 位');
    if (!password) {
      return;
    }
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      await api.changeLocalUserPassword(userId, { password });
      await refreshLocalUsers();
      setLocalUserMessage('本地用户密码已更新。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [refreshLocalUsers]);

  const disableLocalUser = useCallback(async (userId: string) => {
    if (!window.confirm('确定禁用这个本地用户？')) {
      return;
    }
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      await api.disableLocalUser(userId);
      await refreshLocalUsers();
      setLocalUserMessage('本地用户已禁用。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [refreshLocalUsers]);

  const revokeLocalUserSession = useCallback(async (sessionId: string) => {
    if (!window.confirm('确定撤销这个本地登录会话？')) {
      return;
    }
    setLocalUserSaving(true);
    setLocalUserMessage(undefined);
    try {
      await api.revokeLocalUserSession(sessionId);
      await refreshLocalUserSessions();
      setLocalUserMessage('本地登录会话已撤销。');
    } catch (err) {
      setLocalUserMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalUserSaving(false);
    }
  }, [refreshLocalUserSessions]);

  const refreshDevices = useCallback(async () => {
    setDeviceLoading(true);
    setDeviceMessage(undefined);
    try {
      setDevices(await api.devices());
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceLoading(false);
    }
  }, []);

  const registerDevice = useCallback(async () => {
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      await api.registerDevice({
        name: deviceName,
        type: deviceType,
        permissionMode: devicePermissionMode,
        approvedToolIds: splitConfigLines(deviceApprovedToolIds),
        metadata: { source: 'admin', registration: 'manual' },
      });
      await refreshDevices();
      setDeviceMessage('设备已登记。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [deviceApprovedToolIds, deviceName, devicePermissionMode, deviceType, refreshDevices]);

  const createDevicePairingCode = useCallback(async () => {
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    setCreatedDevicePairing(undefined);
    try {
      const response = await api.createDevicePairingCode({
        name: deviceName,
        type: deviceType,
        ttlSeconds: devicePairingTtlSeconds,
        permissionMode: devicePermissionMode,
        approvedToolIds: splitConfigLines(deviceApprovedToolIds),
        metadata: { source: 'admin', registration: 'pairing' },
      });
      setCreatedDevicePairing(response);
      await refreshDevices();
      setDeviceMessage('设备配对码已创建，过期前可在客户端完成配对。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [deviceApprovedToolIds, deviceName, devicePairingTtlSeconds, devicePermissionMode, deviceType, refreshDevices]);

  const heartbeatDevice = useCallback(async (deviceId: string) => {
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      await api.heartbeatDevice(deviceId);
      await refreshDevices();
      setDeviceMessage('设备心跳已更新。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [refreshDevices]);

  const revokeDevice = useCallback(async (deviceId: string) => {
    if (!window.confirm('确定撤销这个设备？')) {
      return;
    }
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      await api.revokeDevice(deviceId);
      await refreshDevices();
      setDeviceMessage('设备已撤销。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [refreshDevices]);

  const updateDevicePermissions = useCallback(async (deviceId: string) => {
    const permissionMode = window.prompt('权限模式：ask / auto / custom / read-only / full-access', 'ask');
    if (!permissionMode) {
      return;
    }
    const toolIds = window.prompt('高危工具白名单，多个工具 ID 用逗号或换行分隔', '');
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      await api.updateDevicePermissions(deviceId, {
        permissionMode,
        approvedToolIds: splitConfigLines((toolIds || '').replace(/,/g, '\n')),
      });
      await refreshDevices();
      setDeviceMessage('设备权限绑定已更新。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [refreshDevices]);

  const rotateDeviceSecret = useCallback(async (deviceId: string) => {
    if (!window.confirm('确认轮换设备密钥？旧密钥会立即失效。')) {
      return;
    }
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      const response = await api.rotateDeviceSecret(deviceId);
      await refreshDevices();
      setDeviceMessage(`设备密钥已轮换，请立即保存：${response.deviceSecret || ''}`);
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [refreshDevices]);

  const bindDeviceUser = useCallback(async (deviceId: string) => {
    const userId = window.prompt('绑定本地用户 ID；留空表示解绑', '');
    if (userId === null) {
      return;
    }
    const username = userId ? window.prompt('展示用户名，可留空', '') : '';
    setDeviceSaving(true);
    setDeviceMessage(undefined);
    try {
      await api.bindDeviceUser(deviceId, {
        userId,
        username: username || '',
      });
      await refreshDevices();
      setDeviceMessage(userId ? '设备用户绑定已更新。' : '设备用户绑定已解除。');
    } catch (err) {
      setDeviceMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setDeviceSaving(false);
    }
  }, [refreshDevices]);

  const refreshAfterChatTask = useCallback(async (sessionId: string, refreshKnowledge: boolean) => {
    const requests: Promise<unknown>[] = [
      loadTodos(sessionId),
      api.sessions(DEFAULT_PAGE_LIMIT)
        .then((sessionData) => {
          // 发送消息后只更新会话列表标题/时间，不再刷新整个后台控制台。
          setSessions(sessionData);
          setSelectedSessionId((current) => current || sessionData[0]?.id);
        })
        .catch(() => undefined),
    ];
    if (refreshKnowledge) {
      requests.push(refreshKnowledgeDocuments().catch(() => undefined));
    }
    await Promise.all(requests);
  }, [loadTodos, refreshKnowledgeDocuments]);

  const uploadKnowledgeDocuments = useCallback(async (files: FileList | File[]) => {
    const fileList = Array.from(files);
    if (!fileList.length) return;
    setKnowledgeLoading(true);
    setKnowledgeMessage(undefined);
    try {
      const uploaded = await api.uploadKnowledgeDocuments(fileList, 'console');
      setKnowledgeMessage(`已入库 ${uploaded.length} 个文件。`);
      const [documents, vectorStatus] = await Promise.all([
        api.knowledgeDocuments('console'),
        api.knowledgeVectorStatus('console').catch(() => [] as VectorStatusView[]),
      ]);
      setKnowledgeDocuments(documents);
      setKnowledgeVectorStatus(vectorStatus);
    } catch (err) {
      setKnowledgeMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setKnowledgeLoading(false);
    }
  }, []);

  const deleteKnowledgeDocument = useCallback(async (documentId: string) => {
    setKnowledgeLoading(true);
    setKnowledgeMessage(undefined);
    try {
      await api.deleteKnowledgeDocument(documentId, 'console');
      setSelectedKnowledgeDocumentIds((current) => current.filter((id) => id !== documentId));
      setActiveKnowledgeDocumentIds((current) => current.filter((id) => id !== documentId));
      const [documents, vectorStatus] = await Promise.all([
        api.knowledgeDocuments('console'),
        api.knowledgeVectorStatus('console').catch(() => [] as VectorStatusView[]),
      ]);
      setKnowledgeDocuments(documents);
      setKnowledgeVectorStatus(vectorStatus);
      setKnowledgeSearchHits((current) => current.filter((hit) => hit.documentId !== documentId));
    } catch (err) {
      setKnowledgeMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setKnowledgeLoading(false);
    }
  }, []);

  const searchKnowledge = useCallback(async () => {
    const query = knowledgeSearchQuery.trim();
    if (!query) {
      setKnowledgeSearchHits([]);
      return;
    }
    setKnowledgeLoading(true);
    setKnowledgeMessage(undefined);
    try {
      const result = await api.searchKnowledge({
        userId: 'console',
        query,
        mode: knowledgeSearchMode,
        topK: 8,
        documentIds: selectedKnowledgeDocumentIds.length ? selectedKnowledgeDocumentIds : undefined,
      });
      setKnowledgeSearchHits(result.hits || []);
    } catch (err) {
      setKnowledgeMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setKnowledgeLoading(false);
    }
  }, [knowledgeSearchMode, knowledgeSearchQuery, selectedKnowledgeDocumentIds]);

  const toggleKnowledgeDocument = useCallback((documentId: string) => {
    setSelectedKnowledgeDocumentIds((current) => {
      const next = current.includes(documentId)
        ? current.filter((id) => id !== documentId)
        : [...current, documentId];
      setActiveKnowledgeDocumentIds(next);
      return next;
    });
  }, []);

  const setSelectedKnowledgeDocuments = useCallback((documentIds: string[]) => {
    const next = Array.from(new Set(documentIds.filter(Boolean)));
    setSelectedKnowledgeDocumentIds(next);
    setActiveKnowledgeDocumentIds(next);
  }, []);

  const refreshMemoryData = useCallback(async () => {
    setMemoryLoading(true);
    setMemoryMessage(undefined);
    try {
      const [items, vectorStatus, candidates, hits] = await Promise.all([
        api.memoryItems({ userId: 'console', limit: 200 }),
        api.memoryVectorStatus('console').catch(() => [] as VectorStatusView[]),
        api.memoryCandidates('console'),
        api.memoryHits({ userId: 'console', limit: 100 }),
      ]);
      setMemoryItems(items);
      setMemoryVectorStatus(vectorStatus);
      setMemoryCandidates(candidates);
      setMemoryHits(hits);
      setSelectedMemoryId((current) => current && items.some((item) => item.id === current) ? current : items[0]?.id);
    } catch (err) {
      setMemoryMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMemoryLoading(false);
    }
  }, []);

  const saveMemoryItem = useCallback(async () => {
    setMemoryLoading(true);
    setMemoryMessage(undefined);
    try {
      const saved = memoryDraft.id
        ? await api.updateMemoryItem(memoryDraft.id, memoryDraft)
        : await api.createMemoryItem(memoryDraft);
      setSelectedMemoryId(saved.id);
      setMemoryDraft(memoryDraftFromItem(saved));
      setMemoryMessage(memoryDraft.id ? '记忆已更新。' : '记忆已创建，默认候选状态，审核后才进入上下文。');
      const [items, vectorStatus, candidates] = await Promise.all([
        api.memoryItems({ userId: 'console', limit: 200 }),
        api.memoryVectorStatus('console').catch(() => [] as VectorStatusView[]),
        api.memoryCandidates('console'),
      ]);
      setMemoryItems(items);
      setMemoryVectorStatus(vectorStatus);
      setMemoryCandidates(candidates);
    } catch (err) {
      setMemoryMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMemoryLoading(false);
    }
  }, [memoryDraft]);

  const selectMemoryItem = useCallback((item: MemoryItem) => {
    setSelectedMemoryId(item.id);
    setMemoryDraft(memoryDraftFromItem(item));
  }, []);

  const newMemoryItem = useCallback(() => {
    setSelectedMemoryId(undefined);
    setMemoryDraft(defaultMemoryDraft());
    setMemoryMessage(undefined);
  }, []);

  const updateMemoryStatus = useCallback(async (itemId: string, action: 'enable' | 'disable' | 'archive' | 'accept' | 'reject') => {
    setMemoryLoading(true);
    setMemoryMessage(undefined);
    try {
      if (action === 'enable') await api.enableMemoryItem(itemId, 'console');
      if (action === 'disable') await api.disableMemoryItem(itemId, 'console');
      if (action === 'archive') await api.archiveMemoryItem(itemId, 'console');
      if (action === 'accept') await api.acceptMemoryCandidate(itemId, 'console');
      if (action === 'reject') await api.rejectMemoryCandidate(itemId, 'console');
      await refreshMemoryData();
    } catch (err) {
      setMemoryMessage(err instanceof Error ? err.message : String(err));
      setMemoryLoading(false);
    }
  }, [refreshMemoryData]);

  const deleteMemoryItem = useCallback(async (itemId: string) => {
    setMemoryLoading(true);
    setMemoryMessage(undefined);
    try {
      await api.deleteMemoryItem(itemId, 'console');
      setMemoryDraft(defaultMemoryDraft());
      setMemorySearchHits((current) => current.filter((hit) => hit.itemId !== itemId));
      await refreshMemoryData();
    } catch (err) {
      setMemoryMessage(err instanceof Error ? err.message : String(err));
      setMemoryLoading(false);
    }
  }, [refreshMemoryData]);

  const searchMemory = useCallback(async () => {
    const query = memorySearchQuery.trim();
    if (!query) {
      setMemorySearchHits([]);
      return;
    }
    setMemoryLoading(true);
    setMemoryMessage(undefined);
    try {
      const result = await api.searchMemory({
        userId: 'console',
        query,
        mode: memorySearchMode,
        scopeTypes: ['global', 'channel', 'session'],
        statuses: ['active'],
        topK: 10,
      });
      setMemorySearchHits(result.hits || []);
    } catch (err) {
      setMemoryMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMemoryLoading(false);
    }
  }, [memorySearchMode, memorySearchQuery]);

  const loadSessionDetail = useCallback(async (sessionId: string) => {
    setError(undefined);
    try {
      if (detailTab === 'tasks') {
        setTasks(await api.sessionTasks(sessionId, DEFAULT_PAGE_LIMIT));
      }
      if (detailTab === 'messages') {
        setMessages(await api.sessionMessages(sessionId, DEFAULT_PAGE_LIMIT));
      }
      if (detailTab === 'events') {
        setEvents(await api.sessionEvents(sessionId, DEFAULT_PAGE_LIMIT));
      }
      if (detailTab === 'todos') {
        setSessionTodos(await api.todos(sessionId, undefined, DEFAULT_PAGE_LIMIT));
      }
      if (detailTab === 'tokens') {
        const taskData = await api.sessionTasks(sessionId, DEFAULT_PAGE_LIMIT);
        const usageData = await api.sessionTokenUsage(sessionId, DEFAULT_PAGE_LIMIT);
        const usageEntries = await Promise.all(
          taskData.map((task) => api.taskTokenUsage(task.id).then((usage) => [task.id, usage] as const).catch(() => undefined)),
        );
        setTasks(taskData);
        setTokenUsage(usageData);
        setTaskTokenUsages(Object.fromEntries(usageEntries.filter(Boolean) as Array<readonly [string, TokenUsageSummary]>));
      }
      setTaskDetail(undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [detailTab]);

  const viewTaskDetail = useCallback(async (task: AgentTask) => {
    setTaskDetailTab('summary');
    setTaskDetail({ task, messages: [], events: [], loading: true });
    try {
      const [taskData, messageData, eventData, usageData, summaryData, auditData, subTaskData, orchestrationGraph] = await Promise.all([
        api.task(task.id),
        api.taskMessages(task.id, 100),
        api.taskEvents(task.id, 200),
        api.taskTokenUsage(task.id),
        api.developmentSummary(task.id),
        api.taskAudit(task.id),
        api.subAgentTasks(task.id).catch(() => [] as AgentTask[]),
        api.agentOrchestrationGraph(task.id).catch(() => undefined),
      ]);
      setTaskDetail({
        task: taskData,
        messages: messageData,
        events: eventData,
        tokenUsage: usageData,
        developmentSummary: summaryData,
        taskAudit: auditData,
        subTasks: subTaskData,
        orchestrationGraph,
        loading: false,
      });
    } catch (err) {
      setTaskDetail({
        task,
        messages: [],
        events: [],
        loading: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  const createSubAgentTask = useCallback(async (parentTask: AgentTask, request: SubAgentTaskRequest) => {
    if (!parentTask.id) return;
    setTaskDetail((current) => current ? { ...current, error: undefined } : current);
    try {
      await api.createSubAgentTask(parentTask.id, request);
      await viewTaskDetail(parentTask);
    } catch (err) {
      setTaskDetail((current) => current ? {
        ...current,
        loading: false,
        error: err instanceof Error ? err.message : String(err),
      } : current);
    }
  }, [viewTaskDetail]);

  const loadSystemLogs = useCallback(async (filter: SystemLogFilter = systemLogFilter) => {
    setSystemLogLoading(true);
    setSystemLogMessage(undefined);
    try {
      const [logData, sourceData] = await Promise.all([
        api.queryLogs(filter),
        api.logSources().catch(() => [] as SystemLogSource[]),
      ]);
      setSystemLogs(logData);
      setSystemLogSources(sourceData);
      setSelectedSystemLog(undefined);
      setSystemLogMessage(`已加载 ${logData.length} 条日志。`);
    } catch (err) {
      setSystemLogs([]);
      setSelectedSystemLog(undefined);
      setSystemLogMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSystemLogLoading(false);
    }
  }, [systemLogFilter]);

  const loadAuditEvents = useCallback(async (filter: AuditEventFilter = auditFilter) => {
    setAuditLoading(true);
    setAuditMessage(undefined);
    try {
      const data = await api.auditEvents(filter);
      setAuditEvents(data);
      setSelectedAuditEvent(undefined);
      setAuditMessage(`已加载 ${data.length} 条审计事件。`);
    } catch (err) {
      setAuditEvents([]);
      setSelectedAuditEvent(undefined);
      setAuditMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setAuditLoading(false);
    }
  }, [auditFilter]);

  const runTaskStepSearch = useCallback(async (filter: TaskStepSearchFilter = taskStepSearchFilter) => {
    setTaskStepSearchLoading(true);
    setTaskStepSearchMessage(undefined);
    try {
      if (filter.mode === 'steps') {
        const data = await api.searchSteps({
          query: filter.query,
          status: filter.status,
          taskId: filter.taskId,
          toolId: filter.toolId,
          riskLevel: filter.riskLevel,
          limit: filter.limit,
        });
        setStepSearchResults(data);
        setTaskSearchResults([]);
        setTaskStepSearchMessage(`已检索到 ${data.length} 条步骤。`);
      } else {
        const data = await api.searchTasks({
          query: filter.query,
          status: filter.status,
          channelId: filter.channelId,
          userId: filter.userId,
          sessionId: filter.sessionId,
          limit: filter.limit,
        });
        setTaskSearchResults(data);
        setStepSearchResults([]);
        setTaskStepSearchMessage(`已检索到 ${data.length} 个任务。`);
      }
    } catch (err) {
      setTaskSearchResults([]);
      setStepSearchResults([]);
      setTaskStepSearchMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setTaskStepSearchLoading(false);
    }
  }, [taskStepSearchFilter]);

  const openTaskSearchResult = useCallback(async (taskId?: string) => {
    if (!taskId) return;
    try {
      const task = await api.task(taskId);
      setSelectedSessionId(task.sessionId);
      setDetailTab('tasks');
      await viewTaskDetail(task);
    } catch (err) {
      setTaskStepSearchMessage(err instanceof Error ? err.message : String(err));
    }
  }, [viewTaskDetail]);

  const resetTaskStepSearch = useCallback(() => {
    window.localStorage.removeItem(TASK_SEARCH_FILTER_STORAGE_KEY);
    setTaskStepSearchFilter(defaultTaskStepSearchFilter());
    setTaskSearchResults([]);
    setStepSearchResults([]);
    setTaskStepSearchMessage(undefined);
  }, []);

  useEffect(() => {
    void loadCore();
    return () => {
      if (todoPollerRef.current) window.clearInterval(todoPollerRef.current);
    };
  }, [loadCore]);

  useEffect(() => {
    if (active === 'sessions' && selectedSessionId) {
      void loadSessionDetail(selectedSessionId);
    }
  }, [active, loadSessionDetail, selectedSessionId]);

  useEffect(() => {
    if (active === 'logs' && !systemLogs.length && !systemLogLoading) {
      void loadSystemLogs();
    }
  }, [active, loadSystemLogs, systemLogLoading, systemLogs.length]);

  useEffect(() => {
    if (active === 'audit' && !auditEvents.length && !auditLoading) {
      void loadAuditEvents();
    }
  }, [active, auditEvents.length, auditLoading, loadAuditEvents]);

  useEffect(() => {
    if (active === 'knowledge' && !knowledgeDocuments.length && !knowledgeLoading) {
      void refreshKnowledgeDocuments();
    }
  }, [active, knowledgeDocuments.length, knowledgeLoading, refreshKnowledgeDocuments]);

  useEffect(() => {
    if (active === 'memory' && !memoryItems.length && !memoryLoading) {
      void refreshMemoryData();
    }
  }, [active, memoryItems.length, memoryLoading, refreshMemoryData]);

  useEffect(() => {
    if (active === 'channels' && !channels.length && !channelLoading) {
      void refreshChannels();
    }
  }, [active, channelLoading, channels.length, refreshChannels]);

  useEffect(() => {
    if (active !== 'auth' || authDataLoadedRef.current) {
      return;
    }
    authDataLoadedRef.current = true;
    // 授权数据允许为空；只在首次进入授权页自动加载一次，避免空列表触发重复请求。
    void Promise.all([
      refreshApiTokens(),
      refreshLocalUsers(),
      refreshLocalUserSessions(),
    ]);
  }, [active, refreshApiTokens, refreshLocalUserSessions, refreshLocalUsers]);

  useEffect(() => {
    if (localSessionToken && !currentLocalUser && !currentLocalSession) {
      void refreshCurrentLocalUser(localSessionToken);
    }
  }, [currentLocalSession, currentLocalUser, localSessionToken, refreshCurrentLocalUser]);

  useEffect(() => {
    if (active === 'devices' && !devices.length) {
      void refreshDevices();
    }
  }, [active, devices.length, refreshDevices]);

  useEffect(() => {
    window.localStorage.setItem('clawagent.approval.allowHighRiskTools', String(approvalSettings.allowHighRiskTools));
    window.localStorage.setItem('clawagent.approval.approvedToolIds', JSON.stringify(approvalSettings.approvedToolIds));
    window.localStorage.setItem('clawagent.approval.mode', approvalMode);
  }, [approvalMode, approvalSettings]);

  useEffect(() => {
    // 跨任务检索是排障入口，自动保存筛选条件能让用户刷新页面后继续追同一类工具或风险记录。
    window.localStorage.setItem(TASK_SEARCH_FILTER_STORAGE_KEY, JSON.stringify(taskStepSearchFilter));
  }, [taskStepSearchFilter]);

  useEffect(() => () => {
    attachmentPreviewUrlsRef.current.forEach((previewUrl) => URL.revokeObjectURL(previewUrl));
    attachmentPreviewUrlsRef.current = [];
  }, []);

  const updateMessage = useCallback((messageId: string, patch: Partial<ChatMessage> | ((current: ChatMessage) => ChatMessage)) => {
    setChatMessages((current) => current.map((message) => {
      if (message.id !== messageId) return message;
      return typeof patch === 'function' ? patch(message) : { ...message, ...patch };
    }));
  }, []);

  const appendSlashCommandResult = useCallback((commandLine: string, content: string, startedAt: number) => {
    const assistant = {
      ...createAssistantMessage(content),
      status: '指令完成',
      finishedAt: Date.now(),
      durationMs: Date.now() - startedAt,
      toolsCollapsed: true,
    };
    setChatMessages((current) => [...current, createUserMessage(commandLine), assistant]);
  }, []);

  const appendSlashCommandError = useCallback((commandLine: string, err: unknown, startedAt: number) => {
    const message = err instanceof Error ? err.message : String(err);
    appendSlashCommandResult(commandLine, `### 指令执行失败\n\n${message}`, startedAt);
  }, [appendSlashCommandResult]);

  const startTodoPolling = useCallback((sessionId: string) => {
    if (todoPollerRef.current) window.clearInterval(todoPollerRef.current);
    todoPollerRef.current = undefined;
    // SSE 已经推送步骤和工具事件，这里只在任务开始时刷新一次，避免执行期重复刷 /todos。
    void loadTodos(sessionId);
  }, [loadTodos]);

  const stopTodoPolling = useCallback(async (sessionId?: string) => {
    if (todoPollerRef.current) {
      window.clearInterval(todoPollerRef.current);
      todoPollerRef.current = undefined;
    }
    await loadTodos(sessionId);
  }, [loadTodos]);

  const changeApprovalMode = useCallback((mode: ApprovalMode) => {
    setApprovalMode(mode);
    setModelDraft((current) => ({ ...current, localPermissionMode: mode }));
    setApprovalSettings((current) => ({
      ...current,
      // custom 只使用显式工具白名单，auto/full 才自动放行高危工具。
      allowHighRiskTools: allowHighRiskForMode(mode),
    }));
  }, []);

  const changePlanMode = useCallback((enabled: boolean) => {
    setPlanMode(enabled);
    window.localStorage.setItem('clawagent.chat.planMode', String(enabled));
  }, []);

  const changePlanTemplate = useCallback((templateId: string) => {
    setSelectedPlanTemplateId(templateId);
    window.localStorage.setItem('clawagent.chat.planTemplateId', templateId);
  }, []);

  useEffect(() => {
    api.planTemplates()
      .then(setPlanTemplates)
      .catch(() => setPlanTemplates([]));
  }, []);

  const toggleApprovedTool = useCallback((toolId: string) => {
    const exists = approvalSettings.approvedToolIds.includes(toolId);
    const approvedToolIds = exists
      ? approvalSettings.approvedToolIds.filter((item) => item !== toolId)
      : [...approvalSettings.approvedToolIds, toolId];
    setApprovalSettings((current) => ({ ...current, approvedToolIds }));
    setModelDraft((draft) => ({ ...draft, localApprovedToolIds: approvedToolIds }));
  }, [approvalSettings.approvedToolIds]);

  const addAttachments = useCallback((files: FileList | File[]) => {
    const nextFiles = Array.from(files);
    if (!nextFiles.length) return;
    setAttachments((current) => [
      ...current,
      ...nextFiles.map((file) => {
        const previewUrl = file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined;
        if (previewUrl) attachmentPreviewUrlsRef.current.push(previewUrl);
        return {
          id: `${Date.now()}-${file.name}-${Math.random().toString(16).slice(2)}`,
          name: file.name,
          size: file.size,
          type: file.type,
          file,
          previewUrl,
        };
      }),
    ]);
  }, []);

  const removeAttachment = useCallback((attachmentId: string) => {
    setAttachments((current) => {
      const removed = current.find((attachment) => attachment.id === attachmentId);
      if (removed?.previewUrl) {
        URL.revokeObjectURL(removed.previewUrl);
        attachmentPreviewUrlsRef.current = attachmentPreviewUrlsRef.current.filter((item) => item !== removed.previewUrl);
      }
      return current.filter((attachment) => attachment.id !== attachmentId);
    });
  }, []);

  const clearAttachments = useCallback(() => {
    setAttachments((current) => {
      current.forEach((attachment) => {
        if (attachment.previewUrl) URL.revokeObjectURL(attachment.previewUrl);
      });
      attachmentPreviewUrlsRef.current = [];
      return [];
    });
  }, []);

  const handleStreamEvent = useCallback((event: SseEvent, assistantId: string, startedAt: number) => {
    const data = event.data || {};
    if (data.taskId) {
      setLastTaskId(data.taskId);
      runningTaskRef.current = data.taskId;
      updateMessage(assistantId, { taskId: data.taskId });
    }
    if (event.name === 'task.started') {
      updateMessage(assistantId, { progress: '规划中...' });
    } else if (event.name === 'task.resume_requested') {
      updateMessage(assistantId, {
        progress: todoExecutionLabel(data, '正在继续未完成任务...'),
      });
    } else if (event.name === 'task.resume_checkpoint') {
      const checkpoint = formatCheckpointPreview(typeof data.checkpoint === 'string' ? data.checkpoint : '', 360);
      updateMessage(assistantId, {
        progress: todoExecutionLabel(data, '正在恢复上次任务...'),
        content: [
          `从 Todo ${data.todoOrder || '-'}：${data.todoTitle || '未完成任务'} 恢复执行。`,
          checkpoint ? `\n恢复点：\n${checkpoint}` : '',
        ].filter(Boolean).join('\n'),
      });
    } else if (event.name === 'step.started') {
      updateMessage(assistantId, { progress: `正在执行：${data.message || data.toolId || '任务步骤'}` });
    } else if (event.name === 'step.finished') {
      updateMessage(assistantId, { progress: `步骤完成：${data.message || data.status || ''}` });
      void loadTodos();
    } else if (event.name === 'tool.started') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'running',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        riskLevel: data.riskLevel,
        outputPreview: data.outputPreview,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `正在调用 ${data.toolId || data.message || '未知工具'}`,
      }, false, `${todoExecutionLabel(data, '正在执行任务')} · ${data.toolId || data.message || '未知工具'}`));
    } else if (event.name === 'tool.succeeded') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'completed',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        riskLevel: data.riskLevel,
        outputPreview: data.outputPreview,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `调用成功 ${data.toolId || data.message || '未知工具'}`,
      }, false, `工具调用成功：${data.toolId || data.message || '未知工具'}`));
      void loadTodos();
    } else if (event.name === 'tool.failed') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'failed',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        riskLevel: data.riskLevel,
        outputPreview: data.outputPreview,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        error: data.error || data.message,
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `调用失败 ${data.toolId || data.message || '未知工具'}`,
      }, false, `工具调用失败：${data.toolId || data.message || '未知工具'}`));
      void loadTodos();
    } else if (event.name === 'tool.approval_requested') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'running',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        riskLevel: data.riskLevel,
        approvalRequested: true,
        approvalKey: data.approvalKey,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        error: data.error || data.message,
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `等待审批 ${data.toolId || data.message || '未知工具'}`,
      }, false, `等待审批：${data.toolId || data.message || '未知工具'}`));
    } else if (event.name === 'tool.approval_granted') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'running',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        approvalRequested: false,
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `已审批，继续调用 ${data.toolId || data.message || '未知工具'}`,
      }, false, `已审批，继续执行：${data.toolId || data.message || '未知工具'}`));
    } else if (event.name === 'tool.approval_rejected') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'failed',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        approvalRequested: false,
        error: data.reason || data.message,
        todoId: data.todoId,
        todoOrder: data.todoOrder == null ? undefined : String(data.todoOrder),
        todoTitle: data.todoTitle,
        message: `已拒绝调用 ${data.toolId || data.message || '未知工具'}`,
      }, false, `已拒绝执行：${data.toolId || data.message || '未知工具'}`));
    } else if (event.name === 'llm.delta') {
      const chunk = normalizeStreamChunk(data.content);
      if (!chunk) return;
      updateMessage(assistantId, (message) => ({ ...message, content: message.content + chunk, progress: '正在生成最终回复...' }));
    } else if (event.name === 'llm.completed') {
      // 模型正文已经流式输出完成，后端后续还会写库、提炼记忆并发送 result；这些收尾不需要继续占用聊天气泡进度条。
      updateMessage(assistantId, { progress: '' });
    } else if (event.name === 'result') {
      updateMessage(assistantId, (message) => ({
        ...message,
        content: message.content || data.answer || '',
        status: statusText(data.status) || '已完成',
        progress: '',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
        toolsCollapsed: approvalBlockedTool(message) ? false : true,
      }));
    } else if (event.name === 'task.cancelled') {
      updateMessage(assistantId, {
        content: data.message || '任务已停止。',
        status: '已停止',
        progress: '',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
        toolsCollapsed: true,
      });
    } else if (event.name === 'error') {
      updateMessage(assistantId, {
        content: data.message || '任务执行失败',
        status: '失败',
        progress: data.message || '任务执行失败',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
        toolsCollapsed: true,
      });
    }
  }, [loadTodos, updateMessage]);

  const cancelRunningTask = useCallback(async () => {
    const taskId = runningTaskRef.current;
    if (taskId) {
      await api.cancelTask(taskId).catch((err) => setError(err instanceof Error ? err.message : String(err)));
    }
    abortRef.current?.abort();
  }, []);

  const changeActiveProjectPath = useCallback((value: string) => {
    setActiveProjectPath(value);
    const normalized = value.trim();
    if (normalized) {
      window.localStorage.setItem('clawagent.chat.activeProjectPath', normalized);
    } else {
      window.localStorage.removeItem('clawagent.chat.activeProjectPath');
    }
  }, []);

  const rememberActiveProjectPath = useCallback((path: string) => {
    const normalized = path.trim();
    if (!normalized) return;
    void api.rememberRecentProject(normalized).then((saved) => {
      setRuntimeConfig(saved);
      setModelDraft((current) => ({
        ...current,
        localRecentProjects: saved.local?.recentProjects || current.localRecentProjects,
      }));
    }).catch((err) => {
      console.warn('保存最近项目目录失败', err);
    });
  }, []);

  const effectiveLocalUserId = useCallback(() => (
    currentLocalUser?.id
      || currentLocalUser?.username
      || window.localStorage.getItem(AUTH_USER_ID_STORAGE_KEY)
      || 'console'
  ), [currentLocalUser?.id, currentLocalUser?.username]);

  const planExecutionMetadata = useCallback(() => {
    const requestProjectPath = activeProjectPath.trim();
    const configuredApprovedToolIds = approvalMode === 'custom' ? approvalSettings.approvedToolIds : [];
    const approvedToolIds = approvalSettings.allowHighRiskTools
      ? highRiskTools.map((tool) => tool.id)
      : configuredApprovedToolIds;
    return {
      approvalMode,
      toolPermissionMode: approvalMode,
      allowHighRiskTools: String(approvalSettings.allowHighRiskTools),
      ...(currentLocalUser?.id ? { localUserId: currentLocalUser.id } : {}),
      ...(currentLocalUser?.username ? { 'auth.username': currentLocalUser.username } : {}),
      ...(requestProjectPath ? {
        activeProjectPath: requestProjectPath,
        projectPath: requestProjectPath,
        'workspace.projectPath': requestProjectPath,
      } : {}),
      ...(selectedKnowledgeDocumentIds.length ? {
        'knowledge.enabled': 'true',
        'knowledge.documentIds': JSON.stringify(selectedKnowledgeDocumentIds),
        'knowledge.scope': 'selected_documents',
      } : {}),
      ...(approvedToolIds.length ? { approvedToolIds: approvedToolIds.join(',') } : {}),
    };
  }, [activeProjectPath, approvalMode, approvalSettings.allowHighRiskTools, approvalSettings.approvedToolIds, currentLocalUser?.id, currentLocalUser?.username, highRiskTools, selectedKnowledgeDocumentIds]);

  const updatePlanMessages = useCallback((plan: PlanDraft) => {
    setChatMessages((current) => current.map((message) => (
      message.planId === plan.id
        ? { ...message, plan, status: planStatusText(plan.status), finishedAt: Date.now() }
        : message
    )));
  }, []);

  const createPlanFromText = useCallback(async (rawText?: string, visibleInput?: string) => {
    if (running) {
      await cancelRunningTask();
      return;
    }
    const text = normalizeEscapedNewlines(rawText ?? input).trim();
    if (!text || !currentSessionId) return;
    const requestProjectPath = activeProjectPath.trim();
    if (requestProjectPath) {
      rememberActiveProjectPath(requestProjectPath);
    }
    if (rawText === undefined) {
      setInput('');
    }
    setRunning(true);
    setPlanBusyId('create');
    const startedAt = Date.now();
    const userMessage = createUserMessage(visibleInput || text);
    const assistant = createAssistantMessage('', '正在生成计划...');
    setChatMessages((current) => [...current, userMessage, assistant]);
    try {
      const plan = await api.createPlan({
        input: text,
        sessionId: currentSessionId,
        mode: 'grounded',
        templateId: selectedPlanTemplateId || undefined,
        metadata: planExecutionMetadata(),
      });
      updateMessage(assistant.id, {
        planId: plan.id,
        plan,
        status: planStatusText(plan.status),
        progress: '',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
      });
      setAutoRunPlan({ planId: plan.id, messageId: assistant.id });
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      updateMessage(assistant.id, {
        content: `计划生成失败：${message}`,
        status: '失败',
        progress: '',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
      });
    } finally {
      setRunning(false);
      setPlanBusyId(undefined);
    }
  }, [activeProjectPath, cancelRunningTask, currentSessionId, input, planExecutionMetadata, rememberActiveProjectPath, running, selectedPlanTemplateId, updateMessage]);

  const revisePlan = useCallback(async (planId: string, feedback: string) => {
    const normalized = feedback.trim();
    if (!normalized) return;
    setPlanBusyId(planId);
    try {
      updatePlanMessages(await api.revisePlan(planId, { feedback: normalized }));
    } finally {
      setPlanBusyId(undefined);
    }
  }, [updatePlanMessages]);

  const cancelPlan = useCallback(async (planId: string) => {
    setPlanBusyId(planId);
    try {
      updatePlanMessages(await api.cancelPlan(planId));
    } finally {
      setPlanBusyId(undefined);
    }
  }, [updatePlanMessages]);

  const runPlan = useCallback(async (
    planId: string,
    options: { force?: boolean; messageId?: string } = {},
  ) => {
    if (running && !options.force) {
      await cancelRunningTask();
      return;
    }
    if (!currentSessionId) return;
    setRunning(true);
    setPlanBusyId(planId);
    const startedAt = Date.now();
    const controller = new AbortController();
    abortRef.current = controller;
    const existingPlanMessage = options.messageId
      ? chatMessages.find((message) => message.id === options.messageId)
      : chatMessages.find((message) => message.planId === planId);
    const assistantId = options.messageId || existingPlanMessage?.id || createAssistantMessage('', '正在执行计划...').id;
    if (options.messageId || existingPlanMessage) {
      updateMessage(assistantId, {
        content: '',
        progress: '正在执行计划...',
        status: '执行中',
        finishedAt: undefined,
        durationMs: undefined,
        toolsCollapsed: false,
      });
    } else {
      const assistant = createAssistantMessage('', '正在执行计划...');
      assistant.id = assistantId;
      assistant.planId = planId;
      setChatMessages((current) => [...current, assistant]);
    }
    startTodoPolling(currentSessionId);
    try {
      let plan = await api.plan(planId);
      if ((plan.status || '').toUpperCase() === 'DRAFT') {
        // 兼容旧数据中的 DRAFT；新计划创建后已经是可执行状态。
        plan = await api.approvePlan(planId);
        updatePlanMessages(plan);
      }
      updateMessage(assistantId, {
        plan,
        planId,
        status: planStatusText(plan.status),
        progress: '正在启动计划执行...',
      });
      const response = await api.runPlanStream(planId, {
        channelId: 'webui',
        userId: effectiveLocalUserId(),
        metadata: planExecutionMetadata(),
      }, controller.signal, localSessionToken);
      if (!response.ok || !response.body) {
        throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
      }
      await readSseStream(response.body, (event) => handleStreamEvent(event, assistantId, startedAt));
      const latestPlan = await api.plan(planId).catch(() => undefined);
      if (latestPlan) {
        updatePlanMessages(latestPlan);
        updateMessage(assistantId, { plan: latestPlan, status: planStatusText(latestPlan.status) });
      }
      const finishedTaskId = runningTaskRef.current || existingPlanMessage?.taskId;
      if (finishedTaskId) {
        const usage = await api.taskTokenUsage(finishedTaskId).catch(() => undefined);
        if (usage) updateMessage(assistantId, { tokenUsage: usage });
      }
      await refreshAfterChatTask(currentSessionId, false);
    } catch (err) {
      const message = controller.signal.aborted ? '计划执行已请求停止。' : `计划执行失败：${err instanceof Error ? err.message : String(err)}`;
      updateMessage(assistantId, {
        content: message,
        status: controller.signal.aborted ? '已停止' : '失败',
        progress: '',
        finishedAt: Date.now(),
        durationMs: Date.now() - startedAt,
        toolsCollapsed: true,
      });
    } finally {
      setRunning(false);
      setPlanBusyId(undefined);
      abortRef.current = undefined;
      runningTaskRef.current = undefined;
      await stopTodoPolling(currentSessionId);
    }
  }, [cancelRunningTask, chatMessages, currentSessionId, effectiveLocalUserId, handleStreamEvent, localSessionToken, planExecutionMetadata, refreshAfterChatTask, running, startTodoPolling, stopTodoPolling, updateMessage, updatePlanMessages]);

  useEffect(() => {
    if (!autoRunPlan || running) {
      return;
    }
    const next = autoRunPlan;
    setAutoRunPlan(undefined);
    void runPlan(next.planId, { force: true, messageId: next.messageId });
  }, [autoRunPlan, running, runPlan]);

  const submitRequest = useCallback(async (options: SubmitOptions = {}) => {
    if (running) {
      await cancelRunningTask();
      return;
    }
    const retrying = !!options.inputOverride;
    const text = normalizeEscapedNewlines(options.inputOverride ?? input).trim();
    if ((!text && !attachments.length && !selectedKnowledgeDocumentIds.length) || !currentSessionId) return;
    const submittedAttachments = retrying ? [] : attachments;
    const baseText = text || (selectedKnowledgeDocumentIds.length ? '请结合选中的知识库文件回答。' : '请处理以下附件。');
    const requestProjectPath = (options.projectPathOverride || activeProjectPath).trim();
    if (requestProjectPath) {
      rememberActiveProjectPath(requestProjectPath);
    }
    if (!retrying) {
      setInput('');
      clearAttachments();
    }
    setRunning(true);
    const startedAt = Date.now();
    const assistant = {
      ...createAssistantMessage('', options.assistantProgress || (submittedAttachments.length ? '解析附件中...' : '规划中...')),
      requestInput: baseText,
    };
    const userMessage = createUserMessage(baseText, toChatAttachments(submittedAttachments));
    setChatMessages((current) => (options.showUserMessage === false ? [...current, assistant] : [...current, userMessage, assistant]));
    startTodoPolling(currentSessionId);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      const requestUserId = effectiveLocalUserId();
      let parsedAttachments: AttachmentParseResult[] = [];
      if (submittedAttachments.length) {
        // 附件上传后只回传轻量 metadata 和 knowledgeDocumentId，正文由后端知识库检索按需注入模型上下文。
        const parsed = await api.parseAttachments(submittedAttachments.map((attachment) => attachment.file), requestUserId, controller.signal);
        parsedAttachments = parsed.attachments || [];
        updateMessage(userMessage.id, { attachments: toChatAttachments(submittedAttachments, parsedAttachments) });
        const [documents, vectorStatus] = await Promise.all([
          api.knowledgeDocuments(requestUserId).catch(() => knowledgeDocuments),
          api.knowledgeVectorStatus(requestUserId).catch(() => knowledgeVectorStatus),
        ]);
        setKnowledgeDocuments(documents);
        setKnowledgeVectorStatus(vectorStatus);
        updateMessage(assistant.id, { progress: '附件已入库，准备规划任务...' });
      }
      const attachmentKnowledgeDocumentIds = parsedAttachments
        .map((attachment) => attachment.knowledgeDocumentId)
        .filter((id): id is string => Boolean(id));
      // 本次上传附件优先，避免历史选中的知识库文档污染附件总结/附件问答。
      const requestKnowledgeDocumentIds = attachmentKnowledgeDocumentIds.length
        ? attachmentKnowledgeDocumentIds
        : selectedKnowledgeDocumentIds.length
          ? selectedKnowledgeDocumentIds
          : activeKnowledgeDocumentIds;
      const knowledgeScope = attachmentKnowledgeDocumentIds.length
        ? 'attachments'
        : selectedKnowledgeDocumentIds.length
          ? 'selected_documents'
          : activeKnowledgeDocumentIds.length
            ? 'conversation_documents'
          : undefined;
      if (attachmentKnowledgeDocumentIds.length) {
        setActiveKnowledgeDocumentIds(attachmentKnowledgeDocumentIds);
      } else if (selectedKnowledgeDocumentIds.length) {
        setActiveKnowledgeDocumentIds(selectedKnowledgeDocumentIds);
      }
      const configuredApprovedToolIds = approvalMode === 'custom' ? approvalSettings.approvedToolIds : [];
      const approvedToolIds = approvalSettings.allowHighRiskTools
        ? highRiskTools.map((tool) => tool.id)
        : Array.from(new Set([...configuredApprovedToolIds, ...(options.extraApprovedToolIds || [])]));
      const requestMetadata = {
        approvalMode,
        toolPermissionMode: approvalMode,
        allowHighRiskTools: String(approvalSettings.allowHighRiskTools),
        ...(currentLocalUser?.id ? { localUserId: currentLocalUser.id } : {}),
        ...(currentLocalUser?.username ? { 'auth.username': currentLocalUser.username } : {}),
        ...(requestProjectPath ? {
          activeProjectPath: requestProjectPath,
          projectPath: requestProjectPath,
          'workspace.projectPath': requestProjectPath,
        } : {}),
        ...(parsedAttachments.length ? { attachments: JSON.stringify(attachmentMetadata(parsedAttachments)) } : {}),
        ...(parsedAttachments.length ? { attachmentIds: parsedAttachments.map((attachment) => attachment.id).filter(Boolean).join(',') } : {}),
        ...(parsedAttachments.length ? { attachmentStoragePaths: parsedAttachments.map((attachment) => attachment.storagePath).filter(Boolean).join(',') } : {}),
        ...(attachmentKnowledgeDocumentIds.length ? { attachmentKnowledgeDocumentIds: attachmentKnowledgeDocumentIds.join(',') } : {}),
        ...(requestKnowledgeDocumentIds.length ? { 'knowledge.enabled': 'true', 'knowledge.documentIds': JSON.stringify(requestKnowledgeDocumentIds) } : {}),
        ...(knowledgeScope ? { 'knowledge.scope': knowledgeScope } : {}),
        ...(approvedToolIds.length ? { approvedToolIds: approvedToolIds.join(',') } : {}),
      };
      const response = options.resumeTaskId
        ? await api.resumeStream(options.resumeTaskId, {
          input: baseText,
          channelId: 'webui',
          userId: requestUserId,
          metadata: requestMetadata,
        }, controller.signal, localSessionToken)
        : await api.submitStream({
          input: baseText,
          sessionId: currentSessionId,
          channelId: 'webui',
          userId: requestUserId,
          metadata: requestMetadata,
        }, controller.signal, localSessionToken);
      if (!response.ok || !response.body) {
        throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
      }
      await readSseStream(response.body, (event) => handleStreamEvent(event, assistant.id, startedAt));
      const finishedTaskId = runningTaskRef.current || assistant.taskId;
      if (finishedTaskId) {
        const usage = await api.taskTokenUsage(finishedTaskId).catch(() => undefined);
        if (usage) {
          updateMessage(assistant.id, { tokenUsage: usage });
        }
      }
      await refreshAfterChatTask(currentSessionId, parsedAttachments.length > 0);
    } catch (err) {
      if (controller.signal.aborted) {
        updateMessage(assistant.id, {
          content: '任务已请求停止。',
          status: '已停止',
          progress: '',
          finishedAt: Date.now(),
          durationMs: Date.now() - startedAt,
          toolsCollapsed: true,
        });
      } else {
        const message = err instanceof Error ? err.message : String(err);
        updateMessage(assistant.id, {
          content: `执行失败：${message}`,
          status: '失败',
          progress: `执行失败：${message}`,
          finishedAt: Date.now(),
          durationMs: Date.now() - startedAt,
          toolsCollapsed: true,
        });
      }
    } finally {
      setRunning(false);
      abortRef.current = undefined;
      runningTaskRef.current = undefined;
      await stopTodoPolling(currentSessionId);
    }
  }, [activeKnowledgeDocumentIds, activeProjectPath, approvalMode, approvalSettings.allowHighRiskTools, approvalSettings.approvedToolIds, attachments, cancelRunningTask, clearAttachments, currentLocalUser?.id, currentLocalUser?.username, currentSessionId, effectiveLocalUserId, handleStreamEvent, highRiskTools, input, knowledgeDocuments, knowledgeVectorStatus, localSessionToken, refreshAfterChatTask, rememberActiveProjectPath, running, selectedKnowledgeDocumentIds, startTodoPolling, stopTodoPolling, updateMessage]);

  const resumeTask = useCallback(async (message: ChatMessage, projectPathOverride?: string) => {
    if (!message.taskId) return;
    const normalizedProjectPath = (projectPathOverride || '').trim();
    if (normalizedProjectPath) {
      changeActiveProjectPath(normalizedProjectPath);
    }
    const resumeState = resumeStateByTaskId[message.taskId];
    const taskTodos = todosByTaskId[message.taskId] || todos.filter((todo) => todo.taskId === message.taskId);
    const resumeTodo = resumeTodoFromState(resumeState) || nextResumeTodo(taskTodos);
    const remainingTodos = resumeState?.remainingTodos?.length ? resumeState.remainingTodos : taskTodos.filter((todo) => ['running', 'pending', 'failed'].includes((todo.status || '').toLowerCase()));
    const checkpoint = formatCheckpointPreview(resumeState?.checkpoint, 1200);
    const resumeText = resumeTodo
      ? `继续执行当前未完成任务。从第 ${resumeTodo.itemOrder} 个 Todo 开始继续：${resumeTodo.title}。不要重复执行已完成 Todo，只处理 pending/running/failed 的剩余步骤。`
      : '继续执行当前未完成任务。不要重复执行已完成 Todo，只处理 pending/running/failed 的剩余步骤。';
    const resumeContext = [
      resumeText,
      resumeState?.resumeInstruction ? `恢复策略：${resumeState.resumeInstruction}` : '',
      checkpoint ? `恢复点：\n${checkpoint}` : '',
      remainingTodos.length ? `剩余 Todo：\n${remainingTodos.slice(0, 12).map((todo) => `${todo.itemOrder || '-'}.[${todo.status || '-'}] ${todo.title || '未命名 Todo'}`).join('\n')}` : '',
    ].filter(Boolean).join('\n\n');
    await submitRequest({
      inputOverride: resumeContext,
      showUserMessage: false,
      assistantProgress: resumeTodo
        ? `从第 ${resumeTodo.itemOrder} 个 Todo 继续${normalizedProjectPath ? `，项目目录 ${pathBasename(normalizedProjectPath)}` : ''}...`
        : `继续执行未完成任务${normalizedProjectPath ? `，项目目录 ${pathBasename(normalizedProjectPath)}` : ''}...`,
      resumeTaskId: message.taskId,
      projectPathOverride: normalizedProjectPath || undefined,
    });
  }, [changeActiveProjectPath, resumeStateByTaskId, submitRequest, todos, todosByTaskId]);

  const runSlashCommand = useCallback(async () => {
    const command = parseSlashCommand(input);
    if (!command) return false;
    const startedAt = Date.now();
    setInput('');
    try {
      const sessionId = currentSessionId;
      const requireSession = () => {
        if (!sessionId) throw new Error('当前没有可用会话。');
        return sessionId;
      };
      if (command.id === 'resume') {
        const candidate = [...chatMessages].reverse().find((message) => {
          if (message.role !== 'assistant' || !message.taskId) return false;
          return resumeStateByTaskId[message.taskId]?.canResume || isContinuationRequiredMessage(message);
        });
        if (!candidate) {
          appendSlashCommandResult(command.raw, '### /resume\n\n当前没有找到可恢复任务。', startedAt);
          return true;
        }
        setChatMessages((current) => [...current, createUserMessage(command.raw)]);
        await resumeTask(candidate);
        return true;
      }
      if (command.id === 'plan') {
        if (command.args.trim()) {
          await createPlanFromText(command.args, command.raw);
        } else {
          changePlanMode(true);
          appendSlashCommandResult(command.raw, '### /plan\n\n已切换到计划模式。后续发送会先生成计划，再按步骤执行。', startedAt);
        }
        return true;
      }

      let content = '';
      if (command.id === 'clear') {
        const result = await api.clearSessionContext(requireSession(), { reason: 'slash-command' });
        content = commandMarkdown('/clear 已清理模型上下文', [
          ['会话', result.sessionId],
          ['上下文版本', result.contextVersion],
          ['上下文开始时间', formatDateTime(result.contextStartAt)],
          ['受影响消息', result.affectedMessages],
        ], '历史消息仍保留在会话中，只是不再进入后续模型上下文。');
      } else if (command.id === 'compact') {
        const limit = Number(command.args || 120);
        const result = await api.compactSessionContext(requireSession(), {
          taskId: lastTaskId,
          strategy: 'balanced',
          limit: Number.isFinite(limit) ? Math.max(20, Math.min(500, limit)) : 120,
        });
        content = commandMarkdown('/compact 已压缩上下文', [
          ['会话', result.sessionId],
          ['上下文版本', result.contextVersion],
          ['上下文开始时间', formatDateTime(result.contextStartAt)],
          ['摘要长度', result.summary?.length || 0],
        ], result.summary ? `#### 摘要\n\n${result.summary}` : '当前会话暂无可压缩摘要。');
      } else if (command.id === 'context') {
        const context = await api.sessionContext(requireSession());
        content = commandMarkdown('/context 上下文占用', [
          ['上下文版本', context.contextVersion],
          ['上下文开始时间', formatDateTime(context.contextStartAt)],
          ['消息总数', context.totalMessages],
          ['活跃消息', context.activeMessages],
          ['已隔离旧消息', context.inactiveMessages],
          ['估算字符', context.estimatedContextChars],
          ['估算 Token', context.estimatedContextTokens],
          ['累计 Token', formatTokenCount(context.tokenUsage?.totalTokens)],
        ], (context.segments || []).map((segment) => (
          `- ${segment.active ? '●' : '○'} **${segment.label || segment.type}**：${segment.items || 0} 项，约 ${segment.estimatedChars || 0} 字符`
        )).join('\n'));
      } else if (command.id === 'status') {
        const status = await api.sessionRuntimeStatus(requireSession());
        content = commandMarkdown('/status 运行状态', [
          ['会话状态', status.session?.status || '-'],
          ['当前任务', status.currentTask?.id || '-'],
          ['任务状态', status.currentTask?.status || '-'],
          ['工作区', status.workspace?.root || activeProjectPath || '-'],
          ['权限模式', status.permissionMode || approvalMode],
          ['已批准工具', status.approvedToolCount],
          ['MCP', `${status.mcpConnectedCount || 0}/${status.mcpServerCount || 0} 已连接`],
          ['工具数', status.toolCount],
          ['Todo', `${status.todoOpen || 0}/${status.todoTotal || 0} 未完成`],
          ['上下文 Token 估算', status.context?.estimatedContextTokens],
        ]);
      } else if (command.id === 'workspace') {
        const nextPath = stripCommandQuotes(command.args);
        if (nextPath) {
          changeActiveProjectPath(nextPath);
          rememberActiveProjectPath(nextPath);
        }
        const recent = runtimeConfig?.local?.recentProjects || [];
        content = commandMarkdown(nextPath ? '/workspace 已切换项目目录' : '/workspace 当前工作区', [
          ['当前聊天项目', nextPath || activeProjectPath || '-'],
          ['默认工作区', runtimeConfig?.local?.workspaceRoot || '-'],
          ['Allowed Roots', (runtimeConfig?.local?.allowedRoots || []).join(', ') || '-'],
        ], recent.length ? `#### 最近项目\n\n${recent.slice(0, 8).map((item) => `- \`${item}\``).join('\n')}` : '');
      } else if (command.id === 'approval') {
        const requestedMode = command.args.toLowerCase();
        if (requestedMode) {
          if (!isApprovalMode(requestedMode)) {
            throw new Error('权限模式只支持 ask、auto、full、custom。');
          }
          changeApprovalMode(requestedMode);
        }
        const mode = isApprovalMode(requestedMode) ? requestedMode : approvalMode;
        content = commandMarkdown('/approval 权限模式', [
          ['当前模式', mode],
          ['自动批准高危', allowHighRiskForMode(mode) ? '是' : '否'],
          ['自定义工具白名单', approvalSettings.approvedToolIds.length],
        ], '可输入 `/approval ask`、`/approval auto`、`/approval full` 或 `/approval custom` 切换。');
      } else if (command.id === 'mcp') {
        const data = await api.mcpServers();
        setMcpServers(data);
        const connected = data.filter((server) => (server.status || '').toLowerCase() === 'connected').length;
        content = commandMarkdown('/mcp MCP 状态', [
          ['服务数', data.length],
          ['已连接', connected],
        ], data.slice(0, 20).map((server) => (
          `- **${server.name || server.id}**：${server.status || '-'}，工具 ${server.tools?.length || 0}`
        )).join('\n') || '暂无 MCP 服务。');
      } else if (command.id === 'tools') {
        const data = await api.tools();
        setTools(data);
        content = commandMarkdown('/tools 工具列表', [
          ['工具数', data.length],
          ['高危工具', data.filter((tool) => (tool.riskLevel || '').toLowerCase() === 'high').length],
        ], data.slice(0, 60).map((tool) => (
          `- \`${tool.id}\` ${tool.riskLevel ? `(${tool.riskLevel})` : ''} ${tool.description ? `- ${short(tool.description, 80)}` : ''}`
        )).join('\n') || '暂无工具。');
      } else if (command.id === 'todo') {
        const data = await api.todos(requireSession());
        setTodos(data);
        setTodosByTaskId((current) => ({ ...current, ...groupTodosByTask(data) }));
        content = commandMarkdown('/todo Todo 状态', [
          ['Todo 总数', data.length],
          ['未完成', data.filter((todo) => ['pending', 'running', 'failed'].includes((todo.status || '').toLowerCase())).length],
        ], data.slice(0, 30).map((todo) => (
          `- ${todo.itemOrder || '-'}.[${todoStatusText(todo.status)}] ${todo.title || '未命名 Todo'}`
        )).join('\n') || '当前会话暂无 Todo。');
      } else if (command.id === 'diff') {
        const taskId = command.args || lastTaskId;
        if (!taskId) throw new Error('当前没有可查看文件变更的任务。');
        const changes = latestFileChanges(await api.taskFileChanges(taskId));
        content = commandMarkdown('/diff 文件变更', [
          ['任务', taskId],
          ['文件数', changes.length],
        ], changes.slice(0, 40).map((change) => (
          `- ${changeTypeText(change.changeType)} \`${change.path}\` +${change.addedLines || 0} -${change.deletedLines || 0}`
        )).join('\n') || '该任务暂无文件变更。');
      } else if (command.id === 'logs') {
        const logs = await api.queryLogs({
          sessionId,
          keyword: command.args || undefined,
          limit: 30,
        });
        content = commandMarkdown('/logs 服务日志', [
          ['匹配行数', logs.length],
          ['过滤关键词', command.args || '-'],
        ], logs.slice(0, 30).map((line) => (
          `- \`${formatDateTime(line.time)}\` **${line.level || '-'}** ${short(line.logger, 38)}：${short(line.message || line.rawLine, 140)}`
        )).join('\n') || '暂无匹配日志。');
      }
      appendSlashCommandResult(command.raw, content || `### /${command.id}\n\n指令已执行。`, startedAt);
      return true;
    } catch (err) {
      appendSlashCommandError(command.raw, err, startedAt);
      return true;
    }
  }, [
    activeProjectPath,
    approvalMode,
    approvalSettings.approvedToolIds.length,
    appendSlashCommandError,
    appendSlashCommandResult,
    changeActiveProjectPath,
    changeApprovalMode,
    changePlanMode,
    chatMessages,
    createPlanFromText,
    currentSessionId,
    input,
    lastTaskId,
    rememberActiveProjectPath,
    resumeStateByTaskId,
    resumeTask,
    runtimeConfig?.local?.allowedRoots,
    runtimeConfig?.local?.recentProjects,
    runtimeConfig?.local?.workspaceRoot,
  ]);

  const submit = useCallback(async () => {
    if (await runSlashCommand()) return;
    if (planMode) {
      await createPlanFromText();
      return;
    }
    await submitRequest();
  }, [createPlanFromText, planMode, runSlashCommand, submitRequest]);

  const approveToolAndContinue = useCallback((messageId: string, call: ToolCallView) => {
    const source = chatMessages.find((message) => message.id === messageId);
    if (!source?.taskId || !call.stepId || !call.toolId) {
      return;
    }
    updateMessage(messageId, {
      progress: `已批准 ${call.toolId}，等待任务继续执行...`,
      toolsCollapsed: false,
    });
    void api.approveToolCall(source.taskId, call.stepId, call.toolId).then(() => {
      updateMessage(messageId, (message) => upsertToolCall(message, call.stepId, {
        status: 'running',
        stepId: call.stepId,
        toolId: call.toolId,
        approvalRequested: false,
        message: `已审批，继续调用 ${call.toolId}`,
      }, false, `已审批，继续执行：${call.toolId}`));
    }).catch((err) => {
      const message = err instanceof Error ? err.message : String(err);
      updateMessage(messageId, {
        progress: `审批失败：${message}`,
        toolsCollapsed: false,
      });
    });
  }, [chatMessages, updateMessage]);

  const rejectToolAndStop = useCallback((messageId: string, call: ToolCallView) => {
    const source = chatMessages.find((message) => message.id === messageId);
    if (!source?.taskId || !call.stepId || !call.toolId) {
      return;
    }
    updateMessage(messageId, {
      progress: `已拒绝 ${call.toolId}，任务将停止执行。`,
      toolsCollapsed: false,
    });
    void api.rejectToolCall(source.taskId, call.stepId, call.toolId).then(() => {
      updateMessage(messageId, (message) => upsertToolCall(message, call.stepId, {
        status: 'failed',
        stepId: call.stepId,
        toolId: call.toolId,
        approvalRequested: false,
        message: `已拒绝调用 ${call.toolId}`,
      }, false, `已拒绝执行：${call.toolId}`));
    }).catch((err) => {
      const message = err instanceof Error ? err.message : String(err);
      updateMessage(messageId, {
        progress: `拒绝审批失败：${message}`,
        toolsCollapsed: false,
      });
    });
  }, [chatMessages, updateMessage]);

  const createNewSession = useCallback(async () => {
    const created = await api.createSessionId();
    setCurrentSessionId(created.sessionId);
    setSelectedSessionId(undefined);
    setLastTaskId(undefined);
    setTodos([]);
    setTodosByTaskId({});
    setToolCallsByTaskId({});
    setResumeStateByTaskId({});
    setSessionTodos([]);
    setTasks([]);
    setMessages([]);
    setEvents([]);
    setTokenUsage(undefined);
    setTaskTokenUsages({});
    setActiveKnowledgeDocumentIds([]);
    setChatHistoryHasMore(false);
    setChatHistoryLoading(false);
    setChatMessages([createAssistantMessage('已创建新的当前会话。')]);
  }, []);

  const loadRuntimeConfig = useCallback(async () => {
    const configData = await api.runtimeConfig();
    setRuntimeConfig(configData);
    setModelDraft(draftFromConfig(configData));
    const configuredMode = configData.local?.permissionMode;
    if (!window.localStorage.getItem('clawagent.approval.mode') && isApprovalMode(configuredMode)) {
      setApprovalMode(configuredMode);
      setApprovalSettings((current) => ({
        ...current,
        allowHighRiskTools: allowHighRiskForMode(configuredMode),
        approvedToolIds: configData.local?.approvedToolIds?.length ? configData.local.approvedToolIds : current.approvedToolIds,
      }));
    }
    if (!window.localStorage.getItem('clawagent.chat.activeProjectPath') && configData.local?.workspaceRoot) {
      setActiveProjectPath(configData.local.workspaceRoot);
    }
  }, []);

  const loadLocalHealth = useCallback(async (deep = false) => {
    setLocalHealthLoading(true);
    try {
      setLocalHealth(await api.localHealth(deep));
    } catch (err) {
      setConfigMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setLocalHealthLoading(false);
    }
  }, []);

  const dismissSetupWizard = useCallback(() => {
    window.localStorage.setItem('clawagent.setupWizard.dismissed', 'true');
    setSetupWizardDismissed(true);
    setSetupWizardOpen(false);
  }, []);

  const openLocalConfigFromWizard = useCallback(() => {
    setActive('config');
    setConfigTab('local');
    setSetupWizardOpen(false);
  }, []);

  const saveModelConfig = useCallback(async () => {
    setConfigSaving(true);
    setConfigMessage(undefined);
    try {
      const saved = await api.saveModelConfig(modelDraft);
      setRuntimeConfig(saved);
      setModelDraft(draftFromConfig(saved));
      const savedPermissionMode = saved.local?.permissionMode;
      if (configTab === 'local' && isApprovalMode(savedPermissionMode)) {
        setApprovalMode(savedPermissionMode);
        setApprovalSettings((current) => ({
          ...current,
          allowHighRiskTools: allowHighRiskForMode(savedPermissionMode),
          approvedToolIds: saved.local?.approvedToolIds || current.approvedToolIds,
        }));
      }
      setConfigMessage(saved.message || '模型配置已保存，重启服务后生效。');
      if (configTab === 'local') {
        void loadLocalHealth();
      }
    } catch (err) {
      setConfigMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setConfigSaving(false);
    }
  }, [configTab, loadLocalHealth, modelDraft]);

  const savePolicyConfig = useCallback(async () => {
    setConfigSaving(true);
    setConfigMessage(undefined);
    const request: PolicyConfigUpdate = {
      permissionMode: modelDraft.localPermissionMode,
      approvedToolIds: modelDraft.localApprovedToolIds,
      workspaceRoot: modelDraft.localWorkspaceRoot,
      defaultShell: modelDraft.localDefaultShell,
      allowedRoots: modelDraft.localAllowedRoots,
      sensitivePathPatterns: modelDraft.localSensitivePathPatterns,
    };
    try {
      const saved = await api.savePolicyConfig(request);
      setRuntimeConfig(saved);
      setModelDraft(draftFromConfig(saved));
      const savedPermissionMode = saved.local?.permissionMode;
      if (isApprovalMode(savedPermissionMode)) {
        setApprovalMode(savedPermissionMode);
        setApprovalSettings((current) => ({
          ...current,
          allowHighRiskTools: allowHighRiskForMode(savedPermissionMode),
          approvedToolIds: saved.local?.approvedToolIds || current.approvedToolIds,
        }));
      }
      setConfigMessage(saved.message || '审批和本地权限策略已保存。');
    } catch (err) {
      setConfigMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setConfigSaving(false);
    }
  }, [modelDraft]);

  const saveModelDefinition = useCallback(async (request: ModelConfigUpsertRequest) => {
    setConfigSaving(true);
    setConfigMessage(undefined);
    try {
      const saved = await api.saveModelDefinition(request);
      setRuntimeConfig(saved);
      setModelDraft(draftFromConfig(saved));
      setConfigMessage(saved.message || '模型已保存到模型池。');
    } catch (err) {
      setConfigMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setConfigSaving(false);
    }
  }, []);

  const testModelConfig = useCallback(async (request: ModelConfigUpsertRequest) => {
    setModelTesting(true);
    setConfigMessage(undefined);
    setModelTestResult(undefined);
    try {
      const result = await api.testModelApi({
        provider: request.provider,
        baseUrl: request.baseUrl,
        model: request.model,
        apiKey: request.apiKey,
        temperature: request.temperature,
        timeoutSeconds: request.timeoutSeconds,
        prompt: '请用一句中文回复：模型连接正常。',
      });
      setModelTestResult(result);
      setConfigMessage(result.success ? '模型在线测试通过。' : (result.message || '模型在线测试失败。'));
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setModelTestResult({ success: false, message });
      setConfigMessage(message);
    } finally {
      setModelTesting(false);
    }
  }, []);

  const refreshMcpData = useCallback(async () => {
    const [serverData, toolData] = await Promise.all([api.mcpServers(), api.tools()]);
    setMcpServers(serverData);
    setTools(toolData);
  }, []);

  const loadSkillsMenuData = useCallback(async () => {
    const [serverData, toolData, skillData] = await Promise.all([api.mcpServers(), api.tools(), api.skills()]);
    setMcpServers(serverData);
    setTools(toolData);
    setSkills(skillData);
  }, []);

  const importMcpConfig = useCallback(async () => {
    const json = mcpImportJson.trim();
    if (!json) return;
    setMcpUpdating('import');
    setMcpMessage(undefined);
    try {
      const registrations = await api.importMcpServers(json);
      const connected: string[] = [];
      for (const registration of registrations) {
        const id = registration.config?.id || registration.id;
        if (!id) continue;
        // 导入后立即连接，完成 MCP tools 的运行态注册，不要求用户重启服务。
        await api.connectMcpServer(id);
        connected.push(id);
      }
      await refreshMcpData();
      setMcpMessage(connected.length ? `已导入并连接：${connected.join(', ')}` : '已导入 MCP 配置。');
    } catch (err) {
      setMcpMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMcpUpdating(undefined);
    }
  }, [mcpImportJson, refreshMcpData]);

  const updateMcpConnection = useCallback(async (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => {
    setMcpUpdating(`${action}:${serverId}`);
    setMcpMessage(undefined);
    try {
      if (action === 'connect') await api.connectMcpServer(serverId);
      if (action === 'disconnect') await api.disconnectMcpServer(serverId);
      if (action === 'refresh') await api.refreshMcpTools(serverId);
      await refreshMcpData();
      setMcpMessage(action === 'connect' ? 'MCP 已连接并动态加载工具。' : action === 'disconnect' ? 'MCP 已断开。' : 'MCP 工具已刷新。');
    } catch (err) {
      setMcpMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMcpUpdating(undefined);
    }
  }, [refreshMcpData]);

  const deleteMcpServer = useCallback(async (serverId: string) => {
    if (!window.confirm(`确认删除 MCP Server ${serverId}？删除后会断开连接并卸载对应 mcp.* 工具。`)) return;
    setMcpUpdating(`delete:${serverId}`);
    setMcpMessage(undefined);
    try {
      await api.deleteMcpServer(serverId);
      const [serverData, toolData] = await Promise.all([api.mcpServers(), api.tools()]);
      setMcpServers(serverData);
      setTools(toolData);
      setMcpMessage('MCP Server 已删除，工具已同步卸载。');
    } catch (err) {
      setMcpMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setMcpUpdating(undefined);
    }
  }, []);

  const saveMcpServerConfig = useCallback(async (serverId: string, config: McpServerConfig) => {
    setMcpUpdating(`save:${serverId}`);
    setMcpMessage(undefined);
    try {
      await api.updateMcpServer(serverId, config);
      const [serverData, toolData] = await Promise.all([api.mcpServers(), api.tools()]);
      setMcpServers(serverData);
      setTools(toolData);
      setMcpMessage('MCP Server 配置已保存，已断开的服务需要重新连接后生效。');
    } catch (err) {
      setMcpMessage(err instanceof Error ? err.message : String(err));
      throw err;
    } finally {
      setMcpUpdating(undefined);
    }
  }, []);

  const installSkill = useCallback(async () => {
    const text = skillInstallText.trim();
    if (!text) return;
    setSkillUpdating('install');
    setSkillMessage(undefined);
    try {
      if (/^https?:\/\/\S+$/i.test(text)) {
        // GitHub 仓库或 raw SKILL.md URL 走外部导入接口，后端会保留 scripts/assets/references 等资源。
        await api.importSkill({ sourceUrl: text });
      } else {
        let parsedRequest: SkillInstallRequest | undefined;
        try {
          parsedRequest = JSON.parse(text) as SkillInstallRequest;
        } catch {
          // 粘贴 SKILL.md 原文时走导入接口，和 Agent 内部安装工具共用同一套转换规则。
          await api.importSkill({ skillMd: text });
        }
        if (parsedRequest) {
          await api.installSkill(parsedRequest);
        }
      }
      const [skillData, toolData] = await Promise.all([api.skills(), api.tools()]);
      setSkills(skillData);
      setTools(toolData);
      setSkillMessage('Skill 已安装并动态加载。');
    } catch (err) {
      setSkillMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSkillUpdating(undefined);
    }
  }, [skillInstallText]);

  const refreshSkills = useCallback(async () => {
    setSkillUpdating('refresh');
    setSkillMessage(undefined);
    try {
      const skillData = await api.refreshSkills();
      const toolData = await api.tools();
      setSkills(skillData);
      setTools(toolData);
      setSkillMessage('Skill 目录已重新扫描，工具已同步刷新。');
    } catch (err) {
      setSkillMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSkillUpdating(undefined);
    }
  }, []);

  const loadProcesses = useCallback(async () => {
    setProcessLoading(true);
    setProcessMessage(undefined);
    try {
      setProcesses(await api.processes(3000));
    } catch (err) {
      setProcessMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setProcessLoading(false);
    }
  }, []);

  const loadProcessLogs = useCallback(async (pid: number) => {
    setProcessLoading(true);
    setProcessMessage(undefined);
    try {
      setSelectedProcessLogs(await api.processLogs(pid));
    } catch (err) {
      setProcessMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setProcessLoading(false);
    }
  }, []);

  const stopManagedProcess = useCallback(async (pid: number, force = false) => {
    setProcessLoading(true);
    setProcessMessage(undefined);
    try {
      await api.stopProcess(pid, force);
      setProcessMessage(force ? `已强制停止进程 ${pid}` : `已停止进程 ${pid}`);
      setSelectedProcessLogs(undefined);
      setProcesses(await api.processes(3000));
    } catch (err) {
      setProcessMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setProcessLoading(false);
    }
  }, []);

  const toggleSkill = useCallback(async (skillId: string, enabled: boolean) => {
    setSkillUpdating(skillId);
    setSkillMessage(undefined);
    try {
      if (enabled) {
        await api.disableSkill(skillId);
      } else {
        await api.enableSkill(skillId);
      }
      const [skillData, toolData] = await Promise.all([api.skills(), api.tools()]);
      setSkills(skillData);
      setTools(toolData);
      setSkillMessage(enabled ? 'Skill 已禁用，工具已卸载。' : 'Skill 已启用，工具已动态加载。');
    } catch (err) {
      setSkillMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSkillUpdating(undefined);
    }
  }, []);

  const deleteSkill = useCallback(async (skillId: string) => {
    if (!window.confirm(`确认删除 Skill ${skillId}？删除后会移除本地 Skill 目录并卸载对应 skill.* 工具。`)) return;
    setSkillUpdating(`delete:${skillId}`);
    setSkillMessage(undefined);
    try {
      await api.deleteSkill(skillId);
      const [skillData, toolData] = await Promise.all([api.skills(), api.tools()]);
      setSkills(skillData);
      setTools(toolData);
      setSkillMessage('Skill 已删除，工具已同步卸载。');
    } catch (err) {
      setSkillMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setSkillUpdating(undefined);
    }
  }, []);

  const saveSkillManifest = useCallback(async (skillId: string, manifest: NonNullable<SkillRegistration['manifest']>) => {
    setSkillUpdating(`save:${skillId}`);
    setSkillMessage(undefined);
    try {
      await api.updateSkill(skillId, { manifest: { ...manifest, id: skillId } });
      const [skillData, toolData] = await Promise.all([api.skills(), api.tools()]);
      setSkills(skillData);
      setTools(toolData);
      setSkillMessage('Skill manifest 已保存，工具注册已同步刷新。');
    } catch (err) {
      setSkillMessage(err instanceof Error ? err.message : String(err));
      throw err;
    } finally {
      setSkillUpdating(undefined);
    }
  }, []);

  const loadAutomationRuns = useCallback(async (automationId?: string) => {
    if (!automationId) {
      setAutomationRuns([]);
      setAutomationRunDetail(undefined);
      return;
    }
    setAutomationRuns(await api.automationRuns(automationId));
  }, []);

  const viewAutomationRunResult = useCallback(async (run: AutomationRun) => {
    if (!run.taskId) {
      setAutomationRunDetail({
        run,
        messages: [],
        events: [],
        loading: false,
        error: '该运行记录还没有关联 Task ID，可能仍在执行中。',
      });
      return;
    }
    setAutomationRunDetail({ run, messages: [], events: [], loading: true });
    try {
      // 运行历史只保存 taskId，结果、消息和日志继续复用任务域现有接口查询。
      const [taskData, messageData, eventData] = await Promise.all([
        api.task(run.taskId),
        api.taskMessages(run.taskId, 100),
        api.taskEvents(run.taskId, 200),
      ]);
      setAutomationRunDetail({
        run,
        task: taskData,
        messages: messageData,
        events: eventData,
        loading: false,
      });
    } catch (err) {
      setAutomationRunDetail({
        run,
        messages: [],
        events: [],
        loading: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  const refreshAutomations = useCallback(async () => {
    const automationData = await api.automations();
    setAutomations(automationData);
    setSelectedAutomationId((current) => current || automationData[0]?.id);
  }, []);

  const newAutomation = useCallback(() => {
    setSelectedAutomationId(undefined);
    setAutomationRuns([]);
    setAutomationRunDetail(undefined);
    setAutomationDraft(defaultAutomationDraft());
    setAutomationMessage(undefined);
  }, []);

  const selectAutomation = useCallback((automation: AutomationDefinition) => {
    setSelectedAutomationId(automation.id);
    setAutomationDraft(automationDraftFromDefinition(automation));
    setAutomationMessage(undefined);
    setAutomationRunDetail(undefined);
  }, []);

  const saveAutomation = useCallback(async () => {
    setAutomationSaving(true);
    setAutomationMessage(undefined);
    try {
      const body: AutomationUpsertRequest = {
        ...automationDraft,
        name: automationDraft.name?.trim(),
        prompt: automationDraft.prompt?.trim(),
        sessionId: automationDraft.sessionId?.trim() || undefined,
        channelId: automationDraft.channelId?.trim() || undefined,
        userId: automationDraft.userId?.trim() || undefined,
        cronExpression: automationDraft.cronExpression?.trim() || undefined,
        timezone: automationDraft.timezone?.trim() || 'Asia/Shanghai',
        nextRunAt: automationDraft.nextRunAt?.trim() || undefined,
      };
      // 保存后立刻刷新列表，避免调度器重算 nextRunAt 后页面仍显示旧数据。
      const saved = selectedAutomationId
        ? await api.updateAutomation(selectedAutomationId, body)
        : await api.createAutomation(body);
      setSelectedAutomationId(saved.id);
      setAutomationDraft(automationDraftFromDefinition(saved));
      setAutomationMessage('定时任务已保存。');
      await refreshAutomations();
      await loadAutomationRuns(saved.id);
    } catch (err) {
      setAutomationMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setAutomationSaving(false);
    }
  }, [automationDraft, loadAutomationRuns, refreshAutomations, selectedAutomationId]);

  const deleteAutomation = useCallback(async () => {
    if (!selectedAutomationId) return;
    if (!window.confirm('确认删除当前定时任务及运行记录？')) return;
    setAutomationSaving(true);
    try {
      await api.deleteAutomation(selectedAutomationId);
      setAutomationMessage('定时任务已删除。');
      newAutomation();
      await refreshAutomations();
    } catch (err) {
      setAutomationMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setAutomationSaving(false);
    }
  }, [newAutomation, refreshAutomations, selectedAutomationId]);

  const toggleAutomation = useCallback(async (automation: AutomationDefinition) => {
    setAutomationSaving(true);
    try {
      // 启停只改变调度状态，不改写用户维护的 prompt 和周期参数。
      const saved = automation.status === 'ENABLED'
        ? await api.pauseAutomation(automation.id)
        : await api.enableAutomation(automation.id);
      setAutomationDraft(automationDraftFromDefinition(saved));
      setSelectedAutomationId(saved.id);
      await refreshAutomations();
    } catch (err) {
      setAutomationMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setAutomationSaving(false);
    }
  }, [refreshAutomations]);

  const runAutomationNow = useCallback(async (automationId?: string) => {
    if (!automationId) return;
    setAutomationSaving(true);
    try {
      const saved = await api.runAutomation(automationId);
      setAutomationDraft(automationDraftFromDefinition(saved));
      setSelectedAutomationId(saved.id);
      setAutomationMessage('已触发一次立即运行。');
      await Promise.all([refreshAutomations(), loadAutomationRuns(automationId)]);
    } catch (err) {
      setAutomationMessage(err instanceof Error ? err.message : String(err));
    } finally {
      setAutomationSaving(false);
    }
  }, [loadAutomationRuns, refreshAutomations]);

  useEffect(() => {
    if (active === 'automations' && !automations.length) {
      void refreshAutomations();
    }
  }, [active, automations.length, refreshAutomations]);

  useEffect(() => {
    if (active === 'automations' && selectedAutomationId) {
      void loadAutomationRuns(selectedAutomationId);
    }
  }, [active, loadAutomationRuns, selectedAutomationId]);

  useEffect(() => {
    if (active === 'skills' && (!mcpServers.length || !skills.length)) {
      void loadSkillsMenuData();
    }
  }, [active, loadSkillsMenuData, mcpServers.length, skills.length]);

  useEffect(() => {
    if ((active === 'chat' || active === 'config' || active === 'auth') && !runtimeConfig) {
      void loadRuntimeConfig();
    }
  }, [active, loadRuntimeConfig, runtimeConfig]);

  useEffect(() => {
    if (!runtimeConfig || setupWizardDismissed || setupWizardComplete || localHealth || localHealthLoading) {
      return;
    }
    void loadLocalHealth(false);
  }, [loadLocalHealth, localHealth, localHealthLoading, runtimeConfig, setupWizardComplete, setupWizardDismissed]);

  useEffect(() => {
    if (!runtimeConfig || setupWizardDismissed || setupWizardComplete || active === 'config') {
      return;
    }
    setSetupWizardOpen(true);
  }, [active, runtimeConfig, setupWizardComplete, setupWizardDismissed]);

  useEffect(() => {
    if (active === 'config' && configTab === 'local' && !localHealth && !localHealthLoading) {
      void loadLocalHealth();
    }
  }, [active, configTab, loadLocalHealth, localHealth, localHealthLoading]);

  useEffect(() => {
    if (active === 'nodes') {
      void loadProcesses();
    }
  }, [active, loadProcesses]);

  useEffect(() => {
    if (active === 'overview' && (!sessions.length || !mcpServers.length || !skills.length || !automations.length)) {
      void (async () => {
        const [healthData, sessionData, toolData, serverData, skillData, automationData] = await Promise.all([
          api.health(),
          api.sessions(DEFAULT_PAGE_LIMIT),
          api.tools(),
          api.mcpServers(),
          api.skills(),
          api.automations(),
        ]);
        setHealth(healthData);
        setSessions(sessionData);
        setTools(toolData);
        setMcpServers(serverData);
        setSkills(skillData);
        setAutomations(automationData);
        setSelectedSessionId((current) => current || sessionData[0]?.id);
        setSelectedAutomationId((current) => current || automationData[0]?.id);
      })().catch((err) => setError(err instanceof Error ? err.message : String(err)));
    }
  }, [active, automations.length, mcpServers.length, sessions.length, skills.length]);

  const failedTasks = tasks.filter((task) => task.status === 'FAILED').length;
  const runningTasks = tasks.filter((task) => task.status === 'RUNNING').length;
  const mcpConnected = mcpServers.filter((server) => server.connected || server.status === 'CONNECTED').length;
  const enabledSkills = skills.filter((skill) => skill.manifest?.enabled).length;
  const activeMeta = pageMeta[active];
  const visibleNavGroups = useMemo(() => {
    const keyword = navSearch.trim().toLowerCase();
    if (!keyword) return navGroups;
    return navGroups
      .map((group) => ({
        ...group,
        items: group.items.filter(
          (item) => item.label.toLowerCase().includes(keyword) || item.key.toLowerCase().includes(keyword) || group.title.toLowerCase().includes(keyword),
        ),
      }))
      .filter((group) => group.items.length > 0);
  }, [navSearch]);

  useEffect(() => {
    window.localStorage.setItem('clawagent.sidebarCollapsed', String(sidebarCollapsed));
  }, [sidebarCollapsed]);

  useEffect(() => {
    window.localStorage.setItem('clawagent.sidebarGroups', JSON.stringify(collapsedGroups));
  }, [collapsedGroups]);

  const toggleSidebar = useCallback(() => {
    setSidebarCollapsed((value) => {
      const next = !value;
      if (next) setNavSearch('');
      return next;
    });
  }, []);

  const toggleNavGroup = useCallback((title: string) => {
    setCollapsedGroups((current) => ({ ...current, [title]: !current[title] }));
  }, []);

  return (
    <div className={`app-shell ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark">
            <img src={BRAND_LOGO_URL} alt="ClawAgent" />
          </div>
          <div>
            <strong>ClawAgent</strong>
            <span>MANAGEMENT CONSOLE</span>
          </div>
        </div>
        <div className="top-actions">
          <StatusPill label="版本" value="0.1.0" />
          <StatusPill label="健康状况" value={health.status === 'UP' ? '正常' : '异常'} />
          <button className="icon-button" onClick={() => void loadCore()} disabled={loading} title="刷新">
            <RefreshCw size={17} />
          </button>
          <button className={`icon-button ${active === 'overview' ? 'active' : ''}`} onClick={() => setActive('overview')} title="概览">
            <Bot size={17} />
          </button>
          <button className={`icon-button ${active === 'nodes' ? 'active' : ''}`} onClick={() => setActive('nodes')} title="节点">
            <Monitor size={17} />
          </button>
        </div>
      </header>

      <aside className="sidebar">
        <div className="sidebar-tools">
          {!sidebarCollapsed && (
            <label className="nav-search">
              <Search size={15} />
              <input value={navSearch} onChange={(event) => setNavSearch(event.target.value)} placeholder="搜索菜单" />
            </label>
          )}
          <button className="sidebar-toggle" onClick={toggleSidebar} title={sidebarCollapsed ? '展开侧栏' : '收起侧栏'}>
            {sidebarCollapsed ? <PanelLeftOpen size={17} /> : <PanelLeftClose size={17} />}
          </button>
        </div>
        {visibleNavGroups.map((group, groupIndex) => {
          const groupCollapsed = !navSearch.trim() && Boolean(collapsedGroups[group.title]);
          return (
          <div className="nav-group" key={`${group.title}-${groupIndex}`}>
            {!sidebarCollapsed && (
              <button type="button" className="nav-group-title nav-group-toggle" onClick={() => toggleNavGroup(group.title)}>
                <span>{group.title}</span>
                <span>{groupCollapsed ? '+' : '-'}</span>
              </button>
            )}
            {!groupCollapsed && group.items.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={`${group.title}-${item.key}`}
                  className={active === item.key ? 'active' : ''}
                  onClick={() => setActive(item.key)}
                  title={sidebarCollapsed ? item.label : undefined}
                >
                  <Icon size={18} />
                  {!sidebarCollapsed && item.label}
                </button>
              );
            })}
          </div>
          );
        })}
        {!sidebarCollapsed && visibleNavGroups.length === 0 && <div className="empty-nav">没有匹配菜单</div>}
      </aside>

      <main className="content">
        {error && <div className="notice danger">请求失败：{error}</div>}
        {active === 'chat' ? (
          <ChatPage
            currentSessionId={currentSessionId}
            input={input}
            attachments={attachments}
            running={running}
            planMode={planMode}
            planBusyId={planBusyId}
            planTemplates={planTemplates}
            selectedPlanTemplateId={selectedPlanTemplateId}
            messages={chatMessages}
            historyHasMore={chatHistoryHasMore}
            historyLoading={chatHistoryLoading}
            todos={todos}
            todosByTaskId={todosByTaskId}
            toolCallsByTaskId={toolCallsByTaskId}
            resumeStateByTaskId={resumeStateByTaskId}
            lastTaskId={lastTaskId}
            highRiskTools={highRiskTools}
            approvalSettings={approvalSettings}
            approvalMode={approvalMode}
            knowledgeDocuments={knowledgeDocuments}
            selectedKnowledgeDocumentIds={selectedKnowledgeDocumentIds}
            activeProjectPath={activeProjectPath}
            localConfig={runtimeConfig?.local}
            onInputChange={(value) => setInput(normalizeEscapedNewlines(value))}
            onActiveProjectPathChange={changeActiveProjectPath}
            onAddAttachments={addAttachments}
            onRemoveAttachment={removeAttachment}
            onToggleKnowledgeDocument={toggleKnowledgeDocument}
            onSetKnowledgeDocuments={setSelectedKnowledgeDocuments}
            onSubmit={() => void submit()}
            onPlanModeChange={changePlanMode}
            onPlanTemplateChange={changePlanTemplate}
            onRevisePlan={(planId, feedback) => void revisePlan(planId, feedback)}
            onRunPlan={(planId) => void runPlan(planId)}
            onCancelPlan={(planId) => void cancelPlan(planId)}
            onResumeTask={(message) => void resumeTask(message)}
            onConfirmProjectDirectory={(message, projectPath) => void resumeTask(message, projectPath)}
            onNewSession={() => void createNewSession()}
            onLoadOlderMessages={() => void loadOlderChatMessages()}
            onRefreshTodos={() => void loadTodos()}
            onToggleTools={(messageId) => updateMessage(messageId, (message) => ({ ...message, toolsCollapsed: !message.toolsCollapsed }))}
            onApproveTool={approveToolAndContinue}
            onRejectTool={rejectToolAndStop}
            onApprovalModeChange={changeApprovalMode}
            onToggleApprovedTool={toggleApprovedTool}
            onTaskDevelopmentSummaryChange={(taskId) => {
              if (taskDetail?.task?.id !== taskId) return;
              api.developmentSummary(taskId)
                .then((developmentSummary) => setTaskDetail((current) => current ? { ...current, developmentSummary } : current))
                .catch(() => undefined);
            }}
          />
        ) : (
          <>
            <PageHeader title={activeMeta.title} subtitle={activeMeta.subtitle} />
            {active === 'overview' && (
              <section className="stack">
                <div className="metric-grid">
                  <Metric title="会话数" value={sessions.length} desc="已加载会话" onClick={() => setActive('sessions')} />
                  <Metric title="工具数" value={tools.length} desc="本地 + MCP + Skill" onClick={() => setActive('skills')} />
                  <Metric title="MCP 已连接" value={`${mcpConnected}/${mcpServers.length}`} desc="当前 JVM 运行态" onClick={() => setActive('skills')} />
                  <Metric title="启用 Skill" value={`${enabledSkills}/${skills.length}`} desc="本地安装目录" onClick={() => setActive('skills')} />
                  <Metric
                    title="异常执行"
                    value={failedTasks}
                    desc={`当前会话运行中 ${runningTasks}`}
                    onClick={() => {
                      setActive('sessions');
                      setDetailTab('tasks');
                    }}
                  />
                </div>
                <div className="two-col">
                  <Panel title="最近会话" action={<button onClick={() => setActive('sessions')}>查看全部</button>}>
                    <SessionTable sessions={sessions.slice(0, 8)} selectedId={selectedSessionId} onSelect={setSelectedSessionId} />
                  </Panel>
                  <Panel title="高风险工具">
                    <ToolTable tools={tools.filter((tool) => riskClass(tool.riskLevel) !== 'success').slice(0, 8)} compact />
                  </Panel>
                </div>
              </section>
            )}

            {active === 'sessions' && (
              <section className="sessions-layout">
                <Panel title="历史会话" action={<button onClick={() => void loadCore()}>刷新</button>}>
                  <TaskStepSearchPanel
                    filter={taskStepSearchFilter}
                    taskResults={taskSearchResults}
                    stepResults={stepSearchResults}
                    loading={taskStepSearchLoading}
                      message={taskStepSearchMessage}
                      onFilterChange={setTaskStepSearchFilter}
                      onSearch={() => void runTaskStepSearch()}
                      onReset={resetTaskStepSearch}
                      onOpenTask={(taskId) => void openTaskSearchResult(taskId)}
                    />
                  <SessionTable sessions={sessions} selectedId={selectedSessionId} onSelect={setSelectedSessionId} />
                </Panel>
                <Panel
                  title={selectedSession ? short(selectedSession.title || selectedSession.id, 70) : '会话详情'}
                  action={selectedSession ? (
                    <button
                      className="primary-button"
                      onClick={() => void restoreSessionToChat(selectedSession.id)}
                      title="恢复此会话到聊天窗口继续对话"
                      type="button"
                    >
                      恢复到聊天
                    </button>
                  ) : undefined}
                >
                  {selectedSession ? (
                    <>
                      <div className="session-summary">
                        <span>会话ID：{selectedSession.id}</span>
                        <span>用户：{selectedSession.userId || '-'}</span>
                        <span>更新时间：{formatDateTime(selectedSession.updatedAt)}</span>
                      </div>
                      <div className="tab-row">
                        {(['tasks', 'messages', 'events', 'todos', 'tokens'] as DetailTab[]).map((tab) => (
                          <button key={tab} className={detailTab === tab ? 'active' : ''} onClick={() => setDetailTab(tab)}>
                            {tab === 'tasks' ? '执行记录' : tab === 'messages' ? '消息' : tab === 'events' ? '日志' : tab === 'todos' ? 'Todo' : 'Token'}
                          </button>
                        ))}
                      </div>
                      {detailTab === 'tasks' && (
                        <section className="stack">
                          <TaskTable
                            tasks={tasks}
                            selectedTaskId={taskDetail?.task?.id}
                            onViewTask={(task) => void viewTaskDetail(task)}
                          />
                          {taskDetail && (
                            <TaskDetailPanel
                              detail={taskDetail}
                              activeTab={taskDetailTab}
                              onTabChange={setTaskDetailTab}
                              onClose={() => setTaskDetail(undefined)}
                              onViewTask={(task) => void viewTaskDetail(task)}
                              onCreateSubAgent={(task, request) => void createSubAgentTask(task, request)}
                            />
                          )}
                        </section>
                      )}
                      {detailTab === 'messages' && <MessageChatView messages={messages} />}
                      {detailTab === 'events' && <EventTable events={events} />}
                      {detailTab === 'todos' && <TodoList todos={sessionTodos} />}
                      {detailTab === 'tokens' && (
                        <SessionTokenPanel
                          sessionUsage={tokenUsage}
                          tasks={tasks}
                          taskUsages={taskTokenUsages}
                          cost={runtimeConfig?.cost}
                        />
                      )}
                    </>
                  ) : (
                    <Empty text="暂无选中会话" />
                  )}
                </Panel>
              </section>
            )}

            {active === 'automations' && (
              <AutomationPage
                automations={automations}
                selectedAutomation={selectedAutomation}
                draft={automationDraft}
                runs={automationRuns}
                saving={automationSaving}
                message={automationMessage}
                runDetail={automationRunDetail}
                onSelect={selectAutomation}
                onDraftChange={setAutomationDraft}
                onNew={newAutomation}
                onSave={() => void saveAutomation()}
                onDelete={() => void deleteAutomation()}
                onToggle={(automation) => void toggleAutomation(automation)}
                onRun={(automationId) => void runAutomationNow(automationId)}
                onRefresh={() => void refreshAutomations()}
                onViewRunResult={(run) => void viewAutomationRunResult(run)}
                onCloseRunDetail={() => setAutomationRunDetail(undefined)}
              />
            )}

            {active === 'knowledge' && (
              <KnowledgePage
                providers={knowledgeProviders}
                documents={knowledgeDocuments}
                vectorStatus={knowledgeVectorStatus}
                selectedIds={selectedKnowledgeDocumentIds}
                loading={knowledgeLoading}
                message={knowledgeMessage}
                searchQuery={knowledgeSearchQuery}
                searchMode={knowledgeSearchMode}
                searchHits={knowledgeSearchHits}
                onUpload={(files) => void uploadKnowledgeDocuments(files)}
                onRefresh={() => void refreshKnowledgeDocuments()}
                onDelete={(documentId) => void deleteKnowledgeDocument(documentId)}
                onToggleSelected={toggleKnowledgeDocument}
                onSearchQueryChange={setKnowledgeSearchQuery}
                onSearchModeChange={setKnowledgeSearchMode}
                onSearch={() => void searchKnowledge()}
              />
            )}

            {active === 'memory' && (
              <MemoryPage
                items={memoryItems}
                vectorStatus={memoryVectorStatus}
                candidates={memoryCandidates}
                hits={memoryHits}
                searchHits={memorySearchHits}
                selectedId={selectedMemoryId}
                draft={memoryDraft}
                loading={memoryLoading}
                message={memoryMessage}
                searchQuery={memorySearchQuery}
                searchMode={memorySearchMode}
                onRefresh={() => void refreshMemoryData()}
                onSelect={selectMemoryItem}
                onNew={newMemoryItem}
                onDraftChange={setMemoryDraft}
                onSave={() => void saveMemoryItem()}
                onDelete={(itemId) => void deleteMemoryItem(itemId)}
                onStatus={(itemId, action) => void updateMemoryStatus(itemId, action)}
                onSearchQueryChange={setMemorySearchQuery}
                onSearchModeChange={setMemorySearchMode}
                onSearch={() => void searchMemory()}
              />
            )}

            {active === 'skills' && (
              <SkillsPage
                tools={tools}
                mcpServers={mcpServers}
                mcpImportJson={mcpImportJson}
                mcpUpdating={mcpUpdating}
                mcpMessage={mcpMessage}
                skills={skills}
                skillInstallText={skillInstallText}
                skillUpdating={skillUpdating}
                skillMessage={skillMessage}
                config={runtimeConfig}
                configDraft={modelDraft}
                configSaving={configSaving}
                configMessage={configMessage}
                onMcpImportJsonChange={setMcpImportJson}
                onMcpImport={() => void importMcpConfig()}
                onMcpRefresh={() => void refreshMcpData()}
                onMcpConnection={(serverId, action) => void updateMcpConnection(serverId, action)}
                onMcpDelete={(serverId) => void deleteMcpServer(serverId)}
                onMcpSave={(serverId, config) => saveMcpServerConfig(serverId, config)}
                onSkillInstallTextChange={setSkillInstallText}
                onSkillInstall={() => void installSkill()}
                onSkillRefresh={() => void refreshSkills()}
                onSkillToggle={toggleSkill}
                onSkillDelete={(skillId) => void deleteSkill(skillId)}
                onSkillSave={(skillId, manifest) => saveSkillManifest(skillId, manifest)}
                onConfigDraftChange={setModelDraft}
                onConfigSave={savePolicyConfig}
              />
            )}

            {active === 'channels' && (
              <ChannelPage
                channels={channels}
                adapters={channelAdapters}
                draft={channelDraft}
                loading={channelLoading}
                saving={channelSaving}
                adapterReloading={channelAdapterReloading}
                adapterUploading={channelAdapterUploading}
                adapterDeleting={channelAdapterDeleting}
                message={channelMessage}
                testText={channelTestText}
                testResult={channelTestResult}
                outboundConversationId={channelOutboundConversationId}
                outboundText={channelOutboundText}
                outboundResult={channelOutboundResult}
                userBindings={channelUserBindings}
                users={localUsers}
                bindingExternalUserId={channelBindingExternalUserId}
                bindingExternalUsername={channelBindingExternalUsername}
                bindingLocalUserId={channelBindingLocalUserId}
                bindingLoading={channelBindingLoading}
                onRefresh={() => void refreshChannels()}
                onReloadAdapters={() => void reloadChannelAdapters()}
                onUploadAdapter={(file) => void uploadChannelAdapter(file)}
                onDeleteAdapter={(filename) => void deleteChannelAdapter(filename)}
                onSelect={selectChannel}
                onNew={newChannel}
                onDraftChange={setChannelDraft}
                onSave={() => void saveChannel()}
                onDelete={(channelId) => void deleteChannel(channelId)}
                onTestTextChange={setChannelTestText}
                onSubmitTest={() => void submitChannelTest()}
                onOutboundConversationIdChange={setChannelOutboundConversationId}
                onOutboundTextChange={setChannelOutboundText}
                onSubmitOutboundTest={() => void submitChannelOutboundTest()}
                health={channelHealth}
                onCheckHealth={() => void checkChannelHealth()}
                streamStatus={channelStreamStatus}
                onRefreshStream={() => void refreshChannelStreamStatus()}
                onStartStream={() => void startChannelStream()}
                onStopStream={() => void stopChannelStream()}
                onRefreshUserBindings={() => void refreshChannelUserBindings()}
                onBindingExternalUserIdChange={setChannelBindingExternalUserId}
                onBindingExternalUsernameChange={setChannelBindingExternalUsername}
                onBindingLocalUserIdChange={setChannelBindingLocalUserId}
                onBindChannelUser={() => void bindChannelUser()}
                onUnbindChannelUser={(externalUserId) => void unbindChannelUser(externalUserId)}
              />
            )}

            {active === 'auth' && (
              <AuthPage
                tokens={apiTokens}
                tokenName={apiTokenName}
                config={runtimeConfig}
                loading={apiTokenLoading}
                saving={apiTokenSaving}
                message={apiTokenMessage}
                createdToken={createdApiToken}
                users={localUsers}
                currentUser={currentLocalUser}
                currentSession={currentLocalSession}
                loginUsername={localLoginUsername}
                loginPassword={localLoginPassword}
                userUsername={localUserUsername}
                userPassword={localUserPassword}
                userDisplayName={localUserDisplayName}
                userRole={localUserRole}
                userPermissionMode={localUserPermissionMode}
                userApprovedToolIds={localUserApprovedToolIds}
                userLoading={localUserLoading}
                userSaving={localUserSaving}
                userMessage={localUserMessage}
                sessions={localUserSessions}
                sessionLoading={localUserSessionLoading}
                onRefresh={() => {
                  void refreshApiTokens();
                  void refreshLocalUsers();
                  void refreshLocalUserSessions();
                }}
                onClearCreatedToken={() => setCreatedApiToken(undefined)}
                onTokenNameChange={setApiTokenName}
                onCreate={() => void createApiToken()}
                onRevoke={(tokenId) => void deleteApiToken(tokenId)}
                onLoginUsernameChange={setLocalLoginUsername}
                onLoginPasswordChange={setLocalLoginPassword}
                onLogin={() => void loginLocalUser()}
                onLogout={() => void logoutLocalUser()}
                onUserUsernameChange={setLocalUserUsername}
                onUserPasswordChange={setLocalUserPassword}
                onUserDisplayNameChange={setLocalUserDisplayName}
                onUserRoleChange={setLocalUserRole}
                onUserPermissionModeChange={setLocalUserPermissionMode}
                onUserApprovedToolIdsChange={setLocalUserApprovedToolIds}
                onCreateUser={() => void createLocalUser()}
                onChangeUserPassword={(userId) => void changeLocalUserPassword(userId)}
                onUpdateUserPermissions={(user) => void updateLocalUserPermissions(user)}
                onDisableUser={(userId) => void disableLocalUser(userId)}
                onRevokeSession={(sessionId) => void revokeLocalUserSession(sessionId)}
              />
            )}

            {active === 'devices' && (
              <DevicePage
                devices={devices}
                deviceName={deviceName}
                deviceType={deviceType}
                devicePermissionMode={devicePermissionMode}
                deviceApprovedToolIds={deviceApprovedToolIds}
                pairingTtlSeconds={devicePairingTtlSeconds}
                createdPairing={createdDevicePairing}
                loading={deviceLoading}
                saving={deviceSaving}
                message={deviceMessage}
                onRefresh={() => void refreshDevices()}
                onDeviceNameChange={setDeviceName}
                onDeviceTypeChange={setDeviceType}
                onDevicePermissionModeChange={setDevicePermissionMode}
                onDeviceApprovedToolIdsChange={setDeviceApprovedToolIds}
                onPairingTtlSecondsChange={setDevicePairingTtlSeconds}
                onRegister={() => void registerDevice()}
                onCreatePairingCode={() => void createDevicePairingCode()}
                onHeartbeat={(deviceId) => void heartbeatDevice(deviceId)}
                onRotateSecret={(deviceId) => void rotateDeviceSecret(deviceId)}
                onBindUser={(deviceId) => void bindDeviceUser(deviceId)}
                onUpdatePermissions={(deviceId) => void updateDevicePermissions(deviceId)}
                onRevoke={(deviceId) => void revokeDevice(deviceId)}
              />
            )}

            {active === 'config' && (
              <section className="stack">
                <div className="settings-tab-row">
                  <button className={configTab === 'models' ? 'active' : undefined} onClick={() => setConfigTab('models')}><Bot size={15} />模型 API</button>
                  <button className={configTab === 'embedding' ? 'active' : undefined} onClick={() => setConfigTab('embedding')}><Database size={15} />向量模型</button>
                  <button className={configTab === 'memory' ? 'active' : undefined} onClick={() => setConfigTab('memory')}><Clock size={15} />记忆处理</button>
                  <button className={configTab === 'cost' ? 'active' : undefined} onClick={() => setConfigTab('cost')}><BarChart3 size={15} />成本规则</button>
                  <button className={configTab === 'capabilities' ? 'active' : undefined} onClick={() => setConfigTab('capabilities')}><Wrench size={15} />内置能力</button>
                  <button className={configTab === 'local' ? 'active' : undefined} onClick={() => setConfigTab('local')}><Settings size={15} />本地配置</button>
                </div>
                {configTab === 'local' && (
                  <LocalConfigPanel
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    message={configMessage}
                    health={localHealth}
                    healthLoading={localHealthLoading}
                    onChange={setModelDraft}
                    onSave={saveModelConfig}
                    onHealthRefresh={loadLocalHealth}
                  />
                )}
                {configTab === 'models' && (
                  <ModelConfigPanel
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    testing={modelTesting}
                    testResult={modelTestResult}
                    message={configMessage}
                    onChange={setModelDraft}
                    onSave={saveModelConfig}
                    onSaveModel={saveModelDefinition}
                    onTestModel={testModelConfig}
                  />
                )}
                {configTab === 'embedding' && (
                  <EmbeddingConfigPanel
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    knowledgeVectorStatus={knowledgeVectorStatus}
                    memoryVectorStatus={memoryVectorStatus}
                    message={configMessage}
                    onChange={setModelDraft}
                    onSave={savePolicyConfig}
                  />
                )}
                {configTab === 'memory' && (
                  <MemoryExtractionConfigPanel
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    message={configMessage}
                    onChange={setModelDraft}
                    onSave={saveModelConfig}
                  />
                )}
                {configTab === 'cost' && (
                  <CostConfigPanel
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    message={configMessage}
                    onChange={setModelDraft}
                    onSave={saveModelConfig}
                  />
                )}
                {configTab === 'capabilities' && (
                  <CapabilityConfigPanel
                    tools={tools}
                    draft={modelDraft}
                    config={runtimeConfig}
                    saving={configSaving}
                    message={configMessage}
                    onChange={setModelDraft}
                    onSave={saveModelConfig}
                  />
                )}
              </section>
            )}

            {active === 'logs' && (
              <SystemLogPage
                filter={systemLogFilter}
                logs={systemLogs}
                sources={systemLogSources}
                loading={systemLogLoading}
                message={systemLogMessage}
                selectedLog={selectedSystemLog}
                onFilterChange={setSystemLogFilter}
                onQuery={() => void loadSystemLogs()}
                onReset={() => {
                  const next = defaultSystemLogFilter();
                  setSystemLogFilter(next);
                  void loadSystemLogs(next);
                }}
                onSelect={setSelectedSystemLog}
                onCloseDetail={() => setSelectedSystemLog(undefined)}
              />
            )}

            {active === 'audit' && (
              <AuditEventPage
                filter={auditFilter}
                events={auditEvents}
                loading={auditLoading}
                message={auditMessage}
                selectedEvent={selectedAuditEvent}
                onFilterChange={setAuditFilter}
                onQuery={() => void loadAuditEvents()}
                onReset={() => {
                  const next = defaultAuditEventFilter();
                  setAuditFilter(next);
                  void loadAuditEvents(next);
                }}
                onSelect={setSelectedAuditEvent}
                onCloseDetail={() => setSelectedAuditEvent(undefined)}
              />
            )}

            {active === 'nodes' && (
              <ProcessPage
                processes={processes}
                logs={selectedProcessLogs}
                loading={processLoading}
                message={processMessage}
                onRefresh={() => void loadProcesses()}
                onLogs={(pid) => void loadProcessLogs(pid)}
                onStop={(pid, force) => void stopManagedProcess(pid, force)}
              />
            )}
          </>
        )}
      </main>
      {setupWizardOpen && (
        <div className="modal-backdrop" onClick={dismissSetupWizard}>
          <div className="modal-shell setup-wizard-modal" onClick={(event) => event.stopPropagation()}>
            <div className="modal-head">
              <div>
                <h3>本地行动 Agent 初始化</h3>
                <p>完成工作区、模型、权限和健康检查后，再开始让 Agent 操作本地项目。</p>
              </div>
              <button className="tiny-button" type="button" onClick={dismissSetupWizard}><X size={14} />稍后再说</button>
            </div>
            <SetupWizardContent
              steps={setupWizardSteps}
              health={localHealth}
              healthLoading={localHealthLoading}
              saving={configSaving}
              onHealthRefresh={loadLocalHealth}
              onSave={saveModelConfig}
            />
            <div className="setup-wizard-modal-actions">
              <button type="button" onClick={openLocalConfigFromWizard}><Settings size={14} />打开本地配置</button>
              <button className="send-button" type="button" onClick={dismissSetupWizard}>我知道了</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function createUserMessage(content: string, attachments: ChatAttachment[] = []): ChatMessage {
  return {
    id: crypto.randomUUID(),
    role: 'user',
    content,
    attachments,
    createdAt: Date.now(),
    toolCalls: [],
    toolsCollapsed: true,
  };
}

function createAssistantMessage(content: string, progress = ''): ChatMessage {
  return {
    id: crypto.randomUUID(),
    role: 'assistant',
    content,
    progress,
    createdAt: Date.now(),
    toolCalls: [],
    toolsCollapsed: false,
  };
}

function upsertToolCall(message: ChatMessage, stepId: string | undefined, toolCall: ToolCallView, collapsed: boolean, progress: string): ChatMessage {
  const index = message.toolCalls.findIndex((item) => item.stepId && item.stepId === stepId);
  const next = [...message.toolCalls];
  if (index >= 0) {
    next[index] = { ...next[index], ...toolCall };
  } else {
    next.push(toolCall);
  }
  return { ...message, toolCalls: next, toolsCollapsed: collapsed, progress };
}

function SlashCommandMenu({ commands, onSelect }: { commands: SlashCommandDefinition[]; onSelect: (command: SlashCommandDefinition) => void }) {
  return (
    <div className="slash-command-menu">
      {commands.map((command) => (
        <button type="button" key={command.id} onClick={() => onSelect(command)}>
          <span className="slash-command-name">/{command.id}</span>
          <span className="slash-command-title">{command.title}</span>
          <span className="slash-command-desc">{command.description}</span>
        </button>
      ))}
    </div>
  );
}

function ChatPage({
  currentSessionId,
  input,
  attachments,
  running,
  planMode,
  planBusyId,
  planTemplates,
  selectedPlanTemplateId,
  messages,
  historyHasMore,
  historyLoading,
  todos,
  todosByTaskId,
  toolCallsByTaskId,
  resumeStateByTaskId,
  lastTaskId,
  highRiskTools,
  approvalSettings,
  approvalMode,
  knowledgeDocuments,
  selectedKnowledgeDocumentIds,
  activeProjectPath,
  localConfig,
  onInputChange,
  onActiveProjectPathChange,
  onAddAttachments,
  onRemoveAttachment,
  onToggleKnowledgeDocument,
  onSetKnowledgeDocuments,
  onSubmit,
  onPlanModeChange,
  onPlanTemplateChange,
  onRevisePlan,
  onRunPlan,
  onCancelPlan,
  onResumeTask,
  onConfirmProjectDirectory,
  onNewSession,
  onLoadOlderMessages,
  onRefreshTodos,
  onToggleTools,
  onApproveTool,
  onRejectTool,
  onApprovalModeChange,
  onToggleApprovedTool,
  onTaskDevelopmentSummaryChange,
}: {
  currentSessionId?: string;
  input: string;
  attachments: ComposerAttachment[];
  running: boolean;
  planMode: boolean;
  planBusyId?: string;
  planTemplates: PlanTemplateView[];
  selectedPlanTemplateId: string;
  messages: ChatMessage[];
  historyHasMore: boolean;
  historyLoading: boolean;
  todos: TodoItem[];
  todosByTaskId: Record<string, TodoItem[]>;
  toolCallsByTaskId: Record<string, ToolCallView[]>;
  resumeStateByTaskId: Record<string, ResumeStateView>;
  lastTaskId?: string;
  highRiskTools: ToolDefinition[];
  approvalSettings: ApprovalSettings;
  approvalMode: ApprovalMode;
  knowledgeDocuments: KnowledgeDocument[];
  selectedKnowledgeDocumentIds: string[];
  activeProjectPath: string;
  localConfig?: RuntimeConfigSnapshot['local'];
  onInputChange: (value: string) => void;
  onActiveProjectPathChange: (value: string) => void;
  onAddAttachments: (files: FileList | File[]) => void;
  onRemoveAttachment: (attachmentId: string) => void;
  onToggleKnowledgeDocument: (documentId: string) => void;
  onSetKnowledgeDocuments: (documentIds: string[]) => void;
  onSubmit: () => void;
  onPlanModeChange: (enabled: boolean) => void;
  onPlanTemplateChange: (templateId: string) => void;
  onRevisePlan: (planId: string, feedback: string) => void;
  onRunPlan: (planId: string) => void;
  onCancelPlan: (planId: string) => void;
  onResumeTask: (message: ChatMessage) => void;
  onConfirmProjectDirectory: (message: ChatMessage, projectPath: string) => void;
  onNewSession: () => void;
  onLoadOlderMessages: () => void;
  onRefreshTodos: () => void;
  onToggleTools: (messageId: string) => void;
  onApproveTool: (messageId: string, call: ToolCallView) => void;
  onRejectTool: (messageId: string, call: ToolCallView) => void;
  onApprovalModeChange: (mode: ApprovalMode) => void;
  onToggleApprovedTool: (toolId: string) => void;
  onTaskDevelopmentSummaryChange?: (taskId: string) => void;
}) {
  const chatStreamRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const previousMessageFrameRef = useRef<{ firstId?: string; lastId?: string; scrollHeight: number }>();
  const stickToBottomRef = useRef(true);
  const projectOptions = useMemo(() => {
    const values = [
      localConfig?.workspaceRoot,
      ...(localConfig?.recentProjects || []),
      activeProjectPath,
    ];
    return values
      .map((value) => (value || '').trim())
      .filter((value, index, all) => value && all.indexOf(value) === index);
  }, [activeProjectPath, localConfig?.recentProjects, localConfig?.workspaceRoot]);
  const [plusOpen, setPlusOpen] = useState(false);
  const [plusMenuPage, setPlusMenuPage] = useState<'main' | 'planTemplates'>('main');
  const [reviewPanelHidden, setReviewPanelHidden] = useState(() => window.localStorage.getItem('clawagent.chat.reviewPanelHidden') === 'true');
  const [reviewPanelWidth, setReviewPanelWidth] = useState(() => {
    const saved = Number(window.localStorage.getItem('clawagent.chat.reviewPanelWidth') || 720);
    return Number.isFinite(saved) ? Math.min(1100, Math.max(420, saved)) : 720;
  });
  const [fileChanges, setFileChanges] = useState<FileChangeView[]>([]);
  const [fileChangesByTaskId, setFileChangesByTaskId] = useState<Record<string, FileChangeView[]>>({});
  const [selectedFileChangeId, setSelectedFileChangeId] = useState<string>();
  const [fileReview, setFileReview] = useState<FileReviewView>();
  const [fileReviewLoading, setFileReviewLoading] = useState(false);
  const [fileReviewError, setFileReviewError] = useState<string>();
  const [collapsedFileReviewTasks, setCollapsedFileReviewTasks] = useState<Record<string, boolean>>({});
  const [fileContextMenu, setFileContextMenu] = useState<{ change: FileChangeView; x: number; y: number }>();
  const [compactTodoLayout, setCompactTodoLayout] = useState(() => window.innerWidth <= 900);
  const emptyFileChangesLoadedAtRef = useRef<Record<string, number>>({});
  const fileChangeRequestingTaskIdsRef = useRef<Set<string>>(new Set());
  const messageTaskIds = useMemo(() => {
    const ids = messages
      .map((message) => (message.role === 'assistant' ? message.taskId : undefined))
      .filter((taskId): taskId is string => Boolean(taskId));
    return Array.from(new Set(ids));
  }, [messages]);
  const resizingReviewPanelRef = useRef(false);
  const resizingPanelRightRef = useRef(0);
  const visibleReviewPanel = Boolean(fileReview) && !reviewPanelHidden;
  const selectedFileChange = useMemo(
    () => fileChanges.find((change) => change.id === selectedFileChangeId),
    [fileChanges, selectedFileChangeId],
  );
  const slashQuery = slashCommandQuery(input);
  const slashSuggestions = useMemo(() => {
    if (slashQuery === undefined) return [];
    return SLASH_COMMANDS.filter((command) => (
      command.id.includes(slashQuery) || command.title.toLowerCase().includes(slashQuery)
    ));
  }, [slashQuery]);
  const planTemplateLabel = useMemo(() => {
    if (!selectedPlanTemplateId) return '默认计划';
    return planTemplates.find((template) => template.id === selectedPlanTemplateId)?.title || '默认计划';
  }, [planTemplates, selectedPlanTemplateId]);

  const selectPlanTemplate = useCallback((templateId: string) => {
    onPlanTemplateChange(templateId);
    // 选择模板就是开启计划模式，避免用户还要再点一次独立的计划按钮。
    onPlanModeChange(true);
    setPlusOpen(false);
    setPlusMenuPage('main');
  }, [onPlanModeChange, onPlanTemplateChange]);

  const resizeInput = useCallback((textarea?: HTMLTextAreaElement | null) => {
    if (!textarea) return;
    textarea.style.height = 'auto';
    // 输入框默认保持 1-2 行，内容较多时最多增长到约 5 行，超过后在输入框内部滚动。
    const maxHeight = 112;
    const nextHeight = Math.max(38, Math.min(textarea.scrollHeight, maxHeight));
    textarea.style.height = `${nextHeight}px`;
    textarea.style.overflowY = textarea.scrollHeight > maxHeight ? 'auto' : 'hidden';
  }, []);

  useEffect(() => {
    resizeInput(inputRef.current);
  }, [input, resizeInput]);

  useEffect(() => {
    const closePlusMenu = (event: PointerEvent) => {
      const target = event.target as Element;
      if (target.closest?.('.composer-shell')) return;
      setPlusOpen(false);
      setPlusMenuPage('main');
    };
    document.addEventListener('pointerdown', closePlusMenu, true);
    return () => document.removeEventListener('pointerdown', closePlusMenu, true);
  }, []);

  useLayoutEffect(() => {
    const stream = chatStreamRef.current;
    if (!stream) return;
    const firstId = messages[0]?.id;
    const lastId = messages[messages.length - 1]?.id;
    const previousFrame = previousMessageFrameRef.current;
    const prependedOlderMessages = Boolean(previousFrame?.firstId && firstId !== previousFrame.firstId && lastId === previousFrame.lastId);
    if (prependedOlderMessages && previousFrame) {
      // 向上分页会在列表前面插入旧消息，需要补偿 scrollTop，避免用户位置跳到底部。
      stream.scrollTop = stream.scrollHeight - previousFrame.scrollHeight;
      stickToBottomRef.current = false;
    } else if (stickToBottomRef.current || running) {
      stream.scrollTop = stream.scrollHeight;
    }
    previousMessageFrameRef.current = { firstId, lastId, scrollHeight: stream.scrollHeight };
  }, [messages, running]);

  useEffect(() => {
    const stream = chatStreamRef.current;
    if (!stream) return;
    const scrollToBottom = () => {
      if (!stickToBottomRef.current) return;
      window.requestAnimationFrame(() => {
        stream.scrollTop = stream.scrollHeight;
        previousMessageFrameRef.current = {
          firstId: messages[0]?.id,
          lastId: messages[messages.length - 1]?.id,
          scrollHeight: stream.scrollHeight,
        };
      });
    };
    // 流式 markdown、工具调用列表展开时会改变内容高度，只有贴底阅读时才继续跟随底部。
    const observer = new ResizeObserver(scrollToBottom);
    observer.observe(stream);
    Array.from(stream.children).forEach((child) => observer.observe(child));
    return () => observer.disconnect();
  }, [messages, running]);

  const handleStreamScroll = useCallback(() => {
    const stream = chatStreamRef.current;
    if (!stream) return;
    const distanceToBottom = stream.scrollHeight - stream.scrollTop - stream.clientHeight;
    stickToBottomRef.current = distanceToBottom < 80;
    if (stream.scrollTop < 32 && historyHasMore && !historyLoading) {
      onLoadOlderMessages();
    }
  }, [historyHasMore, historyLoading, onLoadOlderMessages]);

  const syncTaskFileChanges = useCallback(async (taskId: string, preferredChange?: FileChangeView) => {
    const changes = latestFileChanges(await api.taskFileChanges(taskId));
    if (changes.length > 0) {
      delete emptyFileChangesLoadedAtRef.current[taskId];
    }
    setFileChanges((current) => (sameFileChanges(current, changes) ? current : changes));
    setFileChangesByTaskId((current) => {
      if (sameFileChanges(current[taskId], changes)) return current;
      return { ...current, [taskId]: changes };
    });
    setSelectedFileChangeId((current) => {
      const preferredPath = normalizeFileChangePath(preferredChange?.path);
      const samePathLatest = preferredPath
        ? changes.find((change) => normalizeFileChangePath(change.path) === preferredPath)
        : undefined;
      if (samePathLatest) return samePathLatest.id;
      if (preferredChange?.id && changes.some((change) => change.id === preferredChange.id)) return preferredChange.id;
      if (current && changes.some((change) => change.id === current)) return current;
      // 右侧详情关闭时不自动高亮文件，避免轮询把用户刚关闭的审查态又带回来。
      return reviewPanelHidden ? undefined : changes[0]?.id;
    });
    return changes;
  }, [reviewPanelHidden]);

  const loadFileChanges = useCallback(async (taskId?: string, resetReview = false) => {
    if (!taskId) {
      setFileChanges([]);
      setSelectedFileChangeId(undefined);
      setFileReview(undefined);
      return;
    }
    try {
      if (resetReview) {
        // 任务切换只清理详情内容，不主动展开右侧面板；文件列表仍保留在对应消息下。
        setFileReview(undefined);
        setFileReviewError(undefined);
      }
      await syncTaskFileChanges(taskId);
    } catch {
      setFileChanges([]);
    }
  }, [syncTaskFileChanges]);

  const updateReviewPanelHidden = useCallback((hidden: boolean) => {
    setReviewPanelHidden(hidden);
    window.localStorage.setItem('clawagent.chat.reviewPanelHidden', String(hidden));
  }, []);

  const closeFileReviewPanel = useCallback(() => {
    // 关闭右侧详情时清理详情态，避免轮询或切换任务后留下一个不可见但仍占用布局判断的旧审查对象。
    updateReviewPanelHidden(true);
    setFileReview(undefined);
    setFileReviewError(undefined);
    setFileReviewLoading(false);
    setSelectedFileChangeId(undefined);
  }, [updateReviewPanelHidden]);

  const openFileReview = useCallback(async (change: FileChangeView) => {
    const taskId = change.taskId || lastTaskId;
    if (!taskId) return;
    setSelectedFileChangeId(change.id);
    updateReviewPanelHidden(false);
    setFileReviewLoading(true);
    setFileReviewError(undefined);
    try {
      setFileReview(await api.fileReview(taskId, change));
    } catch (err) {
      setFileReview(undefined);
      setFileReviewError(err instanceof Error ? err.message : String(err));
    } finally {
      setFileReviewLoading(false);
    }
  }, [lastTaskId, updateReviewPanelHidden]);

  const copyFilePath = useCallback(async (change: FileChangeView) => {
    await navigator.clipboard.writeText(change.path || '');
    setFileContextMenu(undefined);
  }, []);

  const copyFileContent = useCallback(async (change: FileChangeView) => {
    const taskId = change.taskId || lastTaskId;
    if (!taskId) return;
    const review = await api.fileReview(taskId, change);
    await navigator.clipboard.writeText(review.afterContent || review.beforeContent || '');
    setFileContextMenu(undefined);
  }, [lastTaskId]);

  const openFileInLocalApp = useCallback(async (change: FileChangeView, action: 'vscode' | 'explorer') => {
    const taskId = change.taskId || lastTaskId;
    if (!taskId) return;
    await api.openTaskFile(taskId, change, action);
    setFileContextMenu(undefined);
  }, [lastTaskId]);

  const rollbackFileChange = useCallback(async (change: FileChangeView) => {
    const taskId = change.taskId || lastTaskId;
    if (!taskId) return;
    setFileContextMenu(undefined);
    setFileReviewLoading(true);
    setFileReviewError(undefined);
    try {
      const review = await api.rollbackTaskFile(taskId, change);
      setFileReview(review);
      await syncTaskFileChanges(taskId, review.change);
      onTaskDevelopmentSummaryChange?.(taskId);
    } catch (err) {
      setFileReviewError(err instanceof Error ? err.message : String(err));
    } finally {
      setFileReviewLoading(false);
    }
  }, [lastTaskId, onTaskDevelopmentSummaryChange, syncTaskFileChanges]);

  const rollbackFileSelection = useCallback(async (
    change: FileChangeView,
    selection: { startLine: number; endLine: number; selectedText: string },
  ) => {
    const taskId = change.taskId || lastTaskId;
    if (!taskId) return;
    setFileReviewLoading(true);
    setFileReviewError(undefined);
    try {
      const review = await api.rollbackTaskFileSelection(taskId, change, selection);
      setFileReview(review);
      await syncTaskFileChanges(taskId, review.change);
      onTaskDevelopmentSummaryChange?.(taskId);
    } catch (err) {
      setFileReviewError(err instanceof Error ? err.message : String(err));
      throw err;
    } finally {
      setFileReviewLoading(false);
    }
  }, [lastTaskId, onTaskDevelopmentSummaryChange, syncTaskFileChanges]);

  const openFileContextMenu = useCallback((event: ReactMouseEvent, change: FileChangeView) => {
    event.preventDefault();
    setFileContextMenu({ change, x: event.clientX, y: event.clientY });
  }, []);

  useEffect(() => {
    void loadFileChanges(lastTaskId, true);
  }, [lastTaskId, loadFileChanges]);

  useEffect(() => {
    if (!lastTaskId) return;
    if (!running) {
      void loadFileChanges(lastTaskId);
      return;
    }
    // 工具调用会更新消息内容但不一定改变消息数量；运行中轮询一次文件变更，确保 write_file 后审查入口及时出现。
    const timer = window.setInterval(() => void loadFileChanges(lastTaskId), 1500);
    return () => window.clearInterval(timer);
  }, [lastTaskId, loadFileChanges, running]);

  useEffect(() => {
    const now = Date.now();
    const missingTaskIds = messageTaskIds.filter((taskId) => {
      if (fileChangeRequestingTaskIdsRef.current.has(taskId)) return false;
      if (fileChangesByTaskId[taskId] !== undefined) return false;
      const emptyLoadedAt = emptyFileChangesLoadedAtRef.current[taskId] || 0;
      // 空结果也必须节流；运行中的当前任务由专门轮询刷新，历史恢复不能因为空结果反复打接口。
      const retryAfterMs = taskId === lastTaskId && running ? 3_000 : 30_000;
      return now - emptyLoadedAt > retryAfterMs;
    });
    if (missingTaskIds.length === 0) return;
    let cancelled = false;
    missingTaskIds.forEach((taskId) => fileChangeRequestingTaskIdsRef.current.add(taskId));

    const restoreMessageFileChanges = async () => {
      try {
        const entries = await Promise.all(
          missingTaskIds.map(async (taskId) => {
            try {
              return [taskId, latestFileChanges(await api.taskFileChanges(taskId))] as const;
            } catch {
              return [taskId, [] as FileChangeView[]] as const;
            }
          }),
        );
        if (cancelled) return;
        setFileChangesByTaskId((current) => {
          let changed = false;
          const next = { ...current };
          entries.forEach(([taskId, changes]) => {
            if (changes.length > 0) {
              delete emptyFileChangesLoadedAtRef.current[taskId];
              if (!sameFileChanges(current[taskId], changes)) {
                next[taskId] = changes;
                changed = true;
              }
            } else {
              emptyFileChangesLoadedAtRef.current[taskId] = Date.now();
            }
          });
          return changed ? next : current;
        });
      } finally {
        missingTaskIds.forEach((taskId) => fileChangeRequestingTaskIdsRef.current.delete(taskId));
      }
    };

    void restoreMessageFileChanges();
    return () => {
      cancelled = true;
    };
  }, [fileChangesByTaskId, lastTaskId, messageTaskIds, running]);

  const resizeReviewPanel = useCallback((clientX: number, panelRight?: number) => {
    if (!panelRight) return;
    const workspaceBounds = chatStreamRef.current?.closest('.chat-workspace')?.getBoundingClientRect();
    const maxAllowed = workspaceBounds ? workspaceBounds.width - 420 : 1100;
    const nextWidth = Math.min(Math.min(1100, maxAllowed), Math.max(420, panelRight - clientX));
    setReviewPanelWidth(nextWidth);
    window.localStorage.setItem('clawagent.chat.reviewPanelWidth', String(Math.round(nextWidth)));
  }, []);

  const startReviewResize = useCallback((event: ReactMouseEvent<HTMLButtonElement>, panelRight: number) => {
    event.preventDefault();
    resizingReviewPanelRef.current = true;
    resizingPanelRightRef.current = panelRight;
    resizeReviewPanel(event.clientX, panelRight);
  }, [resizeReviewPanel]);

  useEffect(() => {
    const updateLayout = () => setCompactTodoLayout(window.innerWidth <= 900);
    window.addEventListener('resize', updateLayout);
    return () => window.removeEventListener('resize', updateLayout);
  }, []);

  useEffect(() => {
    const handleMouseMove = (event: MouseEvent) => {
      const panelRight = resizingPanelRightRef.current;
      if (resizingReviewPanelRef.current) {
        resizeReviewPanel(event.clientX, panelRight);
      }
    };
    const handleMouseUp = () => {
      resizingReviewPanelRef.current = false;
    };
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);
    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [resizeReviewPanel]);

  useEffect(() => {
    if (!fileContextMenu) return;
    const close = () => setFileContextMenu(undefined);
    window.addEventListener('click', close);
    window.addEventListener('resize', close);
    return () => {
      window.removeEventListener('click', close);
      window.removeEventListener('resize', close);
    };
  }, [fileContextMenu]);

  const workspaceColumns = useMemo(() => {
    if (compactTodoLayout) return undefined;
    const columns = ['minmax(420px, 1fr)'];
    if (visibleReviewPanel) columns.push(`${reviewPanelWidth}px`);
    return { gridTemplateColumns: columns.join(' ') };
  }, [compactTodoLayout, reviewPanelWidth, visibleReviewPanel]);

  return (
    <section className="chat-page">
      <div className="chat-page-head">
        <div>
          <h1>聊天</h1>
        </div>
        <div className="session-chip">
          <span className="mono">{currentSessionId || '等待会话'}</span>
          <input
            className="project-path-input"
            value={activeProjectPath}
            onChange={(event) => onActiveProjectPathChange(event.target.value)}
            placeholder="项目目录，可选"
            title="本会话默认项目目录；执行 mvn/npm/gradle/process 时优先使用"
          />
          {projectOptions.length > 0 && (
            <select
              className="project-path-select"
              value={projectOptions.includes(activeProjectPath.trim()) ? activeProjectPath.trim() : ''}
              onChange={(event) => onActiveProjectPathChange(event.target.value)}
              title="从默认工作区和最近项目中选择"
            >
              <option value="">选择项目</option>
              {projectOptions.map((projectPath) => (
                <option key={projectPath} value={projectPath}>{projectPath}</option>
              ))}
            </select>
          )}
          {fileChanges.length > 0 && (
            <button
              className={`icon-button ${visibleReviewPanel ? 'active' : ''}`}
              onClick={() => {
                if (visibleReviewPanel) {
                  closeFileReviewPanel();
                  return;
                }
                const changeToOpen = selectedFileChange || fileChanges[0];
                if (changeToOpen) void openFileReview(changeToOpen);
              }}
              title={visibleReviewPanel ? '隐藏审查窗口' : '显示审查窗口'}
              type="button"
            >
              <PanelRightOpen size={16} />
            </button>
          )}
          <button className="icon-button" onClick={onRefreshTodos} title="刷新 Todo">
            <RefreshCw size={16} />
          </button>
        </div>
      </div>

      <div
        className={`chat-workspace${visibleReviewPanel ? '' : ' no-todos'}`}
        style={workspaceColumns}
      >
        <div className="chat-main-column">
          <div className="chat-stream" ref={chatStreamRef} onScroll={handleStreamScroll}>
            {(historyHasMore || historyLoading) && (
              <div className="chat-history-loader">
                <button type="button" onClick={onLoadOlderMessages} disabled={historyLoading}>
                  {historyLoading ? '加载中...' : '加载更早消息'}
                </button>
              </div>
            )}
            {messages.map((message) => {
              const messageTodos = message.taskId
                ? todosByTaskId[message.taskId] || (message.taskId === lastTaskId ? todos.filter((todo) => todo.taskId === message.taskId) : [])
                : [];
              const messageToolCalls = message.toolCalls.length > 0
                ? message.toolCalls
                : (message.taskId ? toolCallsByTaskId[message.taskId] || [] : []);
              const resumeState = message.taskId ? resumeStateByTaskId[message.taskId] : undefined;
              return (
              <Fragment key={message.id}>
                <ChatBubble
                  message={message}
                  progressTodos={messageTodos}
                  progressToolCalls={messageToolCalls}
                  onApproveTool={onApproveTool}
                  onRejectTool={onRejectTool}
                  onToggleTools={onToggleTools}
                  onConfirmProjectDirectory={onConfirmProjectDirectory}
                  planBusy={Boolean(message.planId && planBusyId === message.planId)}
                  onRevisePlan={onRevisePlan}
                  onRunPlan={onRunPlan}
                  onCancelPlan={onCancelPlan}
                />
                {((resumeState ? resumeState.canResume : isContinuationRequiredMessage(message))) && (
                  <ResumeTaskAction
                    disabled={running}
                    todo={resumeTodoFromState(resumeState) || nextResumeTodo(messageTodos)}
                    state={resumeState}
                    onClick={() => onResumeTask(message)}
                  />
                )}
                {message.role === 'assistant' && message.taskId && (fileChangesByTaskId[message.taskId] || []).length > 0 && (
                  <FileReviewInlineCard
                    changes={fileChangesByTaskId[message.taskId]}
                    summary={summarizeFileChanges(fileChangesByTaskId[message.taskId])}
                    selectedId={selectedFileChangeId}
                    collapsed={!!collapsedFileReviewTasks[message.taskId]}
                    onOpen={openFileReview}
                    onContextMenu={openFileContextMenu}
                    onToggleCollapsed={() => setCollapsedFileReviewTasks((current) => ({
                      ...current,
                      [message.taskId as string]: !current[message.taskId as string],
                    }))}
                  />
                )}
              </Fragment>
            );})}
          </div>

          <div className="composer-shell">
            <AttachmentPreviewList attachments={attachments} onRemove={onRemoveAttachment} />
            {slashSuggestions.length > 0 && (
              <SlashCommandMenu
                commands={slashSuggestions}
                onSelect={(command) => {
                  onInputChange(`/${command.id}${command.hint ? ' ' : ''}`);
                  window.requestAnimationFrame(() => inputRef.current?.focus());
                }}
              />
            )}
            {plusOpen && plusMenuPage === 'main' && (
              <div className="tool-picker">
                <header><span>+ 添加上下文</span></header>
                <button type="button" onClick={() => attachmentInputRef.current?.click()}>
                  <FileText size={14} />
                  <span>添加文件或图片</span>
                </button>
                <button type="button" onClick={() => setPlusMenuPage('planTemplates')}>
                  <ScrollText size={14} />
                  <span>{planMode ? `计划：${planTemplateLabel}` : '计划'}</span>
                  <ChevronRight size={13} />
                </button>
              </div>
            )}
            {plusOpen && plusMenuPage === 'planTemplates' && (
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
                  <button
                    key={template.id}
                    type="button"
                    title={template.description || template.title}
                    onClick={() => selectPlanTemplate(template.id)}
                  >
                    <ScrollText size={14} />
                    <span>{template.title || template.id}</span>
                    {selectedPlanTemplateId === template.id && <Check size={13} />}
                  </button>
                ))}
                {planMode && (
                  <button type="button" onClick={() => {
                    onPlanModeChange(false);
                    setPlusOpen(false);
                    setPlusMenuPage('main');
                  }}>
                    <ScrollText size={14} />
                    <span>关闭计划</span>
                  </button>
                )}
              </div>
            )}
            <textarea
              ref={inputRef}
              rows={1}
              value={input}
              onChange={(event) => {
                const value = normalizeEscapedNewlines(event.target.value);
                onInputChange(value);
                resizeInput(event.target);
              }}
              placeholder={planMode ? '计划模式：输入需求后先生成计划，再按步骤执行' : 'Message (Enter 发送，Shift+Enter 换行，可粘贴 \\n 自动转为换行)'}
              onKeyDown={(event) => {
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault();
                  onSubmit();
                }
              }}
            />
            <div className="composer-toolbar">
              <input
                ref={attachmentInputRef}
                className="sr-only"
                type="file"
                multiple
                onChange={(event) => {
                  if (event.target.files) onAddAttachments(event.target.files);
                  event.target.value = '';
                }}
              />
              <button
                className="composer-icon-button"
                type="button"
                onClick={() => {
                  setPlusOpen((open) => !open);
                  setPlusMenuPage('main');
                }}
                title="添加上下文"
              >
                <Plus size={18} />
              </button>
              <KnowledgePicker
                documents={knowledgeDocuments}
                selectedIds={selectedKnowledgeDocumentIds}
                onToggle={onToggleKnowledgeDocument}
                onSetSelected={onSetKnowledgeDocuments}
              />
              {planMode ? (
                <button
                  className="composer-plan-toggle active"
                  type="button"
                  onClick={() => {
                    setPlusOpen(true);
                    setPlusMenuPage('planTemplates');
                  }}
                  title={`当前计划：${planTemplateLabel}`}
                >
                  <ScrollText size={14} />
                  <span>计划：{planTemplateLabel}</span>
                </button>
              ) : null}
              <ApprovalControls
                highRiskTools={highRiskTools}
                settings={approvalSettings}
                mode={approvalMode}
                onModeChange={onApprovalModeChange}
                onToggleApprovedTool={onToggleApprovedTool}
              />
              <div className="composer-spacer" />
              <button className="secondary" onClick={onNewSession}>新会话</button>
              <button
                className={running ? 'danger-button' : 'send-button'}
                onClick={onSubmit}
                disabled={!running && !input.trim() && attachments.length === 0 && selectedKnowledgeDocumentIds.length === 0}
              >
                {running ? <Square size={15} /> : <Send size={15} />}
                {running ? '停止' : '发送'}
              </button>
            </div>
          </div>
        </div>
        {visibleReviewPanel && (
          <FileReviewPanel
            review={fileReview}
            loading={fileReviewLoading}
            error={fileReviewError}
            width={reviewPanelWidth}
            onClose={closeFileReviewPanel}
            onStartResize={startReviewResize}
            onRollbackSelection={rollbackFileSelection}
          />
        )}
      </div>
      {fileContextMenu && (
        <div
          className="file-context-menu"
          style={{ left: fileContextMenu.x, top: fileContextMenu.y }}
          onClick={(event) => event.stopPropagation()}
        >
          <button type="button" onClick={() => void openFileInLocalApp(fileContextMenu.change, 'vscode')}>在 VS Code 中打开</button>
          <button type="button" onClick={() => void openFileInLocalApp(fileContextMenu.change, 'explorer')}>在资源管理器中打开</button>
          <button type="button" onClick={() => openFileReview(fileContextMenu.change)}>打开审查</button>
          {fileContextMenu.change.backupPath && (
            <button type="button" onClick={() => void rollbackFileChange(fileContextMenu.change)}>回滚到变更前</button>
          )}
          <button type="button" onClick={() => copyFilePath(fileContextMenu.change)}>复制路径</button>
          <button type="button" onClick={() => void copyFileContent(fileContextMenu.change)}>复制文件内容</button>
        </div>
      )}

    </section>
  );
}

function ResumeTaskAction({
  disabled,
  todo,
  state,
  onClick,
}: {
  disabled: boolean;
  todo?: TodoItem;
  state?: ResumeStateView;
  onClick: () => void;
}) {
  const remainingTodos = state?.remainingTodos || [];
  const checkpoint = formatCheckpointPreview(state?.checkpoint, 360);
  return (
    <div className="resume-task-action">
      <div className="resume-task-main">
        <button className="resume-task-button" type="button" disabled={disabled} onClick={onClick}>
          <RefreshCw size={14} />
          继续执行
        </button>
        <span>{todo ? `从 Todo ${todo.itemOrder || '-'}：${todo.title || '未完成任务'}` : (state?.reason || '继续剩余未完成步骤')}</span>
        {remainingTodos.length ? <small>{remainingTodos.length} 个 Todo 未完成</small> : null}
      </div>
      {(state?.resumeFromTaskId || checkpoint || remainingTodos.length > 0) && (
        <div className="resume-task-detail">
          {state?.resumeFromTaskId && <small>来源任务：{short(state.resumeFromTaskId, 18)}</small>}
          {state?.projectPath && <small title={state.projectPath}>项目目录：{short(state.projectPath, 72)}</small>}
          {state?.resumeMode && <small>恢复模式：{resumeModeText(state.resumeMode)}</small>}
          {state?.resumeInstruction && <small>{state.resumeInstruction}</small>}
          {checkpoint && <pre>{checkpoint}</pre>}
          {remainingTodos.length > 0 && (
            <ul>
              {remainingTodos.slice(0, 4).map((item) => (
                <li key={item.id}>{item.itemOrder || '-'}.[{item.status || '-'}] {item.title || '未命名 Todo'}</li>
              ))}
              {remainingTodos.length > 4 && <li>还有 {remainingTodos.length - 4} 个未展示</li>}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}

function resumeModeText(mode?: string) {
  if (mode === 'retry-failed-todo') return '重试失败 Todo';
  if (mode === 'continue-running-todo') return '继续运行中 Todo';
  if (mode === 'start-pending-todo') return '开始待处理 Todo';
  return mode || '继续执行';
}

function FileReviewInlineCard({
  changes,
  summary,
  selectedId,
  collapsed,
  onOpen,
  onContextMenu,
  onToggleCollapsed,
}: {
  changes: FileChangeView[];
  summary: { created: number; modified: number; total: number };
  selectedId?: string;
  collapsed: boolean;
  onOpen: (change: FileChangeView) => void;
  onContextMenu: (event: ReactMouseEvent, change: FileChangeView) => void;
  onToggleCollapsed: () => void;
}) {
  const [filter, setFilter] = useState<'all' | 'create' | 'modify' | 'rollback' | 'failed'>('all');
  const filterOptions: Array<{ value: typeof filter; label: string }> = [
    { value: 'all', label: '全部' },
    { value: 'create', label: '新增' },
    { value: 'modify', label: '修改' },
    { value: 'rollback', label: '回滚' },
    { value: 'failed', label: '失败' },
  ];
  const filteredChanges = filter === 'all'
    ? changes
    : changes.filter((change) => fileChangeFilterKind(change) === filter);
  const visibleChanges = filteredChanges.slice(0, 8);
  return (
    <div className="file-review-inline-card">
      <div className="file-review-inline-head">
        <div>
          <strong>文件审查</strong>
          <small>{summary.total} 个文件 · 新建 {summary.created} · 修改 {summary.modified}</small>
        </div>
        <div className="file-review-inline-actions">
          <div className="file-review-filter" aria-label="文件审查筛选">
            {filterOptions.map((option) => (
              <button
                className={filter === option.value ? 'active' : ''}
                key={option.value}
                type="button"
                onClick={() => setFilter(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>
          <button
            aria-label={collapsed ? '展开文件审查' : '收起文件审查'}
            className="icon-button compact"
            title={collapsed ? '展开文件审查' : '收起文件审查'}
            type="button"
            onClick={onToggleCollapsed}
          >
            {collapsed ? <ChevronDown size={15} /> : <ChevronUp size={15} />}
          </button>
        </div>
      </div>
      {!collapsed && (
        <div className="file-review-inline-list">
          {visibleChanges.length === 0 ? (
            <div className="file-review-inline-empty">当前筛选没有文件变更</div>
          ) : visibleChanges.map((change) => (
            <button
              className={`file-review-inline-row ${change.id === selectedId ? 'selected' : ''}`}
              key={change.id}
              onClick={() => onOpen(change)}
              onContextMenu={(event) => onContextMenu(event, change)}
              title={change.path}
              type="button"
            >
              <FileIcon size={15} />
              <span>
                <strong>{basename(change.path)} <em>{changeTypeText(change.changeType)}</em></strong>
                <small>{compactPath(change.path)}</small>
                {fileChangeSourceText(change) && <small className="file-change-source">{fileChangeSourceText(change)}</small>}
              </span>
              <em className={`file-review-state ${fileReviewStatusClass(change)}`}>{fileReviewStatusText(change)}</em>
              <b><i>+{change.addedLines || 0}</i><i>-{change.deletedLines || 0}</i></b>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function FileReviewPanel({
  review,
  loading,
  error,
  width,
  onClose,
  onStartResize,
  onRollbackSelection,
}: {
  review?: FileReviewView;
  loading: boolean;
  error?: string;
  width: number;
  onClose: () => void;
  onStartResize: (event: ReactMouseEvent<HTMLButtonElement>, panelRight: number) => void;
  onRollbackSelection: (change: FileChangeView, selection: {
    startLine: number;
    endLine: number;
    selectedText: string;
    base?: 'current' | 'before';
    insertAfterLine?: number;
  }) => Promise<void>;
}) {
  const change = review?.change;
  const language = languageFromPath(change?.path);
  const changeType = (change?.changeType || '').toLowerCase();
  const before = review?.beforeContent || '';
  const after = review?.afterContent || '';
  const diffMode = changeType === 'modify' || changeType === 'append' || changeType === 'rollback';
  const panelRef = useRef<HTMLElement>(null);
  const diffEditorRef = useRef<any>();
  const modifiedEditorRef = useRef<any>();
  const [selectionError, setSelectionError] = useState<string>();
  const handleMouseDown = useCallback((event: ReactMouseEvent<HTMLButtonElement>) => {
    const panel = panelRef.current;
    if (panel) onStartResize(event, panel.getBoundingClientRect().right);
  }, [onStartResize]);
  const rollbackSelectedLines = useCallback(async () => {
    if (!change || !modifiedEditorRef.current) return;
    const editor = modifiedEditorRef.current;
    const model = editor.getModel?.();
    const selection = editor.getSelection?.();
    if (!model || !selection) {
      setSelectionError('无法读取当前选中内容');
      return;
    }
    const startLine = Math.min(selection.startLineNumber, selection.endLineNumber);
    const endLine = Math.max(selection.startLineNumber, selection.endLineNumber);
    const selectedText = Array.from({ length: endLine - startLine + 1 }, (_, index) => model.getLineContent(startLine + index) || '').join('\n');
    setSelectionError(undefined);
    try {
      await onRollbackSelection(change, { startLine, endLine, selectedText });
    } catch (err) {
      setSelectionError(err instanceof Error ? err.message : String(err));
    }
  }, [change, onRollbackSelection]);
  const rollbackCurrentHunk = useCallback(async () => {
    if (!change || !diffEditorRef.current || !modifiedEditorRef.current) return;
    const diffEditor = diffEditorRef.current;
    const editor = modifiedEditorRef.current;
    const model = editor.getModel?.();
    const position = editor.getPosition?.();
    const lineChanges = diffEditor.getLineChanges?.() || [];
    if (!model || !position || !lineChanges.length) {
      setSelectionError('无法识别当前变更块，请先在右侧修改区域点选一行');
      return;
    }
    const currentLine = position.lineNumber;
    const currentHunk = lineChanges.find((item: any) => {
      const start = Number(item.modifiedStartLineNumber || 0);
      const end = Number(item.modifiedEndLineNumber || start);
      return start > 0 && currentLine >= start && currentLine <= Math.max(start, end);
    }) || lineChanges.find((item: any) => {
      const modifiedStart = Number(item.modifiedStartLineNumber || 0);
      const originalStart = Number(item.originalStartLineNumber || 0);
      const originalEnd = Number(item.originalEndLineNumber || originalStart);
      return modifiedStart <= 0 && originalStart > 0 && currentLine === Math.max(1, originalStart - 1)
        && originalEnd >= originalStart;
    });
    if (!currentHunk) {
      setSelectionError('当前光标不在可回滚的变更块内');
      return;
    }
    const startLine = Number(currentHunk.modifiedStartLineNumber || 0);
    const endLine = Number(currentHunk.modifiedEndLineNumber || startLine);
    if (startLine <= 0 || endLine < startLine) {
      const originalStart = Number(currentHunk.originalStartLineNumber || 0);
      const originalEnd = Number(currentHunk.originalEndLineNumber || originalStart);
      if (originalStart <= 0 || originalEnd < originalStart) {
        setSelectionError('当前变更块没有可恢复的原始行');
        return;
      }
      setSelectionError(undefined);
      try {
        await onRollbackSelection(change, {
          startLine: originalStart,
          endLine: originalEnd,
          selectedText: '',
          base: 'before',
          insertAfterLine: Math.max(0, originalStart - 1),
        });
      } catch (err) {
        setSelectionError(err instanceof Error ? err.message : String(err));
      }
      return;
    }
    const selectedText = Array.from({ length: endLine - startLine + 1 }, (_, index) => model.getLineContent(startLine + index) || '').join('\n');
    setSelectionError(undefined);
    try {
      await onRollbackSelection(change, { startLine, endLine, selectedText });
    } catch (err) {
      setSelectionError(err instanceof Error ? err.message : String(err));
    }
  }, [change, onRollbackSelection]);
  return (
    <aside className="file-review-panel" ref={panelRef} style={{ width }}>
      <button
        aria-label="调整文件审查面板宽度"
        className="review-resize-handle"
        onMouseDown={handleMouseDown}
        type="button"
      />
      <div className="file-review-head">
        <div>
          <strong>{change ? basename(change.path) : '文件审查'}</strong>
          <small>{change?.path || '选择左侧文件查看内容'}</small>
          {fileChangeSourceText(change) && <small>{fileChangeSourceText(change)}</small>}
        </div>
        <div className="file-review-actions">
          {change && <span className="file-review-count">+{change.addedLines || 0} -{change.deletedLines || 0}</span>}
          {change?.backupPath && diffMode && (
            <>
              <button className="secondary small" disabled={loading} onClick={() => void rollbackCurrentHunk()} type="button">
                回滚当前块
              </button>
              <button className="secondary small" disabled={loading} onClick={() => void rollbackSelectedLines()} type="button">
                回滚选中行
              </button>
            </>
          )}
          <button className="icon-button compact" onClick={onClose} title="关闭审查" type="button">
            <X size={15} />
          </button>
        </div>
      </div>
      {loading && <div className="empty muted">正在加载文件内容...</div>}
      {error && <div className="notice danger">{error}</div>}
      {selectionError && <div className="notice danger">{selectionError}</div>}
      {change?.backupPath && diffMode && (
        <div className="notice compact">
          删除块可把光标放在删除位置的下一行或上一行后点击“回滚当前块”；多块变更需要逐块回滚。
        </div>
      )}
      {!loading && !error && review && (
        <div className="file-review-editor">
          {diffMode ? (
            <DiffEditor
              original={before}
              modified={after}
              language={language}
              theme="vs"
              onMount={(editor) => {
                diffEditorRef.current = editor;
                modifiedEditorRef.current = editor.getModifiedEditor?.();
              }}
              options={{
                readOnly: true,
                renderSideBySide: true,
                useInlineViewWhenSpaceIsLimited: false,
                minimap: { enabled: false },
                automaticLayout: true,
                scrollBeyondLastLine: false,
                fontSize: 13,
              }}
            />
          ) : (
            <Editor
              value={changeType === 'delete' ? before : after}
              language={language}
              theme="vs"
              options={{
                readOnly: true,
                minimap: { enabled: false },
                automaticLayout: true,
                scrollBeyondLastLine: false,
                fontSize: 13,
              }}
            />
          )}
        </div>
      )}
    </aside>
  );
}

function basename(path?: string) {
  if (!path) return '-';
  const normalized = path.replace(/\\/g, '/');
  return normalized.split('/').filter(Boolean).pop() || path;
}

function compactPath(path?: string) {
  if (!path) return '-';
  const normalized = path.replace(/\\/g, '/');
  const parts = normalized.split('/').filter(Boolean);
  if (parts.length <= 4) return normalized;
  return `${parts[0]}/.../${parts.slice(-3).join('/')}`;
}

function fileChangeSourceText(change?: FileChangeView) {
  if (!change?.todoTitle && !change?.todoOrder) return '';
  const prefix = change.todoOrder ? `Todo ${change.todoOrder}` : 'Todo';
  return change.todoTitle ? `${prefix}：${change.todoTitle}` : prefix;
}

function fileChangeFilterKind(change?: FileChangeView) {
  const normalized = (change?.changeType || '').toLowerCase();
  if (normalized === 'create') return 'create';
  if (normalized === 'rollback') return 'rollback';
  if (normalized === 'failed') return 'failed';
  return 'modify';
}

function changeTypeText(type?: string) {
  const normalized = (type || '').toLowerCase();
  if (normalized === 'create') return '新建';
  if (normalized === 'append') return '追加';
  if (normalized === 'rollback') return '回滚';
  if (normalized === 'delete') return '删除';
  if (normalized === 'failed') return '失败';
  return '修改';
}

function fileReviewStatus(change?: FileChangeView) {
  const explicit = (change?.reviewStatus || '').toLowerCase();
  if (explicit) return explicit;
  const type = (change?.changeType || '').toLowerCase();
  if (type === 'failed') return 'failed';
  if (type === 'rollback') return 'rolled-back';
  return 'latest';
}

function fileReviewStatusText(change?: FileChangeView) {
  const status = fileReviewStatus(change);
  const hidden = Math.max(0, change?.supersededCount || 0);
  if (status === 'failed') return '失败';
  if (status === 'rolled-back') return hidden > 0 ? `已回滚 +${hidden}` : '已回滚';
  return hidden > 0 ? `最新 +${hidden}` : '最新';
}

function fileReviewStatusClass(change?: FileChangeView) {
  const status = fileReviewStatus(change);
  if (status === 'failed') return 'danger';
  if (status === 'rolled-back') return 'warning';
  return 'success';
}

function auditToolStatusText(status?: string) {
  if (status === 'failed') return '失败';
  if (status === 'rejected') return '已拒绝';
  if (status === 'waiting_approval') return '待审批';
  if (status === 'approved') return '已审批';
  if (status === 'completed') return '完成';
  return '运行中';
}

function auditToolPill(status?: string) {
  if (status === 'failed') return 'danger';
  if (status === 'rejected') return 'danger';
  if (status === 'waiting_approval') return 'warning';
  if (status === 'approved') return 'neutral';
  if (status === 'completed') return 'success';
  return 'neutral';
}

function verificationSourceText(source?: string) {
  if (source === 'project-config') return '项目配置';
  if (source === 'global-config') return '全局配置';
  if (source === 'auto-detect') return '自动检测';
  return source || '建议';
}

function finalOutcomeText(outcome?: string) {
  if (outcome === 'completed') return '已完成';
  if (outcome === 'failed') return '需修复';
  if (outcome === 'needs-verification') return '待验证';
  if (outcome === 'in-progress') return '进行中';
  return outcome || '未知';
}

function finalOutcomeClass(outcome?: string) {
  if (outcome === 'completed') return 'success';
  if (outcome === 'failed') return 'danger';
  if (outcome === 'needs-verification') return 'warning';
  return 'neutral';
}

function verificationStatusText(status?: string) {
  if (status === 'passed') return '已通过';
  if (status === 'failed') return '失败';
  if (status === 'pending') return '待执行';
  if (status === 'missing') return '未配置';
  return status || '未知';
}

function languageFromPath(path?: string) {
  const name = (path || '').toLowerCase();
  if (name.endsWith('.java')) return 'java';
  if (name.endsWith('.tsx') || name.endsWith('.ts')) return 'typescript';
  if (name.endsWith('.jsx') || name.endsWith('.js')) return 'javascript';
  if (name.endsWith('.json')) return 'json';
  if (name.endsWith('.yml') || name.endsWith('.yaml')) return 'yaml';
  if (name.endsWith('.xml') || name.endsWith('.pom')) return 'xml';
  if (name.endsWith('.md')) return 'markdown';
  if (name.endsWith('.css')) return 'css';
  if (name.endsWith('.html')) return 'html';
  if (name.endsWith('.properties')) return 'properties';
  return 'plaintext';
}

function AttachmentPreviewList({
  attachments,
  onRemove,
}: {
  attachments: ComposerAttachment[];
  onRemove: (attachmentId: string) => void;
}) {
  if (!attachments.length) return null;
  return (
    <div className="attachment-preview-list">
      {attachments.map((attachment) => (
        <div className="attachment-preview" key={attachment.id} title={`${attachment.name} · ${formatFileSize(attachment.size)}`}>
          {attachment.previewUrl ? (
            <img src={attachment.previewUrl} alt={attachment.name} />
          ) : (
            <div className="attachment-file-icon">
              <FileIcon size={18} />
            </div>
          )}
          <div className="attachment-meta">
            <span>{attachment.name}</span>
            <small>{formatFileSize(attachment.size)}</small>
          </div>
          <button type="button" onClick={() => onRemove(attachment.id)} title="移除附件">
            <X size={13} />
          </button>
        </div>
      ))}
    </div>
  );
}

function KnowledgePicker({
  documents,
  selectedIds,
  onToggle,
  onSetSelected,
}: {
  documents: KnowledgeDocument[];
  selectedIds: string[];
  onToggle: (documentId: string) => void;
  onSetSelected: (documentIds: string[]) => void;
}) {
  const [open, setOpen] = useState(false);
  const pickerRef = useRef<HTMLDivElement>(null);
  const allIds = documents.map((document) => document.id);
  const allSelected = allIds.length > 0 && allIds.every((id) => selectedIds.includes(id));

  useEffect(() => {
    if (!open) return undefined;
    const closeOnOutsideClick = (event: MouseEvent) => {
      if (pickerRef.current && !pickerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutsideClick);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutsideClick);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div className="knowledge-picker" ref={pickerRef}>
      <button
        aria-expanded={open}
        aria-haspopup="dialog"
        className={`composer-icon-button knowledge-picker-trigger ${selectedIds.length ? 'active' : ''}`}
        onClick={() => setOpen((current) => !current)}
        title="选择知识库文件"
        type="button"
      >
        <Database size={17} />
        {selectedIds.length > 0 && <span>{selectedIds.length}</span>}
      </button>
      {open && <div className="knowledge-picker-popover" role="dialog" aria-label="选择知识库文件">
        <div className="knowledge-picker-head">
          <strong>知识库文件</strong>
          <small>{selectedIds.length ? `已选 ${selectedIds.length}` : '未选择'}</small>
        </div>
        <div className="knowledge-picker-actions">
          <button type="button" disabled={!documents.length || allSelected} onClick={() => onSetSelected(allIds)}>
            全选
          </button>
          <button type="button" disabled={!selectedIds.length} onClick={() => onSetSelected([])}>
            清空
          </button>
        </div>
        <div className="knowledge-picker-list">
          {!documents.length ? (
            <div className="knowledge-picker-empty">暂无已入库文件</div>
          ) : documents.map((document) => {
            const selected = selectedIds.includes(document.id);
            return (
              <label className={`knowledge-picker-row ${selected ? 'selected' : ''}`} key={document.id} title={document.name || document.id}>
                <input
                  type="checkbox"
                  checked={selected}
                  onChange={() => onToggle(document.id)}
                />
                <span className="knowledge-picker-file-icon"><FileText size={15} /></span>
                <span className="knowledge-picker-file-info">
                  <strong>{document.name || document.id}</strong>
                  <small>
                    {formatFileSize(document.size || 0)} · {document.kind || 'file'} · {document.status || '-'}
                    {document.createdAt ? ` · ${formatDateTime(document.createdAt)}` : ''}
                  </small>
                </span>
                {selected && <Check size={15} />}
              </label>
            );
          })}
        </div>
      </div>}
    </div>
  );
}

function ApprovalControls({
  highRiskTools,
  settings,
  mode,
  onModeChange,
  onToggleApprovedTool,
}: {
  highRiskTools: ToolDefinition[];
  settings: ApprovalSettings;
  mode: ApprovalMode;
  onModeChange: (mode: ApprovalMode) => void;
  onToggleApprovedTool: (toolId: string) => void;
}) {
  const label = mode === 'full' ? '完全访问' : mode === 'auto' ? '替我审批' : mode === 'custom' ? '自定义' : '请求批准';
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDetailsElement>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutside = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', closeOnOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeOnOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  const selectMode = (nextMode: ApprovalMode) => {
    onModeChange(nextMode);
    if (nextMode !== 'custom') setOpen(false);
  };

  return (
    <details ref={menuRef} className="approval-menu" open={open} onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary title={label}>
        <ShieldCheck size={16} />
        <span>{label}</span>
        <ChevronDown size={15} />
      </summary>
      <div className="approval-popover">
        <button className={mode === 'ask' ? 'selected' : ''} type="button" onClick={() => selectMode('ask')}>
          <span className="approval-option-icon"><ShieldCheck size={16} /></span>
          <span>
            <strong>请求批准</strong>
            <small>高危工具需要在本次请求中显式授权。</small>
          </span>
          {mode === 'ask' && <Check size={16} />}
        </button>
        <button className={mode === 'auto' ? 'selected' : ''} type="button" onClick={() => selectMode('auto')}>
          <span className="approval-option-icon"><Bot size={16} /></span>
          <span>
            <strong>替我审批</strong>
            <small>明确高危自动批准，风险不确定时请求确认。</small>
          </span>
          {mode === 'auto' && <Check size={16} />}
        </button>
        <button className={mode === 'full' ? 'selected' : ''} type="button" onClick={() => selectMode('full')}>
          <span className="approval-option-icon"><Zap size={16} /></span>
          <span>
            <strong>完全访问权限</strong>
            <small>当前控制台最高权限模式，跳过工具审批确认。</small>
          </span>
          {mode === 'full' && <Check size={16} />}
        </button>
        <button className={mode === 'custom' ? 'selected' : ''} type="button" onClick={() => selectMode('custom')}>
          <span className="approval-option-icon"><Settings size={16} /></span>
          <span>
            <strong>自定义</strong>
            <small>只允许下方选中的高危工具，其余高危请求继续拦截。</small>
          </span>
          {mode === 'custom' && <Check size={16} />}
        </button>
        {mode === 'custom' && highRiskTools.length > 0 && (
          <div className="approval-tool-list">
            {highRiskTools.map((tool) => (
              <label className="approval-tool-chip" key={tool.id} title={tool.description || tool.id}>
                <input
                  type="checkbox"
                  checked={settings.approvedToolIds.includes(tool.id)}
                  onChange={() => onToggleApprovedTool(tool.id)}
                />
                <span>{tool.id}</span>
              </label>
            ))}
          </div>
        )}
        <small className="approval-help">授权会写入本次请求 metadata，后端仍按工具风险策略执行。</small>
      </div>
    </details>
  );
}

function AgentProgressInline({
  taskId,
  todos,
  toolCalls,
  onConfirmProjectPath,
}: {
  taskId?: string;
  todos: TodoItem[];
  toolCalls: ToolCallView[];
  onConfirmProjectPath?: (projectPath: string) => void;
}) {
  const [expandedTodoId, setExpandedTodoId] = useState<string | null>(null);
  const [expandedToolStepIds, setExpandedToolStepIds] = useState<Set<string>>(new Set());
  const sortedTodos = useMemo(
    () => [...todos].sort((a, b) => (a.itemOrder || 0) - (b.itemOrder || 0)),
    [todos],
  );
  const toolsByTodoId = useMemo(() => {
    const map = new Map<string, ToolCallView[]>();
    const ungrouped: ToolCallView[] = [];
    toolCalls.forEach((call) => {
      if (call.status === 'running' || call.status === 'completed' || call.status === 'failed') {
        const matchedTodo = call.todoId
          ? sortedTodos.find((todo) => todo.id === call.todoId)
          : sortedTodos.find((todo) => {
            const sameOrder = call.todoOrder && String(todo.itemOrder || '') === call.todoOrder;
            const sameTitle = call.todoTitle && (todo.title === call.todoTitle || todo.description === call.todoTitle);
            return Boolean(sameOrder || sameTitle);
          });
        if (matchedTodo) {
          const list = map.get(matchedTodo.id) || [];
          list.push(call);
          map.set(matchedTodo.id, list);
        } else {
          ungrouped.push(call);
        }
      }
    });
    if (ungrouped.length) map.set('__ungrouped__', ungrouped);
    return map;
  }, [sortedTodos, toolCalls]);
  const completedCount = sortedTodos.filter((todo) => todo.status === 'completed').length;
  const toggleToolExpanded = useCallback((stepId: string) => {
    setExpandedToolStepIds((current) => {
      const next = new Set(current);
      if (next.has(stepId)) next.delete(stepId); else next.add(stepId);
      return next;
    });
  }, []);
  if (!sortedTodos.length) return null;
  return (
    <div className="agent-progress">
      <div className="agent-progress-head">
        <span className="agent-progress-label">进度</span>
        <span className="agent-progress-count">{completedCount}/{sortedTodos.length} 已完成</span>
      </div>
      <ol className="agent-progress-list">
        {sortedTodos.map((todo) => {
          const todoKey = todo.id;
          const isExpanded = expandedTodoId === todoKey;
          const todoTools = toolsByTodoId.get(todoKey) || [];
          return (
            <li key={todo.id} className={`agent-progress-item status-${(todo.status || 'pending').toLowerCase()}`}>
              <button
                className="agent-progress-row"
                type="button"
                onClick={() => setExpandedTodoId(isExpanded ? null : todoKey)}
              >
                <TodoProgressDot status={todo.status} />
                <span className="agent-progress-order">{todo.itemOrder || '-'}</span>
                <span className="agent-progress-title">{todo.title || todo.description || todo.id}</span>
                {todoTools.length > 0 && (
                  <span className="agent-progress-tool-count">{todoTools.length} 条命令</span>
                )}
                {isExpanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              </button>
              {isExpanded && (
                <div className="agent-progress-tools">
                  {todoTools.length > 0 ? todoTools.map((call, index) => (
                    <ToolCallBlock
                      call={call}
                      taskId={taskId}
                      key={`${call.stepId || call.toolId}-${index}`}
                      expanded={expandedToolStepIds.has(call.stepId || '')}
                      onToggle={() => toggleToolExpanded(call.stepId || '')}
                      onConfirmProjectPath={onConfirmProjectPath}
                    />
                  )) : (
                    <div className="agent-progress-empty">暂无工具调用记录</div>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ol>
      {toolsByTodoId.get('__ungrouped__') && (
        <details className="agent-progress-ungrouped">
          <summary>未归类命令 ({toolsByTodoId.get('__ungrouped__')!.length})</summary>
          <div className="agent-progress-tools">
            {toolsByTodoId.get('__ungrouped__')!.map((call, index) => (
              <ToolCallBlock
                call={call}
                taskId={taskId}
                key={`${call.stepId || call.toolId}-${index}`}
                expanded={expandedToolStepIds.has(call.stepId || '')}
                onToggle={() => toggleToolExpanded(call.stepId || '')}
                onConfirmProjectPath={onConfirmProjectPath}
              />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

function TodoProgressDot({ status }: { status?: string }) {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'completed') return <span className="progress-dot completed" title="已完成" />;
  if (normalized === 'running') return <span className="progress-dot running" title="执行中" />;
  if (normalized === 'failed') return <span className="progress-dot failed" title="失败" />;
  return <span className="progress-dot pending" title="未执行" />;
}

function toolCallCategory(toolId: string): string {
  if (!toolId) return 'System';
  if (toolId.includes('execute') || toolId.includes('process')) return 'Shell';
  if (toolId.includes('filesystem') || toolId.includes('file')) return 'File';
  if (toolId.includes('todo')) return 'Todo';
  return 'System';
}

function pathBasename(path?: string) {
  if (!path) return '';
  const parts = path.split(/[\\/]/);
  return parts[parts.length - 1] || path;
}

function toolCallRawOutput(call: ToolCallView) {
  return call.output || call.outputPreview || '';
}

function toolCallOutputPath(call: ToolCallView) {
  const output = toolCallRawOutput(call);
  const pathMatch = output.match(/(?:^|\n)path:\s*([^\n]+)/);
  if (pathMatch) return pathMatch[1].split(/\s+(?:content|created|modified|size|type|readable|writable):/)[0].trim();
  const commandPathMatch = output.match(/\s([A-Z]:\\[^\s]+)/i);
  return commandPathMatch ? commandPathMatch[1].trim() : '';
}

function toolCallActionLabel(call: ToolCallView) {
  const toolId = call.toolId || '';
  const fileName = pathBasename(toolCallOutputPath(call));
  if (toolId.includes('read_text_file')) return fileName ? `读取 ${fileName}` : '读取文件';
  if (toolId.includes('write_file')) return fileName ? `写入 ${fileName}` : '写入文件';
  if (toolId.includes('list_directory')) return fileName ? `查看目录 ${fileName}` : '查看目录';
  if (toolId.includes('search_files')) return '搜索文件';
  if (toolId.includes('execute') || toolId.includes('process')) {
    const command = toolCallRawOutput(call).match(/^command:\s*(.+)$/m)?.[1]?.trim();
    return command ? `运行 ${command}` : '运行命令';
  }
  if (toolId.includes('todo')) return call.message || '更新 Todo';
  return call.message || toolId || '执行工具';
}

function toolCallShellLine(call: ToolCallView): string {
  const output = toolCallRawOutput(call);
  const lines = output.split('\n');
  const firstLine = lines[0] || '';
  if (/^command:/.test(firstLine)) {
    const cmdMatch = firstLine.match(/^command:\s*(.+)$/);
    const cwdLine = lines.find((line) => /^cwd:/.test(line));
    const cmd = cmdMatch ? cmdMatch[1].trim() : call.toolId || '';
    if (cwdLine) {
      const cwdMatch = cwdLine.match(/^cwd:\s*(.+)$/);
      return `cd ${cwdMatch ? cwdMatch[1].trim() : ''}\n$ ${cmd}`;
    }
    return `$ ${cmd}`;
  }
  return call.toolId || 'unknown';
}

function projectConfirmationInfo(call: ToolCallView) {
  const text = [toolCallRawOutput(call), call.error || ''].join('\n');
  if (!/requiresProjectConfirmation:\s*true/.test(text)) return undefined;
  const reason = text.match(/^reasonCode:\s*(.+)$/m)?.[1]?.trim() || 'project-directory';
  const requestedCwd = text.match(/^requestedCwd:\s*(.*)$/m)?.[1]?.trim() || '';
  const message = text.match(/^message:\s*(.+)$/m)?.[1]?.trim() || '需要先确认项目目录。';
  const candidates: string[] = [];
  const lines = text.split('\n');
  const start = lines.findIndex((line) => /^candidateProjects:/.test(line));
  if (start >= 0) {
    for (const line of lines.slice(start + 1)) {
      if (/^nextAction:/.test(line)) break;
      const value = line.replace(/^-\s*/, '').trim();
      if (value) candidates.push(value);
    }
  }
  return { reason, requestedCwd, message, candidates };
}

function toolCallOutputBody(call: ToolCallView): string {
  const output = toolCallRawOutput(call);
  const lines = output.split('\n');
  const bodyStart = lines.findIndex((line) => /^stdout:/.test(line));
  if (bodyStart >= 0) {
    const stderrStart = lines.findIndex((line, idx) => idx > bodyStart && /^stderr:/.test(line));
    const stdoutLines = stderrStart > bodyStart
      ? lines.slice(bodyStart + 1, stderrStart)
      : lines.slice(bodyStart + 1);
    const stderrLines = stderrStart >= 0 ? lines.slice(stderrStart + 1) : [];
    const parts: string[] = [];
    const stdout = stdoutLines.join('\n').trim();
    const stderr = stderrLines.join('\n').trim();
    if (stdout) parts.push(stdout);
    if (stderr) parts.push(`[stderr]\n${stderr}`);
    return parts.join('\n') || '(无输出)';
  }
  const contentIndex = lines.findIndex((line) => /^content:\s*/.test(line));
  if (contentIndex >= 0) {
    const firstContentLine = lines[contentIndex].replace(/^content:\s*/, '');
    const contentLines = [firstContentLine, ...lines.slice(contentIndex + 1)]
      .filter((line) => !/^(path|size|created|modified|readable|writable):\s*/.test(line));
    return contentLines.join('\n').trim() || '(无输出)';
  }
  if (call.error) return call.error;
  return output
    .split('\n')
    .filter((line) => !/^(command|cwd|riskLevel|riskCategory|approvalRequired|riskReason|exitCode|elapsedMs|path|toolId):\s*/.test(line))
    .join('\n')
    .trim() || call.error || '(无输出)';
}

function ToolCallBlock({
  call,
  taskId,
  expanded,
  onToggle,
  onConfirmProjectPath,
}: {
  call: ToolCallView;
  taskId?: string;
  expanded: boolean;
  onToggle: () => void;
  onConfirmProjectPath?: (projectPath: string) => void;
}) {
  const [detailCall, setDetailCall] = useState<ToolCallView | undefined>();
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const visibleCall = detailCall ? { ...call, ...detailCall } : call;

  useEffect(() => {
    if (!expanded || !taskId || !call.stepId || detailCall || detailLoading) return;
    setDetailLoading(true);
    setDetailError('');
    void api.taskEvents(taskId, 1, undefined, call.stepId)
      .then((events) => {
        const detailed = events.map(toolCallFromEvent).find((item): item is ToolCallView => Boolean(item));
        if (detailed) setDetailCall(detailed);
      })
      .catch((error) => setDetailError(error instanceof Error ? error.message : String(error)))
      .finally(() => setDetailLoading(false));
  }, [call.stepId, detailCall, detailLoading, expanded, taskId]);

  const category = toolCallCategory(visibleCall.toolId || '');
  const shellLine = toolCallShellLine(visibleCall);
  const body = toolCallOutputBody(visibleCall);
  const projectConfirmation = projectConfirmationInfo(visibleCall);
  const copyText = `${shellLine}\n\n${body}`;
  const statusLabel = visibleCall.status === 'completed' ? '✓' : visibleCall.status === 'failed' ? '×' : '◌';
  const statusClass = visibleCall.status === 'completed' ? 'success' : visibleCall.status === 'failed' ? 'failure' : 'running';
  const actionLabel = toolCallActionLabel(visibleCall);
  const elapsed = visibleCall.elapsedMs != null
    ? visibleCall.elapsedMs >= 1000 ? `${(visibleCall.elapsedMs / 1000).toFixed(1)}s` : `${visibleCall.elapsedMs}ms`
    : '';
  return (
    <div className={`tool-block ${statusClass}`}>
      <button className="tool-block-head" type="button" onClick={onToggle}>
        <span className="tool-block-status">{statusLabel}</span>
        <span className="tool-block-title">{actionLabel}</span>
        {elapsed && <span className="tool-block-elapsed">{elapsed}</span>}
        <span className="tool-block-spacer" />
        {expanded ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
      </button>
      {expanded && (
        <div className="tool-block-body">
          {projectConfirmation && (
            <div className="project-confirmation-card">
              <strong>需要确认项目目录</strong>
              <span>{projectConfirmation.message}</span>
              {projectConfirmation.requestedCwd && <code>{projectConfirmation.requestedCwd}</code>}
              {projectConfirmation.candidates.length > 0 && (
                <div className="project-confirmation-options">
                  {projectConfirmation.candidates.map((candidate) => (
                    <button
                      className="project-confirmation-option"
                      key={candidate}
                      type="button"
                      onClick={(event) => {
                        event.stopPropagation();
                        onConfirmProjectPath?.(candidate);
                      }}
                    >
                      <code>{candidate}</code>
                      <span>使用并继续</span>
                    </button>
                  ))}
                </div>
              )}
              <small>请选择正确项目目录后继续执行，后续工具调用会把该目录作为 cwd。</small>
            </div>
          )}
          <div className="tool-block-code-wrap">
            <div className="tool-block-code-label">{category}</div>
            <pre className="tool-block-code">
              <code className="tool-block-command">{shellLine}</code>
              <code className="tool-block-output">{`\n\n${body}`}</code>
            </pre>
            <div className={`tool-block-result ${statusClass}`}>
              {visibleCall.status === 'completed' ? '✓ 成功' : visibleCall.status === 'failed' ? '退出码 1' : '运行中'}
            </div>
            <button
              className="tool-block-copy"
              type="button"
              title="复制命令和结果"
              onClick={(event) => {
                event.stopPropagation();
                void navigator.clipboard.writeText(copyText);
              }}
            >
              <Copy size={14} />
            </button>
          </div>
          <div className="tool-block-meta">
            <span className="tool-block-chip">{category}</span>
            {visibleCall.riskLevel && <span className={`tool-block-chip risk-${visibleCall.riskLevel}`}>风险 {visibleCall.riskLevel}</span>}
            {visibleCall.toolId && <span className="tool-block-chip">{visibleCall.toolId}</span>}
            {detailLoading && <span className="tool-block-chip">加载完整输出...</span>}
            {detailError && <span className="tool-block-chip risk-medium" title={detailError}>完整输出加载失败</span>}
            {visibleCall.output && visibleCall.outputLength != null && visibleCall.outputLength > (visibleCall.outputPreview || '').length && (
              <span className="tool-block-chip">完整输出</span>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function ChatBubble({
  message,
  progressTodos = [],
  progressToolCalls,
  onApproveTool,
  onRejectTool,
  onToggleTools,
  onConfirmProjectDirectory,
  planBusy,
  onRevisePlan,
  onRunPlan,
  onCancelPlan,
}: {
  message: ChatMessage;
  progressTodos?: TodoItem[];
  progressToolCalls?: ToolCallView[];
  onApproveTool: (messageId: string, call: ToolCallView) => void;
  onRejectTool: (messageId: string, call: ToolCallView) => void;
  onToggleTools: (messageId: string) => void;
  onConfirmProjectDirectory: (message: ChatMessage, projectPath: string) => void;
  planBusy?: boolean;
  onRevisePlan?: (planId: string, feedback: string) => void;
  onRunPlan?: (planId: string) => void;
  onCancelPlan?: (planId: string) => void;
}) {
  const displayContent = message.role === 'user' ? stripAttachmentParseBlock(message.content) : message.content;
  const isUser = message.role === 'user';
  const blockedTool = message.role === 'assistant' ? approvalBlockedTool(message) : undefined;
  const visibleToolCalls = progressToolCalls || message.toolCalls;
  const hasInlineProgress = message.role === 'assistant' && progressTodos.length > 0;
  const hasToolTrace = message.role === 'assistant' && visibleToolCalls.length > 0;
  const hasMessageContent = displayContent.trim().length > 0;
  const showMessageCard = isUser || hasMessageContent || Boolean(message.plan) || Boolean(blockedTool?.toolId) || Boolean(message.tokenUsage);
  return (
    <article className={`message-row ${message.role}`}>
      <div className={`avatar ${isUser ? 'user-avatar' : 'assistant-avatar'}`}>
        <img src={isUser ? USER_AVATAR_URL : AGENT_AVATAR_URL} alt={isUser ? 'User' : 'Assistant'} />
      </div>
      <div className="message-main">
        <div className="message-meta">
          <span>{message.role === 'assistant' ? 'Assistant' : 'User'}</span>
          {message.status && <span>{message.status}</span>}
          <span>{formatClock(message.finishedAt || message.createdAt)}</span>
          {message.durationMs != null && <span>耗时 {formatDuration(message.durationMs)}</span>}
          {hasToolTrace && (
            <button className="message-meta-action" type="button" onClick={() => onToggleTools(message.id)}>
              已运行 {visibleToolCalls.length} 条命令 {message.toolsCollapsed ? '›' : '⌄'}
            </button>
          )}
        </div>
        {hasToolTrace && !message.toolsCollapsed && (
          <div className="message-tool-trace">
            {hasInlineProgress ? (
              <AgentProgressInline
                taskId={message.taskId}
                todos={progressTodos}
                toolCalls={visibleToolCalls}
                onConfirmProjectPath={(projectPath) => onConfirmProjectDirectory(message, projectPath)}
              />
            ) : (
              <ToolCallGroup
                taskId={message.taskId}
                calls={visibleToolCalls}
                onConfirmProjectPath={(projectPath) => onConfirmProjectDirectory(message, projectPath)}
              />
            )}
          </div>
        )}
        {showMessageCard && (
          <div className="message-card">
            {hasMessageContent && <div className="markdown" dangerouslySetInnerHTML={{ __html: renderMarkdown(displayContent) }} />}
            {message.plan && (
              <PlanCard
                plan={message.plan}
                busy={planBusy}
                onRevise={onRevisePlan}
                onRun={onRunPlan}
                onCancel={onCancelPlan}
              />
            )}
            {message.role === 'assistant' && (
              <>
                {blockedTool?.toolId && (
                  <div className="approval-request-card">
                    <div>
                      <strong>请求批准</strong>
                      <span>高危工具需要你确认后才能继续执行。</span>
                    </div>
                    <div className="approval-request-actions">
                      <button type="button" onClick={() => onApproveTool(message.id, blockedTool)}>
                        批准并继续
                      </button>
                      <button className="secondary" type="button" onClick={() => onRejectTool(message.id, blockedTool)}>
                        拒绝
                      </button>
                    </div>
                  </div>
                )}
                {message.tokenUsage && (
                  <TokenInline usage={message.tokenUsage} />
                )}
              </>
            )}
          </div>
        )}
        <ChatAttachmentList attachments={message.attachments} />
      </div>
    </article>
  );
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
  onRevise?: (planId: string, feedback: string) => void;
  onRun?: (planId: string) => void;
  onCancel?: (planId: string) => void;
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
      <div className="plan-card-head">
        <div>
          <div className="plan-card-title">
            <ScrollText size={16} />
            <span>任务计划</span>
            <span className={`plan-status status-${status.toLowerCase()}`}>{planStatusText(plan.status)}</span>
          </div>
          {plan.summary && <p>{plan.summary}</p>}
        </div>
        <span className="plan-version">v{plan.version || 1}</span>
      </div>
      {plan.goal ? (
        <div className="plan-section">
          <span className="plan-section-title">目标</span>
          <p>{plan.goal}</p>
        </div>
      ) : null}
      <div className="plan-execution-strip">
        <span>状态：{planStatusText(plan.status)}</span>
        {plan.outcome ? <span>结果：{plan.outcome}</span> : null}
        {plan.blockReason ? <span>阻塞：{plan.blockReason}</span> : null}
        {status === 'APPROVED' || status === 'DRAFT' ? <strong>已自动进入执行队列</strong> : null}
        {status === 'BLOCKED' ? <strong>可修订计划后继续执行</strong> : null}
      </div>
      <ol className="plan-step-list">
        {items.map((item) => (
          <PlanStepItem item={item} key={item.id || item.itemOrder || item.title} />
        ))}
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
      {plan.revisions?.length ? (
        <details className="plan-revisions">
          <summary>修订记录 {plan.revisions.length}</summary>
          {plan.revisions.map((revision, index) => (
            <p key={`${revision}-${index}`}>{revision}</p>
          ))}
        </details>
      ) : null}
      {editable && (
        <div className="plan-revise-row">
          <input
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
            placeholder="输入修改意见，例如：先补测试，再改实现"
          />
          <button
            type="button"
            className="secondary"
            disabled={busy || !feedback.trim()}
            onClick={() => {
              onRevise?.(plan.id, feedback);
              setFeedback('');
            }}
          >
            修订
          </button>
        </div>
      )}
      <div className="plan-actions">
        {runnable && (
          <button type="button" disabled={busy} onClick={() => onRun?.(plan.id)}>
            {busy ? '处理中...' : '继续执行'}
          </button>
        )}
        {editable && (
          <button type="button" className="ghost-button" disabled={busy} onClick={() => onCancel?.(plan.id)}>
            取消
          </button>
        )}
      </div>
    </section>
  );
}

function PlanStepItem({ item }: { item: PlanItem }) {
  const detail = item.detail || item.description;
  return (
    <li className="plan-step-item">
      <span className="plan-step-dot" />
      <div className="plan-step-body">
        <div className="plan-step-title">
          <strong>{item.itemOrder || '-'}. {item.title || '未命名步骤'}</strong>
          {item.requiresApproval && <span className="plan-step-chip approval">需审批</span>}
          {item.riskLevel && <span className={`plan-step-chip risk-${item.riskLevel}`}>风险：{item.riskLevel}</span>}
        </div>
        {detail && <p>{detail}</p>}
        {item.expectedTools?.length ? (
          <div className="plan-tool-row">
            <span>预计工具</span>
            {item.expectedTools.slice(0, 8).map((tool) => <code key={tool}>{tool}</code>)}
          </div>
        ) : null}
      </div>
    </li>
  );
}

function ToolCallGroup({
  taskId,
  calls,
  onConfirmProjectPath,
}: {
  taskId?: string;
  calls: ToolCallView[];
  onConfirmProjectPath?: (projectPath: string) => void;
}) {
  const [expandedStepIds, setExpandedStepIds] = useState<Set<string>>(new Set());
  const toggle = useCallback((stepId: string) => {
    setExpandedStepIds((current) => {
      const next = new Set(current);
      if (next.has(stepId)) next.delete(stepId); else next.add(stepId);
      return next;
    });
  }, []);
  return (
    <div className="tool-call-list">
      {calls.map((call, index) => (
        <ToolCallBlock
          call={call}
          taskId={taskId}
          key={`${call.stepId || call.toolId}-${index}`}
          expanded={expandedStepIds.has(call.stepId || '')}
          onToggle={() => toggle(call.stepId || '')}
          onConfirmProjectPath={onConfirmProjectPath}
        />
      ))}
    </div>
  );
}

function TokenInline({ usage }: { usage: TokenUsageSummary }) {
  return (
    <div className="token-inline">
      <span>LLM {formatTokenCount(usage.callCount)} 次</span>
      <span>Prompt {formatTokenCount(usage.promptTokens)}</span>
      <span>Completion {formatTokenCount(usage.completionTokens)}</span>
      <strong>Total {formatTokenCount(usage.totalTokens)}</strong>
    </div>
  );
}

function ChatAttachmentList({ attachments }: { attachments?: ChatAttachment[] }) {
  if (!attachments?.length) return null;
  return (
    <div className="chat-attachment-list">
      {attachments.map((attachment) => {
        const image = isImageAttachment(attachment);
        const href = image ? (attachment.viewUrl || attachment.previewUrl) : (attachment.downloadUrl || attachment.viewUrl);
        const content = (
          <>
            {image && attachment.previewUrl ? (
              <span className="chat-attachment-thumb">
                <img src={attachment.previewUrl} alt={attachment.name} />
              </span>
            ) : (
              <span className="chat-attachment-file">
                <FileIcon size={18} />
              </span>
            )}
            <span className="chat-attachment-info">
              <span>{formatAttachmentName(attachment.name)}</span>
              <small>{formatFileSize(attachment.size || 0)}{attachment.knowledgeDocumentId ? ' · 已入库' : ''}</small>
            </span>
          </>
        );
        if (!href) {
          return (
            <span className="chat-attachment-card" key={attachment.id} title={attachment.name}>
              {content}
            </span>
          );
        }
        return (
          <a
            className="chat-attachment-card"
            download={!image}
            href={href}
            key={attachment.id}
            rel={image ? 'noreferrer' : undefined}
            target={image ? '_blank' : undefined}
            title={attachment.name}
          >
            {content}
          </a>
        );
      })}
    </div>
  );
}

function TodoList({ todos, lastTaskId }: { todos: TodoItem[]; lastTaskId?: string }) {
  if (!todos.length) return <div className="empty muted">当前会话暂无 Todo。</div>;
  const latestTaskTodos = lastTaskId ? todos.filter((todo) => todo.taskId === lastTaskId) : [];
  const displayTodos = latestTaskTodos.length ? latestTaskTodos : todos;
  return (
    <div className="todo-list">
      {displayTodos
        .slice()
        .sort((a, b) => (a.itemOrder || 0) - (b.itemOrder || 0))
        .map((todo) => (
          <div className="todo-card" key={todo.id} title={`${todo.title || ''}\n${todo.description || ''}`}>
            <div className="todo-card-main">
              <TodoStatusIcon status={todo.status} />
              <span className="todo-order">{todo.itemOrder || '-'}</span>
              <strong>{todo.title || todo.description || todo.id}</strong>
              <em className={`todo-status-badge status-${(todo.status || 'pending').toLowerCase()}`}>{todoStatusText(todo.status)}</em>
            </div>
            {todo.description && todo.description !== todo.title && <small>{todo.description}</small>}
          </div>
        ))}
    </div>
  );
}

function TodoStatusIcon({ status }: { status?: string }) {
  const normalized = (status || '').toLowerCase();
  if (normalized === 'completed') return <CheckCircle className="todo-success" size={15} />;
  if (normalized === 'running') return <Circle className="todo-running" size={15} fill="currentColor" />;
  if (normalized === 'failed') return <XCircle className="todo-failed" size={15} />;
  return <Circle className="todo-pending" size={15} fill="currentColor" />;
}

function StatusPill({ label, value }: { label: string; value: string }) {
  return (
    <span className="status-pill">
      <i />
      {label} <strong>{value}</strong>
    </span>
  );
}

function PageHeader({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <header className="page-header">
      <h1>{title}</h1>
      <p>{subtitle}</p>
    </header>
  );
}

function Metric({ title, value, desc, onClick }: { title: string; value: string | number; desc: string; onClick?: () => void }) {
  const content = (
    <>
      <span>{title}</span>
      <strong>{value}</strong>
      <small>{desc}</small>
    </>
  );
  if (onClick) {
    return (
      <button type="button" className="metric metric-clickable" onClick={onClick}>
        {content}
      </button>
    );
  }
  return (
    <div className="metric">
      {content}
    </div>
  );
}

function Panel({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function UnavailablePanel({ title, text }: { title: string; text: string }) {
  return (
    <section className="panel unavailable-panel">
      <div className="panel-head">
        <h2>{title}</h2>
        <span className="pill neutral">待接入</span>
      </div>
      <div className="unavailable-body">
        <Clock size={22} />
        <p>{text}</p>
      </div>
    </section>
  );
}

function Empty({ text }: { text: string }) {
  return <div className="empty">{text}</div>;
}

function JsonBlock({ data }: { data: unknown }) {
  return <pre className="json-block">{JSON.stringify(data, null, 2)}</pre>;
}

function Modal({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <div className="modal-shell" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

function AuditEventPage({
  filter,
  events,
  loading,
  message,
  selectedEvent,
  onFilterChange,
  onQuery,
  onReset,
  onSelect,
  onCloseDetail,
}: {
  filter: AuditEventFilter;
  events: AgentEvent[];
  loading: boolean;
  message?: string;
  selectedEvent?: AgentEvent;
  onFilterChange: (filter: AuditEventFilter) => void;
  onQuery: () => void;
  onReset: () => void;
  onSelect: (event: AgentEvent) => void;
  onCloseDetail: () => void;
}) {
  const update = (patch: Partial<AuditEventFilter>) => onFilterChange({ ...filter, ...patch });
  return (
    <section className="stack">
      <Panel
        title="审计事件"
        action={<button onClick={onQuery} disabled={loading}>{loading ? '查询中...' : '刷新'}</button>}
      >
        <div className="log-filter">
          <label>
            <span>开始日期</span>
            <input type="date" value={filter.from} onChange={(event) => update({ from: event.target.value })} />
          </label>
          <label>
            <span>结束日期</span>
            <input type="date" value={filter.to} onChange={(event) => update({ to: event.target.value })} />
          </label>
          <label>
            <span>级别</span>
            <select value={filter.level} onChange={(event) => update({ level: event.target.value })}>
              <option value="">全部</option>
              <option value="DEBUG">DEBUG</option>
              <option value="INFO">INFO</option>
              <option value="WARN">WARN</option>
              <option value="ERROR">ERROR</option>
            </select>
          </label>
          <label>
            <span>类型</span>
            <input value={filter.type} onChange={(event) => update({ type: event.target.value })} placeholder="tool / task / security" />
          </label>
          <label>
            <span>Session ID</span>
            <input value={filter.sessionId} onChange={(event) => update({ sessionId: event.target.value })} />
          </label>
          <label>
            <span>Task ID</span>
            <input value={filter.taskId} onChange={(event) => update({ taskId: event.target.value })} />
          </label>
          <label>
            <span>用户</span>
            <input value={filter.userId} onChange={(event) => update({ userId: event.target.value })} placeholder="本地用户或外部用户" />
          </label>
          <label>
            <span>通道</span>
            <input value={filter.channelId} onChange={(event) => update({ channelId: event.target.value })} placeholder="channelId" />
          </label>
          <label>
            <span>工具</span>
            <input value={filter.toolId} onChange={(event) => update({ toolId: event.target.value })} placeholder="builtin.execute.command" />
          </label>
          <label>
            <span>风险</span>
            <select value={filter.riskLevel} onChange={(event) => update({ riskLevel: event.target.value })}>
              <option value="">全部</option>
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
            </select>
          </label>
          <label>
            <span>详情 Key</span>
            <input value={filter.detailKey} onChange={(event) => update({ detailKey: event.target.value })} placeholder="metadata key" />
          </label>
          <label>
            <span>详情值</span>
            <input value={filter.detailValue} onChange={(event) => update({ detailValue: event.target.value })} placeholder="metadata value" />
          </label>
          <label>
            <span>关键词</span>
            <input value={filter.q} onChange={(event) => update({ q: event.target.value })} placeholder="说明或详情内容" />
          </label>
          <label>
            <span>数量</span>
            <input type="number" min={1} max={500} value={filter.limit} onChange={(event) => update({ limit: Number(event.target.value || 100) })} />
          </label>
          <div className="log-filter-actions">
            <button onClick={onQuery} disabled={loading}>{loading ? '查询中...' : '查询'}</button>
            <button className="secondary" onClick={onReset} disabled={loading}>重置</button>
          </div>
        </div>
        {message && <div className="automation-message">{message}</div>}
      </Panel>

      <Panel title="事件列表">
        {!events.length ? <Empty text="暂无审计事件。" /> : (
          <Table>
            <thead>
              <tr>
                <th>时间</th>
                <th>级别</th>
                <th>类型</th>
                <th>任务</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id} className={selectedEvent?.id === event.id ? 'selected-row' : undefined} onClick={() => onSelect(event)}>
                  <td>{formatDateTime(event.createdAt)}</td>
                  <td><span className={`pill ${event.level === 'ERROR' ? 'danger' : event.level === 'WARN' ? 'warning' : 'neutral'}`}>{event.level || '-'}</span></td>
                  <td><span className="mono">{event.type || '-'}</span></td>
                  <td><span className="mono">{short(event.taskId, 16)}</span></td>
                  <td>{short(event.message, 100)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>

      {selectedEvent && (
        <Panel title="事件详情" action={<button onClick={onCloseDetail}>关闭</button>}>
          <div className="session-summary">
            <span>Event ID：<span className="mono">{selectedEvent.id}</span></span>
            <span>Session：<span className="mono">{selectedEvent.sessionId || '-'}</span></span>
            <span>Task：<span className="mono">{selectedEvent.taskId || '-'}</span></span>
            <span>时间：{formatDateTime(selectedEvent.createdAt)}</span>
          </div>
          <JsonBlock data={selectedEvent.details || {}} />
        </Panel>
      )}
    </section>
  );
}

function SystemLogPage({
  filter,
  logs,
  sources,
  loading,
  message,
  selectedLog,
  onFilterChange,
  onQuery,
  onReset,
  onSelect,
  onCloseDetail,
}: {
  filter: SystemLogFilter;
  logs: SystemLogLine[];
  sources: SystemLogSource[];
  loading: boolean;
  message?: string;
  selectedLog?: SystemLogLine;
  onFilterChange: (filter: SystemLogFilter) => void;
  onQuery: () => void;
  onReset: () => void;
  onSelect: (line: SystemLogLine) => void;
  onCloseDetail: () => void;
}) {
  const [filterCollapsed, setFilterCollapsed] = useState(false);
  const update = (patch: Partial<SystemLogFilter>) => onFilterChange({ ...filter, ...patch });
  const compressedCount = sources.filter((source) => source.compressed).length;
  return (
    <section className="stack">
      <Panel
        title="日志"
        action={(
          <div className="panel-actions">
            <button onClick={() => setFilterCollapsed((current) => !current)}>
              {filterCollapsed ? '展开筛选' : '收起筛选'}
            </button>
            <button onClick={onQuery} disabled={loading}>{loading ? '查询中...' : '刷新'}</button>
          </div>
        )}
      >
        {!filterCollapsed && (
          <div className="log-filter">
            <label>
              <span>开始日期</span>
              <input type="date" value={filter.from} onChange={(event) => update({ from: event.target.value })} />
            </label>
            <label>
              <span>结束日期</span>
              <input type="date" value={filter.to} onChange={(event) => update({ to: event.target.value })} />
            </label>
            <label>
              <span>级别</span>
              <select value={filter.level} onChange={(event) => update({ level: event.target.value })}>
                <option value="">全部</option>
                <option value="ERROR">ERROR</option>
                <option value="WARN">WARN</option>
                <option value="INFO">INFO</option>
                <option value="DEBUG">DEBUG</option>
              </select>
            </label>
            <label>
              <span>Limit</span>
              <input
                type="number"
                min={1}
                max={1000}
                value={filter.limit}
                onChange={(event) => update({ limit: Number(event.target.value || 200) })}
              />
            </label>
            <label>
              <span>用户</span>
              <input value={filter.userId} onChange={(event) => update({ userId: event.target.value })} placeholder="userId" />
            </label>
            <label>
              <span>会话ID</span>
              <input value={filter.sessionId} onChange={(event) => update({ sessionId: event.target.value })} placeholder="sessionId" />
            </label>
            <label>
              <span>任务ID</span>
              <input value={filter.taskId} onChange={(event) => update({ taskId: event.target.value })} placeholder="taskId" />
            </label>
            <label>
              <span>Logger</span>
              <input value={filter.logger} onChange={(event) => update({ logger: event.target.value })} placeholder="clawagent.server" />
            </label>
            <label className="log-filter-wide">
              <span>关键词</span>
              <input value={filter.keyword} onChange={(event) => update({ keyword: event.target.value })} placeholder="输入关键词过滤原始日志行" />
            </label>
            <div className="log-filter-actions">
              <button className="primary-button" onClick={onQuery} disabled={loading}>查询</button>
              <button onClick={onReset} disabled={loading}>重置当天</button>
            </div>
          </div>
        )}
        <div className="session-summary">
          <span>当前日志源：{sources.find((source) => !source.compressed)?.name || 'clawagent.log'}</span>
          <span>历史 gz：{compressedCount} 个</span>
          <span>返回：{logs.length} 条</span>
          {filterCollapsed && <span>筛选：{systemLogFilterSummary(filter)}</span>}
          {message && <span>{message}</span>}
        </div>
      </Panel>
      <Panel title="匹配日志">
        <SystemLogTable logs={logs} selectedLog={selectedLog} onSelect={onSelect} />
      </Panel>
      {selectedLog && (
        <Modal onClose={onCloseDetail}>
          <SystemLogDetail line={selectedLog} onClose={onCloseDetail} />
        </Modal>
      )}
    </section>
  );
}

function SystemLogTable({
  logs,
  selectedLog,
  onSelect,
}: {
  logs: SystemLogLine[];
  selectedLog?: SystemLogLine;
  onSelect: (line: SystemLogLine) => void;
}) {
  if (!logs.length) return <Empty text="暂无匹配日志" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>时间</th>
          <th>级别</th>
          <th>用户</th>
          <th>会话ID</th>
          <th>任务ID</th>
          <th>来源</th>
          <th>摘要</th>
          <th>文件</th>
          <th>详情</th>
        </tr>
      </thead>
      <tbody>
        {logs.map((line, index) => (
          <tr
            key={`${line.sourceFile}-${line.time}-${index}`}
            className={`clickable-row ${line === selectedLog ? 'selected-row' : ''}`}
            onClick={() => onSelect(line)}
          >
            <td>{line.time || '-'}</td>
            <td><span className={`pill ${logLevelClass(line.level)}`}>{line.level || '-'}</span></td>
            <td>{line.userId || '-'}</td>
            <td className="mono">{short(line.sessionId, 18)}</td>
            <td className="mono">{short(line.taskId, 18)}</td>
            <td className="mono">{short(line.logger, 32)}</td>
            <td>{short(line.message, 90)}</td>
            <td>
              <span className="mono">{line.sourceFile || '-'}</span>
              {line.compressed && <span className="pill neutral log-source-pill">gz</span>}
            </td>
            <td>
              <button
                type="button"
                className="link-button"
                onClick={(event) => {
                  event.stopPropagation();
                  onSelect(line);
                }}
              >
                查看
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function SystemLogDetail({ line, onClose }: { line: SystemLogLine; onClose: () => void }) {
  return (
    <Panel title="日志详情" action={<button onClick={onClose}>关闭</button>}>
      <div className="event-detail">
        <div className="session-summary">
          <span>时间：{line.time || '-'}</span>
          <span>级别：{line.level || '-'}</span>
          <span>线程：{line.thread || '-'}</span>
          <span>文件：{line.sourceFile || '-'}</span>
          <span>{line.compressed ? '历史压缩日志' : '当前日志'}</span>
        </div>
        <div className="definition-list log-definition-list">
          <div><span>traceId</span><strong className="mono">{line.traceId || '-'}</strong></div>
          <div><span>userId</span><strong className="mono">{line.userId || '-'}</strong></div>
          <div><span>sessionId</span><strong className="mono">{line.sessionId || '-'}</strong></div>
          <div><span>taskId</span><strong className="mono">{line.taskId || '-'}</strong></div>
          <div><span>channelId</span><strong className="mono">{line.channelId || '-'}</strong></div>
          <div><span>logger</span><strong className="mono">{line.logger || '-'}</strong></div>
        </div>
        <div className="event-message">{line.message || '无消息内容'}</div>
        <pre className="json-block log-raw-block">{line.rawLine || ''}</pre>
      </div>
    </Panel>
  );
}

function logLevelClass(level?: string) {
  const normalized = (level || '').toUpperCase();
  if (normalized === 'ERROR') return 'danger';
  if (normalized === 'WARN') return 'warning';
  if (normalized === 'INFO') return 'success';
  return 'neutral';
}

function systemLogFilterSummary(filter: SystemLogFilter) {
  const parts = [
    `${filter.from || '今天'} ~ ${filter.to || filter.from || '今天'}`,
    filter.level || '全部级别',
    filter.userId ? `user=${short(filter.userId, 18)}` : '',
    filter.sessionId ? `session=${short(filter.sessionId, 18)}` : '',
    filter.taskId ? `task=${short(filter.taskId, 18)}` : '',
    filter.logger ? `logger=${short(filter.logger, 24)}` : '',
    filter.keyword ? `keyword=${short(filter.keyword, 24)}` : '',
    `limit=${filter.limit}`,
  ].filter(Boolean);
  return parts.join(' / ');
}

function processDiagnosisPill(status?: string) {
  if (status === 'healthy') return 'success';
  if (status === 'failed') return 'danger';
  return 'neutral';
}

function processDiagnosisText(status?: string) {
  if (status === 'healthy') return '健康';
  if (status === 'failed') return '失败';
  return '待确认';
}

function processHealthPill(status?: string) {
  if (status === 'healthy') return 'success';
  if (status === 'unhealthy' || status === 'invalid') return 'warning';
  return 'neutral';
}

function processHealthText(status?: string) {
  if (status === 'healthy') return '健康';
  if (status === 'unhealthy') return '未就绪';
  if (status === 'invalid') return 'URL 无效';
  return '未配置';
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

function ProcessPage({
  processes,
  logs,
  loading,
  message,
  onRefresh,
  onLogs,
  onStop,
}: {
  processes: ManagedProcessView[];
  logs?: ManagedProcessLogsView;
  loading: boolean;
  message?: string;
  onRefresh: () => void;
  onLogs: (pid: number) => void;
  onStop: (pid: number, force: boolean) => void;
}) {
  const runningCount = processes.filter((process) => process.status === 'running').length;
  return (
    <section className="stack process-page">
      <div className="knowledge-summary">
        <div className="knowledge-stat"><Monitor size={18} /><span>托管进程</span><strong>{processes.length}</strong></div>
        <div className="knowledge-stat"><CheckCircle size={18} /><span>运行中</span><strong>{runningCount}</strong></div>
        <div className="knowledge-stat"><Clock size={18} /><span>已退出</span><strong>{processes.length - runningCount}</strong></div>
      </div>
      {message && <div className="knowledge-message">{message}</div>}
      <div className="process-layout">
        <Panel title="后台进程" action={<button onClick={onRefresh} disabled={loading}><RefreshCw size={15} />刷新</button>}>
          {processes.length === 0 ? <Empty text="暂无由 builtin.process.start 托管的后台进程。" /> : (
            <Table>
              <thead>
                <tr>
                  <th>PID</th>
                  <th>状态</th>
                  <th>命令</th>
                  <th>目录</th>
                  <th>端口</th>
                  <th>健康</th>
                  <th>诊断</th>
                  <th>来源</th>
                  <th>启动时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {processes.map((process) => (
                  <tr key={process.pid}>
                    <td className="mono">{process.pid}</td>
                    <td>
                      <span className={`pill ${process.status === 'running' ? 'success' : 'neutral'}`}>{process.status === 'running' ? '运行中' : '已退出'}</span>
                      {process.persistent && <span className="pill neutral" title={process.storePath || '持久化恢复记录'}>恢复</span>}
                    </td>
                    <td title={process.commandLine}><span className="mono">{short(process.commandLine, 76)}</span></td>
                    <td title={process.cwd}><span className="mono">{short(process.cwd, 58)}</span></td>
                    <td>{process.ports?.length ? process.ports.map((port) => (
                      <span className={`pill ${process.portStatus?.[String(port)] ? 'success' : 'neutral'}`} key={port}>
                        {port}{process.portStatus?.[String(port)] ? ' listening' : ''}
                      </span>
                    )) : '-'}</td>
                    <td title={process.health?.url || process.health?.message || ''}>
                      {process.health ? (
                        <span className={`pill ${processHealthPill(process.health.status)}`}>
                          {processHealthText(process.health.status)}{process.health.httpStatus ? ` ${process.health.httpStatus}` : ''}
                        </span>
                      ) : '-'}
                    </td>
                    <td title={process.diagnosis?.nextAction || process.diagnosis?.summary || ''}>
                      {process.diagnosis ? (
                        <div className="process-diagnosis-cell">
                          <span className={`pill ${processDiagnosisPill(process.diagnosis.status)}`}>
                            {processDiagnosisText(process.diagnosis.status)}
                          </span>
                          <span>{short(process.diagnosis.summary, 52)}</span>
                        </div>
                      ) : '-'}
                    </td>
                    <td>
                      <div className="process-source-cell">
                        <span title={process.projectPath || process.cwd} className="mono">{short(process.projectPath || process.cwd, 42)}</span>
                        {process.taskId && <small title={process.taskId}>task {short(process.taskId, 8)}</small>}
                        {process.sessionId && <small title={process.sessionId}>session {short(process.sessionId, 8)}</small>}
                      </div>
                    </td>
                    <td>{formatDateTime(process.startedAt)}</td>
                    <td>
                      <div className="row-actions">
                        <button className="tiny-button" onClick={() => onLogs(process.pid)} disabled={loading}>日志</button>
                        <button className="tiny-button danger-text" onClick={() => onStop(process.pid, false)} disabled={loading || process.status !== 'running'}>
                          <Square size={13} />停止
                        </button>
                        <button className="tiny-button danger-text" onClick={() => onStop(process.pid, true)} disabled={loading || process.status !== 'running'}>强停</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>
        <Panel title={logs ? `进程日志：${logs.pid}` : '进程日志'}>
          {!logs ? <Empty text="选择一个进程查看日志尾部。" /> : (
            <div className="process-log-detail">
              <div className="tool-command-meta">
                <span className="mono">{logs.logPath || '-'}</span>
                <span className={`pill ${logs.status === 'running' ? 'success' : 'neutral'}`}>{logs.status === 'running' ? '运行中' : '已退出'}</span>
                {logs.health && (
                  <span className={`pill ${processHealthPill(logs.health.status)}`} title={logs.health.url || logs.health.message || ''}>
                    {processHealthText(logs.health.status)}{logs.health.httpStatus ? ` ${logs.health.httpStatus}` : ''}
                  </span>
                )}
              </div>
              {logs.diagnosis && (
                <div className="process-diagnosis-detail">
                  <div>
                    <span className={`pill ${processDiagnosisPill(logs.diagnosis.status)}`}>
                      {processDiagnosisText(logs.diagnosis.status)}
                    </span>
                    <strong>{logs.diagnosis.summary}</strong>
                  </div>
                  {logs.diagnosis.nextAction && <p>{logs.diagnosis.nextAction}</p>}
                  {!!logs.diagnosis.evidence?.length && (
                    <ul>
                      {logs.diagnosis.evidence.map((line, index) => (
                        <li key={`${index}-${line}`} className="mono">{line}</li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
              <pre>{logs.logs || '(无日志)'}</pre>
            </div>
          )}
        </Panel>
      </div>
    </section>
  );
}

function KnowledgePage({
  providers,
  documents,
  vectorStatus,
  selectedIds,
  loading,
  message,
  searchQuery,
  searchMode,
  searchHits,
  onUpload,
  onRefresh,
  onDelete,
  onToggleSelected,
  onSearchQueryChange,
  onSearchModeChange,
  onSearch,
}: {
  providers: KnowledgeProviderView[];
  documents: KnowledgeDocument[];
  vectorStatus: VectorStatusView[];
  selectedIds: string[];
  loading: boolean;
  message?: string;
  searchQuery: string;
  searchMode: KnowledgeSearchMode;
  searchHits: KnowledgeSearchHit[];
  onUpload: (files: FileList | File[]) => void;
  onRefresh: () => void;
  onDelete: (documentId: string) => void;
  onToggleSelected: (documentId: string) => void;
  onSearchQueryChange: (query: string) => void;
  onSearchModeChange: (mode: KnowledgeSearchMode) => void;
  onSearch: () => void;
}) {
  const uploadRef = useRef<HTMLInputElement>(null);
  const readyCount = documents.filter((document) => document.status === 'READY').length;
  const vectorizedCount = vectorStatus.filter((item) => item.vectorized).length;
  return (
    <section className="stack knowledge-page">
      <div className="knowledge-summary">
        <div className="knowledge-stat">
          <Database size={18} />
          <span>文档</span>
          <strong>{documents.length}</strong>
        </div>
        <div className="knowledge-stat">
          <CheckCircle size={18} />
          <span>可检索</span>
          <strong>{readyCount}</strong>
        </div>
        <div className="knowledge-stat">
          <FileText size={18} />
          <span>已选择</span>
          <strong>{selectedIds.length}</strong>
        </div>
        <div className="knowledge-stat provider">
          <Plug size={18} />
          <span>已向量化</span>
          <strong>{vectorizedCount}</strong>
        </div>
      </div>

      <div className="knowledge-layout">
        <Panel
          title="本地知识库文件"
          action={(
            <div className="panel-actions">
              <button onClick={onRefresh} disabled={loading}><RefreshCw size={15} />刷新</button>
              <button onClick={() => uploadRef.current?.click()} disabled={loading}><Upload size={15} />上传</button>
              <input
                ref={uploadRef}
                className="sr-only"
                type="file"
                multiple
                onChange={(event) => {
                  if (event.target.files) onUpload(event.target.files);
                  event.target.value = '';
                }}
              />
            </div>
          )}
        >
          {message && <div className="knowledge-message">{message}</div>}
          <KnowledgeDocumentTable
            documents={documents}
            vectorStatus={vectorStatus}
            selectedIds={selectedIds}
            loading={loading}
            onToggleSelected={onToggleSelected}
            onDelete={onDelete}
          />
        </Panel>

        <Panel title="检索调试">
          <div className="knowledge-search">
            <div className="knowledge-search-scope">
              <span>检索范围</span>
              <strong>{selectedIds.length > 0 ? `已选 ${selectedIds.length} 个文档` : '当前用户全库'}</strong>
            </div>
            <div className="form-field">
              <span>Query</span>
              <textarea value={searchQuery} onChange={(event) => onSearchQueryChange(event.target.value)} placeholder="输入要检索的问题或关键词" />
            </div>
            <div className="knowledge-search-row">
              <select value={searchMode} onChange={(event) => onSearchModeChange(event.target.value as KnowledgeSearchMode)}>
                <option value="hybrid">混合检索</option>
                <option value="keyword">关键词</option>
                <option value="vector">向量</option>
              </select>
              <button className="knowledge-search-button" onClick={onSearch} disabled={loading || !searchQuery.trim()}>
                <Search size={15} />检索
              </button>
            </div>
            <div className="knowledge-hit-list">
              {searchHits.length === 0 ? (
                <Empty text="暂无检索结果" />
              ) : searchHits.map((hit, index) => (
                <article className="knowledge-hit" key={`${hit.chunkId || hit.documentId}-${index}`}>
                  <div>
                    <strong>{hit.documentName || hit.documentId}</strong>
                    <span className="pill neutral">{hit.provider || 'local'}</span>
                    <span className="mono">score {typeof hit.score === 'number' ? hit.score.toFixed(4) : '-'}</span>
                  </div>
                  <p>{short(hit.text, 360)}</p>
                </article>
              ))}
            </div>
          </div>
        </Panel>
      </div>
    </section>
  );
}

function KnowledgeDocumentTable({
  documents,
  vectorStatus,
  selectedIds,
  loading,
  onToggleSelected,
  onDelete,
}: {
  documents: KnowledgeDocument[];
  vectorStatus: VectorStatusView[];
  selectedIds: string[];
  loading: boolean;
  onToggleSelected: (documentId: string) => void;
  onDelete: (documentId: string) => void;
}) {
  const vectorById = new Map(vectorStatus.map((item) => [item.id, item]));
  if (!documents.length) return <Empty text="暂无入库文件，点击上传后会自动解析并写入本地知识库。" />;
  return (
    <div className="knowledge-table">
      <Table>
        <thead>
          <tr>
            <th className="select-col">引用</th>
            <th>文件</th>
            <th>状态</th>
            <th>向量</th>
            <th>Provider</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {documents.map((document) => {
            const selected = selectedIds.includes(document.id);
            const vector = vectorById.get(document.id);
            return (
              <tr className={selected ? 'selected-row' : undefined} key={document.id}>
                <td className="select-col">
                  <input
                    type="checkbox"
                    checked={selected}
                    onChange={() => onToggleSelected(document.id)}
                    aria-label={`选择 ${document.name || document.id}`}
                  />
                </td>
                <td>
                  <div className="knowledge-file-cell">
                    <span className="knowledge-file-icon"><FileText size={16} /></span>
                    <div>
                      <strong>{document.name || document.id}</strong>
                      <small className="muted block">
                        {formatFileSize(document.size || 0)} · {document.kind || 'file'} · <span className="mono">{short(document.id, 18)}</span>
                      </small>
                    </div>
                  </div>
                </td>
                <td><span className={`pill ${document.status === 'READY' ? 'success' : 'neutral'}`}>{document.status || '-'}</span></td>
                <td>
                  <span className={`pill ${vector?.vectorized ? 'success' : 'warning'}`}>
                    {(vector?.vectorCount ?? 0)}/{(vector?.chunkCount ?? 0)}
                  </span>
                </td>
                <td><span className="pill neutral">{document.provider || 'local'}</span></td>
                <td>{formatDateTime(document.createdAt)}</td>
                <td>
                  <div className="row-actions">
                    <a className="tiny-button" href={api.knowledgeDownloadUrl(document.id, 'console')} download title="下载">
                      <Download size={14} />下载
                    </a>
                    <button className="tiny-button danger-text" disabled={loading} onClick={() => onDelete(document.id)} title="删除">
                      <Trash2 size={14} />删除
                    </button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </Table>
    </div>
  );
}

function MemoryPage({
  items,
  vectorStatus,
  candidates,
  hits,
  searchHits,
  selectedId,
  draft,
  loading,
  message,
  searchQuery,
  searchMode,
  onRefresh,
  onSelect,
  onNew,
  onDraftChange,
  onSave,
  onDelete,
  onStatus,
  onSearchQueryChange,
  onSearchModeChange,
  onSearch,
}: {
  items: MemoryItem[];
  vectorStatus: VectorStatusView[];
  candidates: MemoryItem[];
  hits: MemoryHitLog[];
  searchHits: MemorySearchHit[];
  selectedId?: string;
  draft: MemoryUpsertRequest;
  loading: boolean;
  message?: string;
  searchQuery: string;
  searchMode: MemorySearchMode;
  onRefresh: () => void;
  onSelect: (item: MemoryItem) => void;
  onNew: () => void;
  onDraftChange: (draft: MemoryUpsertRequest) => void;
  onSave: () => void;
  onDelete: (itemId: string) => void;
  onStatus: (itemId: string, action: 'enable' | 'disable' | 'archive' | 'accept' | 'reject') => void;
  onSearchQueryChange: (query: string) => void;
  onSearchModeChange: (mode: MemorySearchMode) => void;
  onSearch: () => void;
}) {
  const activeCount = items.filter((item) => item.status === 'active').length;
  const vectorizedCount = vectorStatus.filter((item) => item.vectorized).length;
  return (
    <section className="stack memory-page">
      <div className="knowledge-summary">
        <div className="knowledge-stat"><ScrollText size={18} /><span>记忆</span><strong>{items.length}</strong></div>
        <div className="knowledge-stat"><CheckCircle size={18} /><span>Active</span><strong>{activeCount}</strong></div>
        <div className="knowledge-stat"><Clock size={18} /><span>候选</span><strong>{candidates.length}</strong></div>
        <div className="knowledge-stat provider"><Search size={18} /><span>已向量化</span><strong>{vectorizedCount}</strong></div>
      </div>
      {message && <div className="knowledge-message">{message}</div>}

      <div className="memory-layout">
        <div className="stack">
          <Panel title="长期记忆" action={<button onClick={onRefresh} disabled={loading}><RefreshCw size={15} />刷新</button>}>
            <MemoryItemTable
              items={items}
              vectorStatus={vectorStatus}
              selectedId={selectedId}
              loading={loading}
              onSelect={onSelect}
              onDelete={onDelete}
              onStatus={onStatus}
            />
          </Panel>
          <Panel title="候选记忆">
            <MemoryCandidateList candidates={candidates} loading={loading} onSelect={onSelect} onStatus={onStatus} />
          </Panel>
        </div>

        <div className="stack">
          <Panel title={draft.id ? `编辑记忆：${short(draft.id, 18)}` : '新建记忆'} action={<button onClick={onNew}>新建</button>}>
            <MemoryEditor draft={draft} loading={loading} onChange={onDraftChange} onSave={onSave} />
          </Panel>
          <Panel title="检索调试">
            <div className="knowledge-search">
              <div className="form-field">
                <span>Query</span>
                <textarea value={searchQuery} onChange={(event) => onSearchQueryChange(event.target.value)} placeholder="输入要检索的偏好、事实或决策" />
              </div>
              <div className="knowledge-search-row">
                <select value={searchMode} onChange={(event) => onSearchModeChange(event.target.value as MemorySearchMode)}>
                  <option value="hybrid">混合检索</option>
                  <option value="keyword">关键词</option>
                  <option value="vector">向量</option>
                </select>
                <button className="knowledge-search-button" onClick={onSearch} disabled={loading || !searchQuery.trim()}>
                  <Search size={15} />检索
                </button>
              </div>
              <div className="knowledge-hit-list">
                {searchHits.length === 0 ? <Empty text="暂无检索结果" /> : searchHits.map((hit, index) => (
                  <article className="knowledge-hit" key={`${hit.chunkId || hit.itemId}-${index}`}>
                    <div>
                      <strong>{hit.summary || short(hit.content, 40) || hit.itemId}</strong>
                      <span className={`pill ${memoryStatusClass('active')}`}>{hit.scopeType || '-'}</span>
                      <span className="mono">score {typeof hit.score === 'number' ? hit.score.toFixed(4) : '-'}</span>
                      {hit.metadata?.retrievalSource && <span className="pill neutral">{hit.metadata.retrievalSource}</span>}
                    </div>
                    <p>{short(hit.content, 360)}</p>
                    {(hit.metadata?.rrfScore || hit.metadata?.qualityScore) && (
                      <small className="muted block">
                        {hit.metadata?.rrfScore ? `RRF ${hit.metadata.rrfScore} · rank ${hit.metadata.retrievalRank || '-'}` : ''}
                        {hit.metadata?.qualityScore ? `${hit.metadata?.rrfScore ? ' · ' : ''}质量 ${formatQuality(hit.metadata.qualityScore)} · 降权 ${formatQuality(hit.metadata.stalenessPenalty)}` : ''}
                      </small>
                    )}
                  </article>
                ))}
              </div>
            </div>
          </Panel>
          <Panel title="命中记录">
            <MemoryHitTable hits={hits} />
          </Panel>
        </div>
      </div>
    </section>
  );
}

function MemoryItemTable({
  items,
  vectorStatus,
  selectedId,
  loading,
  onSelect,
  onDelete,
  onStatus,
}: {
  items: MemoryItem[];
  vectorStatus: VectorStatusView[];
  selectedId?: string;
  loading: boolean;
  onSelect: (item: MemoryItem) => void;
  onDelete: (itemId: string) => void;
  onStatus: (itemId: string, action: 'enable' | 'disable' | 'archive') => void;
}) {
  const vectorById = new Map(vectorStatus.map((item) => [item.id, item]));
  if (!items.length) return <Empty text="暂无长期记忆。可从候选审核，也可以手动新建。" />;
  return (
    <div className="memory-table">
      <Table>
        <thead>
          <tr>
            <th>摘要</th>
            <th>Scope</th>
            <th>状态</th>
            <th>质量</th>
            <th>向量</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => {
            const vector = vectorById.get(item.id);
            return (
              <tr className={item.id === selectedId ? 'selected-row' : undefined} key={item.id} onClick={() => onSelect(item)}>
                <td>
                  <strong>{item.summary || short(item.content, 48)}</strong>
                  <small className="muted block">{short(item.content, 120)}</small>
                </td>
                <td><span className="pill neutral">{item.scopeType || '-'}</span></td>
                <td><span className={`pill ${memoryStatusClass(item.status)}`}>{item.status || '-'}</span></td>
                <td>
                  <span className="pill neutral">{formatQuality(item.metadata?.qualityScore)}</span>
                  {item.metadata?.staleDays && <small className="muted block">{item.metadata.staleDays} 天未命中</small>}
                </td>
                <td>
                  <span className={`pill ${vector?.vectorized ? 'success' : 'warning'}`}>
                    {(vector?.vectorCount ?? 0)}/{(vector?.chunkCount ?? 0)}
                  </span>
                </td>
                <td>{formatDateTime(item.updatedAt || item.createdAt)}</td>
                <td>
                  <div className="row-actions" onClick={(event) => event.stopPropagation()}>
                    {item.status === 'active'
                      ? <button className="tiny-button" disabled={loading} onClick={() => onStatus(item.id, 'disable')}>禁用</button>
                      : <button className="tiny-button" disabled={loading} onClick={() => onStatus(item.id, 'enable')}>启用</button>}
                    <button className="tiny-button" disabled={loading} onClick={() => onStatus(item.id, 'archive')}>归档</button>
                    <button className="tiny-button danger-text" disabled={loading} onClick={() => onDelete(item.id)}><Trash2 size={14} />删除</button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </Table>
    </div>
  );
}

function MemoryCandidateList({
  candidates,
  loading,
  onSelect,
  onStatus,
}: {
  candidates: MemoryItem[];
  loading: boolean;
  onSelect: (item: MemoryItem) => void;
  onStatus: (itemId: string, action: 'accept' | 'reject') => void;
}) {
  if (!candidates.length) return <Empty text="暂无候选记忆。task 结束后的提炼结果会先进入这里。" />;
  return (
    <div className="memory-candidate-list">
      {candidates.map((item) => (
        <article className="memory-candidate" key={item.id}>
          <button className="link-button" onClick={() => onSelect(item)}>{item.summary || short(item.content, 50)}</button>
          <p>{short(item.content, 180)}</p>
          {(item.metadata?.governance || item.metadata?.conflictWith || item.metadata?.duplicateReason) && (
            <small className="muted block">
              {item.metadata?.governance || item.status}
              {item.metadata?.conflictWith ? ` · 冲突对象 ${short(item.metadata.conflictWith, 18)}` : ''}
              {item.metadata?.duplicateReason ? ` · ${item.metadata.duplicateReason}` : ''}
              {item.metadata?.qualityScore ? ` · 质量 ${formatQuality(item.metadata.qualityScore)}` : ''}
            </small>
          )}
          <div className="row-actions">
            <span className="pill neutral">{item.scopeType || '-'}</span>
            <span className={`pill ${memoryStatusClass(item.status)}`}>{item.status || '-'}</span>
            <button className="tiny-button" disabled={loading} onClick={() => onStatus(item.id, 'accept')}><Check size={14} />通过</button>
            <button className="tiny-button danger-text" disabled={loading} onClick={() => onStatus(item.id, 'reject')}><X size={14} />拒绝</button>
          </div>
        </article>
      ))}
    </div>
  );
}

function MemoryEditor({
  draft,
  loading,
  onChange,
  onSave,
}: {
  draft: MemoryUpsertRequest;
  loading: boolean;
  onChange: (draft: MemoryUpsertRequest) => void;
  onSave: () => void;
}) {
  return (
    <div className="config-form memory-editor">
      <div className="form-grid">
        <label className="form-field">
          <span>Scope</span>
          <select value={draft.scopeType || 'session'} onChange={(event) => onChange({ ...draft, scopeType: event.target.value })}>
            <option value="global">global</option>
            <option value="channel">channel</option>
            <option value="session">session</option>
          </select>
        </label>
        <label className="form-field">
          <span>Scope ID</span>
          <input value={draft.scopeId || ''} onChange={(event) => onChange({ ...draft, scopeId: event.target.value })} placeholder="global 可留空" />
        </label>
        <label className="form-field">
          <span>类型</span>
          <input value={draft.type || ''} onChange={(event) => onChange({ ...draft, type: event.target.value })} placeholder="fact/preference/decision" />
        </label>
        <label className="form-field">
          <span>状态</span>
          <select value={draft.status || 'pending'} onChange={(event) => onChange({ ...draft, status: event.target.value })}>
            <option value="pending">pending</option>
            <option value="active">active</option>
            <option value="conflict">conflict</option>
            <option value="disabled">disabled</option>
            <option value="archived">archived</option>
          </select>
        </label>
        <label className="form-field wide">
          <span>摘要</span>
          <input value={draft.summary || ''} onChange={(event) => onChange({ ...draft, summary: event.target.value })} placeholder="列表展示和上下文预览用" />
        </label>
        <label className="form-field wide">
          <span>正文</span>
          <textarea value={draft.content || ''} onChange={(event) => onChange({ ...draft, content: event.target.value })} placeholder="稳定事实、偏好、决策或经验" />
        </label>
      </div>
      <div className="form-actions">
        <button className="primary-button" disabled={loading || !String(draft.content || '').trim()} onClick={onSave}>
          <Check size={15} />保存记忆
        </button>
      </div>
    </div>
  );
}

function MemoryHitTable({ hits }: { hits: MemoryHitLog[] }) {
  if (!hits.length) return <Empty text="暂无命中记录。Runtime 注入长期记忆后会记录在这里。" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>记忆ID</th>
          <th>会话/任务</th>
          <th>分数</th>
          <th>时间</th>
        </tr>
      </thead>
      <tbody>
        {hits.map((hit) => (
          <tr key={hit.id}>
            <td className="mono">{short(hit.itemId, 18)}</td>
            <td>
              <span className="mono">{short(hit.sessionId, 14)}</span>
              <small className="muted block">{short(hit.taskId, 18)}</small>
            </td>
            <td>{typeof hit.score === 'number' ? hit.score.toFixed(4) : '-'}</td>
            <td>{formatDateTime(hit.createdAt)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function memoryStatusClass(status?: string) {
  if (status === 'active') return 'success';
  if (status === 'pending') return 'warning';
  if (status === 'conflict') return 'danger';
  if (status === 'disabled' || status === 'archived') return 'neutral';
  return 'neutral';
}

function AutomationPage({
  automations,
  selectedAutomation,
  draft,
  runs,
  saving,
  message,
  runDetail,
  onSelect,
  onDraftChange,
  onNew,
  onSave,
  onDelete,
  onToggle,
  onRun,
  onRefresh,
  onViewRunResult,
  onCloseRunDetail,
}: {
  automations: AutomationDefinition[];
  selectedAutomation?: AutomationDefinition;
  draft: AutomationUpsertRequest;
  runs: AutomationRun[];
  saving: boolean;
  message?: string;
  runDetail?: AutomationRunDetail;
  onSelect: (automation: AutomationDefinition) => void;
  onDraftChange: (draft: AutomationUpsertRequest) => void;
  onNew: () => void;
  onSave: () => void;
  onDelete: () => void;
  onToggle: (automation: AutomationDefinition) => void;
  onRun: (automationId?: string) => void;
  onRefresh: () => void;
  onViewRunResult: (run: AutomationRun) => void;
  onCloseRunDetail: () => void;
}) {
  const enabledCount = automations.filter((automation) => automation.status === 'ENABLED').length;
  const dueCount = automations.filter((automation) => automation.nextRunAt).length;
  return (
    <section className="stack">
      <div className="metric-grid metric-grid-compact">
        <Metric title="定时任务" value={automations.length} desc="全部自动化定义" />
        <Metric title="启用中" value={enabledCount} desc="ENABLED" />
        <Metric title="待触发" value={dueCount} desc="已计算 nextRunAt" />
        <Metric title="运行记录" value={runs.length} desc="当前选中任务" />
      </div>

      <div className="automation-layout">
        <div className="stack">
          <Panel title="定时任务列表" action={<button onClick={onRefresh}>刷新</button>}>
            <AutomationTable
              automations={automations}
              selectedId={selectedAutomation?.id}
              saving={saving}
              onSelect={onSelect}
              onToggle={onToggle}
              onRun={onRun}
            />
          </Panel>
          <Panel title="运行历史">
            <AutomationRunTable runs={runs} selectedTaskId={runDetail?.run.taskId} onViewResult={onViewRunResult} />
          </Panel>
          {runDetail && <AutomationRunDetailPanel detail={runDetail} onClose={onCloseRunDetail} />}
        </div>

        <Panel
          title={selectedAutomation ? `编辑：${selectedAutomation.name || selectedAutomation.id}` : '新建定时任务'}
          action={<button onClick={onNew}>新建</button>}
        >
          <div className="config-form">
            <div className="form-grid">
              <label className="form-field">
                <span>名称</span>
                <input value={draft.name || ''} onChange={(event) => onDraftChange({ ...draft, name: event.target.value })} placeholder="例如：每日汇总" />
              </label>
              <label className="form-field">
                <span>状态</span>
                <select value={draft.status || 'ENABLED'} onChange={(event) => onDraftChange({ ...draft, status: event.target.value as AutomationDefinition['status'] })}>
                  <option value="ENABLED">启用</option>
                  <option value="PAUSED">暂停</option>
                </select>
              </label>
              <label className="form-field">
                <span>调度类型</span>
                <select value={draft.scheduleType || 'INTERVAL'} onChange={(event) => onDraftChange({ ...draft, scheduleType: event.target.value as AutomationDefinition['scheduleType'] })}>
                  <option value="INTERVAL">固定间隔</option>
                  <option value="CRON">Cron 表达式</option>
                  <option value="ONCE">单次执行</option>
                </select>
              </label>
              <label className="form-field">
                <span>时区</span>
                <input value={draft.timezone || ''} onChange={(event) => onDraftChange({ ...draft, timezone: event.target.value })} placeholder="Asia/Shanghai" />
              </label>
              <label className="form-field">
                <span>间隔秒数</span>
                <input type="number" min={1} value={draft.intervalSeconds ?? ''} onChange={(event) => onDraftChange({ ...draft, intervalSeconds: Number(event.target.value || 0) })} placeholder="3600" />
              </label>
              <label className="form-field">
                <span>Cron</span>
                <input value={draft.cronExpression || ''} onChange={(event) => onDraftChange({ ...draft, cronExpression: event.target.value })} placeholder="0 0/30 * * * *" />
              </label>
              <label className="form-field">
                <span>下次执行时间</span>
                <input value={draft.nextRunAt || ''} onChange={(event) => onDraftChange({ ...draft, nextRunAt: event.target.value })} placeholder="2026-06-04T09:00:00+08:00" />
              </label>
              <label className="form-field">
                <span>失败重试次数</span>
                <input
                  type="number"
                  min={0}
                  value={automationMetadataValue(draft, AUTOMATION_RETRY_MAX_ATTEMPTS)}
                  onChange={(event) => onDraftChange(withAutomationMetadata(draft, AUTOMATION_RETRY_MAX_ATTEMPTS, event.target.value))}
                  placeholder="留空使用全局配置"
                />
              </label>
              <label className="form-field">
                <span>退避秒数</span>
                <input
                  type="number"
                  min={1}
                  value={automationMetadataValue(draft, AUTOMATION_RETRY_BACKOFF_SECONDS)}
                  onChange={(event) => onDraftChange(withAutomationMetadata(draft, AUTOMATION_RETRY_BACKOFF_SECONDS, event.target.value))}
                  placeholder="留空使用全局配置"
                />
              </label>
              <label className="form-field">
                <span>复用会话ID</span>
                <input value={draft.sessionId || ''} onChange={(event) => onDraftChange({ ...draft, sessionId: event.target.value })} placeholder="留空则每次由 Runtime 创建" />
              </label>
              <label className="form-field">
                <span>通道 ID</span>
                <input value={draft.channelId || ''} onChange={(event) => onDraftChange({ ...draft, channelId: event.target.value })} placeholder="automation" />
              </label>
              <label className="form-field">
                <span>User ID</span>
                <input value={draft.userId || ''} onChange={(event) => onDraftChange({ ...draft, userId: event.target.value })} placeholder="automation" />
              </label>
            </div>
            <label className="checkbox-line">
              <input
                type="checkbox"
                checked={automationMetadataBoolean(draft, AUTOMATION_RETRY_PAUSE_AFTER_EXHAUSTED)}
                onChange={(event) => onDraftChange(withAutomationMetadata(
                  draft,
                  AUTOMATION_RETRY_PAUSE_AFTER_EXHAUSTED,
                  event.target.checked ? 'true' : ''
                ))}
              />
              <span>重试耗尽后自动暂停任务</span>
            </label>
            <label className="form-field">
              <span>Prompt</span>
              <textarea value={draft.prompt || ''} onChange={(event) => onDraftChange({ ...draft, prompt: event.target.value })} placeholder="写清楚自动化要让 Agent 执行的任务。" />
            </label>
            {message && <div className="automation-message">{message}</div>}
            <div className="form-actions">
              <button onClick={onSave} disabled={saving || !draft.name?.trim() || !draft.prompt?.trim()}>{saving ? '保存中...' : '保存'}</button>
              <button className="secondary" onClick={() => onRun(selectedAutomation?.id)} disabled={saving || !selectedAutomation}>立即运行</button>
              <button className="danger-button" onClick={onDelete} disabled={saving || !selectedAutomation}>删除</button>
            </div>
          </div>
        </Panel>
      </div>
    </section>
  );
}

function AutomationTable({
  automations,
  selectedId,
  saving,
  onSelect,
  onToggle,
  onRun,
}: {
  automations: AutomationDefinition[];
  selectedId?: string;
  saving: boolean;
  onSelect: (automation: AutomationDefinition) => void;
  onToggle: (automation: AutomationDefinition) => void;
  onRun: (automationId?: string) => void;
}) {
  if (!automations.length) return <Empty text="暂无定时任务。右侧填写后保存即可创建。" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>名称</th>
          <th>类型</th>
          <th>状态</th>
          <th>下次执行</th>
          <th>最近执行</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        {automations.map((automation) => (
          <tr key={automation.id} className={selectedId === automation.id ? 'selected-row' : undefined} onClick={() => onSelect(automation)}>
            <td>
              <strong>{automation.name || '-'}</strong>
              <small className="muted block">{short(automation.prompt, 48)}</small>
              {automationMetadataValue(automation, AUTOMATION_RETRY_CURRENT_ATTEMPT) ? (
                <small className="muted block">
                  连续失败 {automationMetadataValue(automation, AUTOMATION_RETRY_CURRENT_ATTEMPT)} 次
                  {automationMetadataValue(automation, AUTOMATION_RETRY_LAST_ERROR) ? `：${short(automationMetadataValue(automation, AUTOMATION_RETRY_LAST_ERROR), 42)}` : ''}
                </small>
              ) : null}
            </td>
            <td>{automation.scheduleType || '-'}</td>
            <td><span className={`pill ${automation.status === 'ENABLED' ? 'success' : 'neutral'}`}>{automation.status === 'ENABLED' ? '启用' : '暂停'}</span></td>
            <td>{formatDateTime(automation.nextRunAt)}</td>
            <td>{formatDateTime(automation.lastRunAt)}</td>
            <td>
              <div className="row-actions">
                <button onClick={(event) => { event.stopPropagation(); onToggle(automation); }} disabled={saving}>{automation.status === 'ENABLED' ? '暂停' : '启用'}</button>
                <button onClick={(event) => { event.stopPropagation(); onRun(automation.id); }} disabled={saving}>运行</button>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function AutomationRunTable({
  runs,
  selectedTaskId,
  onViewResult,
}: {
  runs: AutomationRun[];
  selectedTaskId?: string;
  onViewResult: (run: AutomationRun) => void;
}) {
  if (!runs.length) return <Empty text="暂无运行记录。" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>状态</th>
          <th>Task ID</th>
          <th>耗时</th>
          <th>Token</th>
          <th>工具</th>
          <th>开始时间</th>
          <th>结束时间</th>
          <th>错误</th>
        </tr>
      </thead>
      <tbody>
        {runs.map((run) => (
          <tr key={run.id} className={run.taskId === selectedTaskId ? 'selected-row' : undefined}>
            <td><span className={`pill ${run.status === 'COMPLETED' ? 'success' : run.status === 'FAILED' ? 'danger' : 'warning'}`}>{statusText(run.status) || run.status}</span></td>
            <td>
              {run.taskId ? (
                <button type="button" className="link-button mono" onClick={() => onViewResult(run)}>
                  查看结果 {short(run.taskId, 18)}
                </button>
              ) : (
                <span className="mono">-</span>
              )}
            </td>
            <td>{run.elapsedMs == null ? '-' : formatDuration(run.elapsedMs)}</td>
            <td>
              <span className="mono">{formatTokenCount(run.totalTokens)}</span>
              {run.tokenCalls ? <small className="muted block">{run.tokenCalls} 次调用</small> : null}
            </td>
            <td>
              <span className="mono">{run.toolCalls ?? 0}</span>
              {run.failedToolCalls ? <small className="muted block danger-text">失败 {run.failedToolCalls}</small> : null}
            </td>
            <td>{formatDateTime(run.startedAt)}</td>
            <td>{formatDateTime(run.finishedAt)}</td>
            <td>{short(run.error, 80)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function AutomationRunDetailPanel({ detail, onClose }: { detail: AutomationRunDetail; onClose: () => void }) {
  const [tab, setTab] = useState<'result' | 'messages' | 'events'>('result');
  const answer = detail.task?.finalAnswer || '';
  return (
    <Panel
      title="执行结果"
      action={<button onClick={onClose}>关闭</button>}
    >
      <div className="automation-run-detail">
        <div className="session-summary">
          <span>Task ID：<span className="mono">{detail.run.taskId || '-'}</span></span>
          <span>状态：{statusText(detail.run.status) || detail.run.status}</span>
          <span>开始：{formatDateTime(detail.run.startedAt)}</span>
          <span>结束：{formatDateTime(detail.run.finishedAt)}</span>
        </div>
        <div className="tab-row">
          {(['result', 'messages', 'events'] as Array<'result' | 'messages' | 'events'>).map((item) => (
            <button key={item} className={tab === item ? 'active' : ''} onClick={() => setTab(item)}>
              {item === 'result' ? '结果' : item === 'messages' ? '消息' : '日志'}
            </button>
          ))}
        </div>
        {detail.loading && <Empty text="正在加载执行结果..." />}
        {!detail.loading && detail.error && <div className="automation-message">{detail.error}</div>}
        {!detail.loading && !detail.error && tab === 'result' && (
          <div className="automation-result">
            {answer ? (
              <div className="markdown" dangerouslySetInnerHTML={{ __html: renderMarkdown(answer) }} />
            ) : (
              <Empty text="该任务还没有最终回答。" />
            )}
          </div>
        )}
        {!detail.loading && !detail.error && tab === 'messages' && <MessageChatView messages={detail.messages} compact />}
        {!detail.loading && !detail.error && tab === 'events' && <EventTable events={detail.events} />}
      </div>
    </Panel>
  );
}

function TaskDetailPanel({
  detail,
  activeTab,
  onTabChange,
  onClose,
  onViewTask,
  onCreateSubAgent,
}: {
  detail: TaskDetail;
  activeTab: 'summary' | 'audit' | 'result' | 'messages' | 'events' | 'tokens';
  onTabChange: (tab: 'summary' | 'audit' | 'result' | 'messages' | 'events' | 'tokens') => void;
  onClose: () => void;
  onViewTask?: (task: AgentTask) => void;
  onCreateSubAgent?: (task: AgentTask, request: SubAgentTaskRequest) => void;
}) {
  const task = detail.task;
  const answer = task?.finalAnswer || '';
  const taskUsageMap = task && detail.tokenUsage ? { [task.id]: detail.tokenUsage } : {};
  return (
    <Panel
      title={task ? `任务结果：${short(task.input, 60)}` : '任务结果'}
      action={<button onClick={onClose}>关闭</button>}
    >
      <div className="automation-run-detail">
        <div className="session-summary">
          <span>任务ID：<span className="mono">{task?.id || '-'}</span></span>
          <span>状态：{statusText(task?.status) || task?.status || '-'}</span>
          <span>创建：{formatDateTime(task?.createdAt)}</span>
          <span>更新：{formatDateTime(task?.updatedAt)}</span>
        </div>
        <div className="tab-row">
          {(['summary', 'audit', 'result', 'messages', 'events', 'tokens'] as Array<'summary' | 'audit' | 'result' | 'messages' | 'events' | 'tokens'>).map((tab) => (
            <button key={tab} className={activeTab === tab ? 'active' : ''} onClick={() => onTabChange(tab)}>
              {tab === 'summary' ? '摘要' : tab === 'audit' ? '审计' : tab === 'result' ? '结果' : tab === 'messages' ? '消息' : tab === 'events' ? '日志' : 'Token'}
            </button>
          ))}
        </div>
        {detail.loading && <Empty text="正在加载任务结果..." />}
        {!detail.loading && detail.error && <div className="automation-message">{detail.error}</div>}
        {!detail.loading && !detail.error && activeTab === 'summary' && (
          <div className="stack">
            <DevelopmentSummaryPanel summary={detail.developmentSummary} />
            <SubAgentTaskPanel
              parentTask={task}
              tasks={detail.subTasks || []}
              graph={detail.orchestrationGraph}
              onViewTask={onViewTask}
              onCreateSubAgent={onCreateSubAgent}
            />
          </div>
        )}
        {!detail.loading && !detail.error && activeTab === 'audit' && (
          <TaskAuditPanel audit={detail.taskAudit} />
        )}
        {!detail.loading && !detail.error && activeTab === 'result' && (
          <div className="automation-result">
            {answer ? (
              <div className="markdown" dangerouslySetInnerHTML={{ __html: renderMarkdown(answer) }} />
            ) : (
              <Empty text="该任务还没有最终回答。" />
            )}
          </div>
        )}
        {!detail.loading && !detail.error && activeTab === 'messages' && <MessageChatView messages={detail.messages} compact />}
        {!detail.loading && !detail.error && activeTab === 'events' && <EventTable events={detail.events} />}
        {!detail.loading && !detail.error && activeTab === 'tokens' && (
          <SessionTokenPanel
            sessionUsage={detail.tokenUsage}
            tasks={task ? [task] : []}
            taskUsages={taskUsageMap}
            cost={undefined}
          />
        )}
      </div>
    </Panel>
  );
}

function SubAgentTaskPanel({
  parentTask,
  tasks,
  graph,
  onViewTask,
  onCreateSubAgent,
}: {
  parentTask?: AgentTask;
  tasks: AgentTask[];
  graph?: AgentOrchestrationGraphView;
  onViewTask?: (task: AgentTask) => void;
  onCreateSubAgent?: (task: AgentTask, request: SubAgentTaskRequest) => void;
}) {
  const [role, setRole] = useState('reviewer');
  const [input, setInput] = useState('');
  const canCreate = Boolean(parentTask?.id && input.trim() && onCreateSubAgent);
  const submit = () => {
    if (!parentTask || !canCreate) return;
    onCreateSubAgent?.(parentTask, {
      input: input.trim(),
      role: role.trim() || 'subagent',
      isolation: 'read-only',
    });
    setInput('');
  };

  return (
    <section className="task-audit-section">
      <div className="section-title-row">
        <h4>子 Agent</h4>
        <span className="pill neutral">{tasks.length} 个</span>
      </div>
      {graph && (
        <div className="orchestration-graph">
          <div className="orchestration-summary">
            <span><strong>{graph.totalTasks ?? graph.nodes?.length ?? 0}</strong><small>任务</small></span>
            <span><strong>{graph.runningCount ?? 0}</strong><small>运行/待执行</small></span>
            <span><strong>{graph.waitingCount ?? 0}</strong><small>等待</small></span>
            <span><strong>{graph.completedCount ?? 0}</strong><small>完成</small></span>
            <span><strong>{graph.failedCount ?? 0}</strong><small>失败/取消</small></span>
            {graph.truncated && <em className="pill warning">已截断</em>}
          </div>
          {(graph.edges || []).length > 0 && (
            <div className="orchestration-edges">
              {(graph.edges || []).slice(0, 8).map((edge) => (
                <button
                  type="button"
                  key={`${edge.parentTaskId}-${edge.childTaskId}`}
                  className="orchestration-edge"
                  onClick={() => {
                    const child = tasks.find((item) => item.id === edge.childTaskId);
                    if (child) onViewTask?.(child);
                  }}
                >
                  <span className="mono">{short(edge.parentTaskId, 10)}</span>
                  <span>→</span>
                  <span className="mono">{short(edge.childTaskId, 10)}</span>
                  <em className="pill neutral">{edge.role || 'subagent'}</em>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      {onCreateSubAgent && (
        <div className="sub-agent-create">
          <div className="form-grid">
            <label className="form-field">
              <span>角色</span>
              <input value={role} onChange={(event) => setRole(event.target.value)} placeholder="reviewer / researcher" />
            </label>
            <label className="form-field">
              <span>隔离策略</span>
              <input value="read-only" disabled />
            </label>
          </div>
          <label className="form-field">
            <span>子 Agent 任务</span>
            <textarea
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="让只读子 Agent 检查当前任务的风险、查找相关代码或复核结果。"
            />
          </label>
          <div className="form-actions inline-actions">
            <button type="button" disabled={!canCreate} onClick={submit}>创建只读子 Agent</button>
          </div>
          <p className="muted compact-text">
            子 Agent 会继承当前任务的项目和知识库上下文，但不会继承高危批准；非 low 风险工具会被运行时拦截。
          </p>
        </div>
      )}
      {!tasks.length ? (
        <Empty text="当前任务暂无子 Agent。" />
      ) : (
        <Table>
          <thead>
            <tr>
              <th>角色</th>
              <th>隔离</th>
              <th>状态</th>
              <th>任务</th>
              <th>更新时间</th>
            </tr>
          </thead>
          <tbody>
            {tasks.map((task) => (
              <tr key={task.id}>
                <td>{task.metadata?.['agent.role'] || 'subagent'}</td>
                <td><span className="pill neutral">{task.metadata?.['agent.isolation'] || '-'}</span></td>
                <td><span className={`pill ${task.status === 'COMPLETED' ? 'success' : task.status === 'FAILED' ? 'danger' : 'warning'}`}>{statusText(task.status) || task.status || '-'}</span></td>
                <td>
                  <button type="button" className="link-button mono" onClick={() => onViewTask?.(task)}>
                    {short(task.id, 18)}
                  </button>
                  <small className="muted block">{short(task.input, 80)}</small>
                </td>
                <td>{formatDateTime(task.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </Table>
      )}
    </section>
  );
}

function TaskAuditPanel({ audit }: { audit?: TaskAuditView }) {
  if (!audit) return <Empty text="暂无任务审计数据。" />;
  const summary = audit.summary || {};
  return (
    <section className="stack task-audit">
      <div className="knowledge-summary">
        <div className="knowledge-stat"><Wrench size={18} /><span>工具调用</span><strong>{summary.toolCalls || 0}</strong></div>
        <div className="knowledge-stat"><ShieldCheck size={18} /><span>审批</span><strong>{summary.approvalRequests || 0}</strong></div>
        <div className="knowledge-stat"><FileText size={18} /><span>文件变更</span><strong>{summary.fileChanges || 0}</strong></div>
        <div className="knowledge-stat"><ScrollText size={18} /><span>命令</span><strong>{summary.commands || 0}</strong></div>
        <div className="knowledge-stat"><ShieldCheck size={18} /><span>安全提示</span><strong>{summary.securityWarnings || 0}</strong></div>
        <div className="knowledge-stat"><XCircle size={18} /><span>失败</span><strong>{(summary.failedToolCalls || 0) + (summary.failedCommands || 0)}</strong></div>
      </div>
      {audit.resume?.resumed && (
        <Panel title="恢复执行">
          <div className="summary-list">
            <div><span>来源任务</span><strong className="mono">{audit.resume.resumeFromTaskId || '-'}</strong></div>
            <div><span>来源状态</span><strong>{statusText(audit.resume.resumeFromStatus) || audit.resume.resumeFromStatus || '-'}</strong></div>
            <div><span>恢复 Todo</span><strong>{audit.resume.todoOrder ? `#${audit.resume.todoOrder} ` : ''}{audit.resume.todoTitle || '-'}</strong></div>
            <div><span>Todo 状态</span><strong>{todoStatusText(audit.resume.todoStatus) || audit.resume.todoStatus || '-'}</strong></div>
            <div><span>恢复模式</span><strong>{resumeModeText(audit.resume.resumeMode)}</strong></div>
            <div><span>请求时间</span><strong>{formatDateTime(audit.resume.requestedAt)}</strong></div>
            {audit.resume.resumeInstruction && <div><span>恢复策略</span><strong>{audit.resume.resumeInstruction}</strong></div>}
            {audit.resume.checkpoint && (
              <div className="summary-wide">
                <span>Checkpoint</span>
                <pre>{audit.resume.checkpoint}</pre>
              </div>
            )}
          </div>
        </Panel>
      )}
      <Panel title="工具调用">
        {!audit.tools?.length ? <Empty text="暂无工具调用" /> : (
          <Table>
            <thead><tr><th>状态</th><th>工具</th><th>风险</th><th>Todo</th><th>耗时</th><th>时间</th></tr></thead>
            <tbody>
              {audit.tools.map((tool, index) => (
                <tr key={`${tool.stepId}-${index}`}>
                  <td><span className={`pill ${auditToolPill(tool.status)}`}>{auditToolStatusText(tool.status)}</span></td>
                  <td className="mono" title={tool.toolId}>{short(tool.toolId, 52)}</td>
                  <td><span className={`pill ${riskClass(tool.riskLevel)}`}>{tool.riskLevel || '-'}</span></td>
                  <td title={tool.todoTitle}>{short(tool.todoTitle, 40) || '-'}</td>
                  <td>{tool.elapsedMs == null ? '-' : `${tool.elapsedMs}ms`}</td>
                  <td>{formatDateTime(tool.startedAt)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      <div className="two-col">
        <Panel title="审批记录">
          {!audit.approvals?.length ? <Empty text="暂无审批记录" /> : (
            <Table>
              <thead><tr><th>状态</th><th>工具</th><th>原因</th><th>请求时间</th><th>处理时间</th></tr></thead>
              <tbody>
                {audit.approvals.map((approval, index) => (
                  <tr key={`${approval.stepId}-${index}`}>
                    <td><span className={`pill ${approval.status === 'granted' ? 'success' : approval.status === 'rejected' ? 'danger' : 'warning'}`}>
                      {approval.status === 'granted' ? '已批准' : approval.status === 'rejected' ? '已拒绝' : '待审批'}
                    </span></td>
                    <td className="mono" title={approval.toolId}>{short(approval.toolId, 42)}</td>
                    <td title={approval.reason}>{short(approval.reason, 70) || '-'}</td>
                    <td>{formatDateTime(approval.requestedAt)}</td>
                    <td>{formatDateTime(approval.approvedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>
        <Panel title="文件变更">
          {!audit.fileChanges?.length ? <Empty text="暂无文件变更" /> : (
            <Table>
              <thead><tr><th>类型</th><th>状态</th><th>文件</th><th>来源</th></tr></thead>
              <tbody>
                {audit.fileChanges.map((change) => (
                  <tr key={change.id}>
                    <td><span className="pill neutral">{changeTypeText(change.changeType)}</span></td>
                    <td><span className={`pill ${fileReviewStatusClass(change)}`}>{fileReviewStatusText(change)}</span></td>
                    <td className="mono" title={change.path}>{short(change.path, 58)}</td>
                    <td title={fileChangeSourceText(change)}>{short(fileChangeSourceText(change), 34) || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>
      </div>
      <Panel title="命令输出">
        {!audit.commands?.length ? <Empty text="暂无命令记录" /> : (
          <Table>
            <thead><tr><th>状态</th><th>命令</th><th>目录</th><th>退出码</th><th>输出预览</th></tr></thead>
            <tbody>
              {audit.commands.map((command, index) => (
                <tr key={`${command.stepId}-${index}`}>
                  <td><span className={`pill ${command.status === 'failed' || (command.exitCode != null && command.exitCode !== 0) ? 'danger' : 'success'}`}>{command.status === 'failed' ? '失败' : '完成'}</span></td>
                  <td className="mono" title={command.command}>{short(command.command, 60)}</td>
                  <td className="mono" title={command.cwd}>{short(command.cwd, 42)}</td>
                  <td>{command.exitCode ?? '-'}</td>
                  <td title={command.outputPreview}>{short(command.outputPreview, 90) || '-'}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      <Panel title="审计时间线">
        {!audit.timeline?.length ? <Empty text="暂无审计时间线" /> : (
          <Table>
            <thead><tr><th>时间</th><th>级别</th><th>类型</th><th>工具</th><th>消息</th></tr></thead>
            <tbody>
              {audit.timeline.slice(0, 120).map((item) => (
                <tr key={item.id}>
                  <td>{formatDateTime(item.createdAt)}</td>
                  <td>{item.level || '-'}</td>
                  <td className="mono">{short(item.type, 34)}</td>
                  <td className="mono" title={item.toolId}>{short(item.toolId, 34) || '-'}</td>
                  <td title={item.message}>{short(item.message, 96)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
    </section>
  );
}

function DevelopmentSummaryPanel({ summary }: { summary?: DevelopmentTaskSummary }) {
  const [copiedVerificationKey, setCopiedVerificationKey] = useState<string>();
  const [copiedFailureKey, setCopiedFailureKey] = useState<string>();
  const copyVerificationPrompt = useCallback(async (item: VerificationCommandView, index: number) => {
    const prompt = `请在项目目录 ${item.cwd || '当前项目'} 执行验证命令：${item.command}。如果失败，请分析错误并继续修复；如果成功，请总结验证结果。`;
    await navigator.clipboard.writeText(prompt);
    const key = `${item.command}-${item.cwd}-${index}`;
    setCopiedVerificationKey(key);
    window.setTimeout(() => setCopiedVerificationKey((current) => (current === key ? undefined : current)), 1800);
  }, []);
  const copyFailurePrompt = useCallback(async (item: FailureAnalysisView, index: number) => {
    const prompt = [
      `请继续修复这个开发任务中的失败：${item.summary || item.category || '未知失败'}。`,
      item.cwd ? `项目目录：${item.cwd}` : '',
      item.command ? `失败命令：${item.command}` : '',
      item.nextAction ? `建议下一步：${item.nextAction}` : '',
      item.evidence ? `失败证据：${item.evidence}` : '',
      '修复后请重新运行相关验证命令，并总结变更和剩余风险。',
    ].filter(Boolean).join('\n');
    await navigator.clipboard.writeText(prompt);
    const key = `${item.category}-${item.command}-${index}`;
    setCopiedFailureKey(key);
    window.setTimeout(() => setCopiedFailureKey((current) => (current === key ? undefined : current)), 1800);
  }, []);
  if (!summary) return <Empty text="暂无开发任务摘要。" />;
  return (
    <section className="stack development-summary">
      <div className="knowledge-summary">
        <div className="knowledge-stat"><FileText size={18} /><span>文件变更</span><strong>{summary.fileChanges?.length || 0}</strong></div>
        <div className="knowledge-stat"><Wrench size={18} /><span>命令</span><strong>{summary.commands?.length || 0}</strong></div>
        <div className="knowledge-stat"><CheckCircle size={18} /><span>测试</span><strong>{summary.tests?.length || 0}</strong></div>
        <div className="knowledge-stat"><Monitor size={18} /><span>进程</span><strong>{summary.processes?.length || 0}</strong></div>
        <div className="knowledge-stat"><XCircle size={18} /><span>失败</span><strong>{summary.failures?.length || 0}</strong></div>
      </div>
      <div className="two-col">
        <Panel title="最终结果">
          {!summary.finalResult ? <Empty text="暂无最终结果" /> : (
            <div className="summary-list">
              <div className={`notice ${finalOutcomeClass(summary.finalResult.outcome)}`}>
                <strong>{finalOutcomeText(summary.finalResult.outcome)}</strong>
                <span>{summary.finalResult.summary}</span>
              </div>
              <div className="command-chip-list">
                <span className={`pill ${finalOutcomeClass(summary.finalResult.outcome)}`}>结果 {finalOutcomeText(summary.finalResult.outcome)}</span>
                <span className={`pill ${summary.finalResult.verificationStatus === 'passed' ? 'success' : summary.finalResult.verificationStatus === 'failed' ? 'danger' : 'warning'}`}>
                  验证 {verificationStatusText(summary.finalResult.verificationStatus)}
                </span>
                <span className={`pill ${summary.finalResult.readyForCommit ? 'success' : 'neutral'}`}>
                  {summary.finalResult.readyForCommit ? '可提交' : '暂不提交'}
                </span>
              </div>
              {summary.finalResult.nextActions?.length ? (
                <ul className="compact-list">
                  {summary.finalResult.nextActions.map((action) => <li key={action}>{action}</li>)}
                </ul>
              ) : null}
            </div>
          )}
        </Panel>
        <Panel title="Git 审查">
          {!summary.gitReview ? <Empty text="暂无 Git 审查信息" /> : (
            <div className="summary-list">
              <Table>
                <thead><tr><th>项目</th><th>状态</th><th>命令</th><th>退出码</th></tr></thead>
                <tbody>
                  <tr>
                    <td>状态</td>
                    <td><span className={`pill ${summary.gitReview.statusAlreadyRun ? 'success' : 'neutral'}`}>{summary.gitReview.statusAlreadyRun ? '已运行' : '建议'}</span></td>
                    <td className="mono" title={summary.gitReview.statusCommand}>{short(summary.gitReview.statusCommand, 52)}</td>
                    <td>{summary.gitReview.statusExitCode ?? '-'}</td>
                  </tr>
                  <tr>
                    <td>Diff</td>
                    <td><span className={`pill ${summary.gitReview.diffAlreadyRun ? 'success' : 'neutral'}`}>{summary.gitReview.diffAlreadyRun ? '已运行' : '建议'}</span></td>
                    <td className="mono" title={summary.gitReview.diffCommand}>{short(summary.gitReview.diffCommand, 52)}</td>
                    <td>{summary.gitReview.diffExitCode ?? '-'}</td>
                  </tr>
                </tbody>
              </Table>
              <div className="notice neutral">{summary.gitReview.nextAction}</div>
              {(summary.gitReview.statusOutputPreview || summary.gitReview.diffOutputPreview) ? (
                <pre className="json-block">{[summary.gitReview.statusOutputPreview, summary.gitReview.diffOutputPreview].filter(Boolean).join('\n\n')}</pre>
              ) : null}
            </div>
          )}
        </Panel>
      </div>
      <div className="two-col">
        <Panel title="文件变更">
          {!summary.fileChanges?.length ? <Empty text="暂无文件变更" /> : (
            <Table>
              <thead><tr><th>类型</th><th>状态</th><th>文件</th><th>来源</th><th>增删</th></tr></thead>
              <tbody>
                {summary.fileChanges.map((change) => (
                  <tr key={change.id}>
                    <td><span className="pill neutral">{changeTypeText(change.changeType)}</span></td>
                    <td><span className={`pill ${fileReviewStatusClass(change)}`}>{fileReviewStatusText(change)}</span></td>
                    <td className="mono" title={change.path}>{short(change.path, 72)}</td>
                    <td title={fileChangeSourceText(change)}>{short(fileChangeSourceText(change), 32) || '-'}</td>
                    <td><span className="pill success">+{change.addedLines || 0}</span> <span className="pill danger">-{change.deletedLines || 0}</span></td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Panel>
        <Panel title="验证计划">
          {summary.verificationPlan?.length ? (
            <Table>
              <thead><tr><th>状态</th><th>命令</th><th>目录</th><th>来源</th><th>操作</th></tr></thead>
              <tbody>
                {summary.verificationPlan.map((item, index) => (
                  <tr key={`${item.command}-${item.cwd}-${index}`}>
                    <td>
                      <span className={`pill ${item.alreadyRun ? (item.lastStatus === 'failed' ? 'danger' : 'success') : 'neutral'}`}>
                        {item.alreadyRun ? (item.lastStatus === 'failed' ? '失败' : '已执行') : '待执行'}
                      </span>
                    </td>
                    <td className="mono" title={item.command}>{short(item.command, 54)}</td>
                    <td className="mono" title={item.cwd}>{short(item.cwd, 42) || '-'}</td>
                    <td title={item.reason}>
                      <span className="pill neutral">{verificationSourceText(item.source)}</span>
                    </td>
                    <td>
                      <button
                        className="icon-button compact"
                        title="复制为可发送给 Agent 的验证请求"
                        type="button"
                        onClick={() => void copyVerificationPrompt(item, index)}
                      >
                        {copiedVerificationKey === `${item.command}-${item.cwd}-${index}` ? <Check size={14} /> : <Copy size={14} />}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ) : !summary.testCommandSuggestions?.length ? <Empty text="未检测到默认测试命令。" /> : (
            <div className="command-chip-list">
              {summary.testCommandSuggestions.map((command) => <span className="pill neutral mono" key={command}>{command}</span>)}
            </div>
          )}
        </Panel>
      </div>
      <Panel title="后台进程">
        {!summary.processes?.length ? <Empty text="本任务暂无后台进程记录。" /> : (
          <Table>
            <thead><tr><th>状态</th><th>PID</th><th>命令</th><th>端口</th><th>健康</th><th>诊断</th><th>项目</th></tr></thead>
            <tbody>
              {summary.processes.map((process) => (
                <tr key={process.pid}>
                  <td>
                    <span className={`pill ${process.status === 'running' ? 'success' : 'neutral'}`}>
                      {process.status === 'running' ? '运行中' : '已退出'}
                    </span>
                  </td>
                  <td className="mono">{process.pid}</td>
                  <td className="mono" title={process.commandLine}>{short(process.commandLine, 72)}</td>
                  <td>{process.ports?.length ? process.ports.map((port) => (
                    <span className={`pill ${process.portStatus?.[String(port)] ? 'success' : 'neutral'}`} key={port}>
                      {port}{process.portStatus?.[String(port)] ? ' listening' : ''}
                    </span>
                  )) : '-'}</td>
                  <td title={process.health?.url || process.health?.message || ''}>
                    {process.health ? (
                      <span className={`pill ${processHealthPill(process.health.status)}`}>
                        {processHealthText(process.health.status)}{process.health.httpStatus ? ` ${process.health.httpStatus}` : ''}
                      </span>
                    ) : '-'}
                  </td>
                  <td title={process.diagnosis?.nextAction || process.diagnosis?.summary || ''}>
                    {process.diagnosis ? (
                      <div className="process-diagnosis-cell">
                        <span className={`pill ${processDiagnosisPill(process.diagnosis.status)}`}>
                          {processDiagnosisText(process.diagnosis.status)}
                        </span>
                        <span>{short(process.diagnosis.summary, 72)}</span>
                      </div>
                    ) : '-'}
                  </td>
                  <td className="mono" title={process.projectPath || process.cwd}>{short(process.projectPath || process.cwd, 58)}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      <Panel title="命令与测试">
        {!summary.commands?.length ? <Empty text="暂无命令记录" /> : (
          <Table>
            <thead><tr><th>状态</th><th>命令</th><th>目录</th><th>退出码</th><th>风险</th><th>耗时</th></tr></thead>
            <tbody>
              {summary.commands.map((command, index) => (
                <tr key={`${command.stepId}-${index}`}>
                  <td><span className={`pill ${command.status === 'failed' ? 'danger' : 'success'}`}>{command.status === 'failed' ? '失败' : '成功'}</span></td>
                  <td className="mono" title={command.command}>{short(command.command, 76)}</td>
                  <td className="mono" title={command.cwd}>{short(command.cwd, 54)}</td>
                  <td>{command.exitCode ?? '-'}</td>
                  <td><span className={`pill ${riskClass(command.riskLevel)}`}>{command.riskLevel || '-'}</span></td>
                  <td>{command.elapsedMs == null ? '-' : `${command.elapsedMs}ms`}</td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Panel>
      <div className="two-col">
        <Panel title="失败与风险">
          {(!summary.failureAnalyses?.length && !summary.failures?.length && !summary.risks?.length) ? <Empty text="暂无失败或风险记录" /> : (
            <div className="summary-list">
              {summary.failureAnalyses?.length ? (
                <Table>
                  <thead><tr><th>类型</th><th>重试</th><th>命令</th><th>下一步</th><th>操作</th></tr></thead>
                  <tbody>
                    {summary.failureAnalyses.map((item, index) => (
                      <tr key={`${item.category}-${item.command}-${index}`}>
                        <td><span className="pill danger">{item.summary || item.category || '失败'}</span></td>
                        <td>
                          <span className={`pill ${item.retryable ? 'warning' : 'neutral'}`}>
                            {item.retryable ? `可重试 ${item.retryLimit || 1} 次` : '先修复'}
                          </span>
                        </td>
                        <td className="mono" title={item.command || item.evidence}>{short(item.command || item.evidence, 48)}</td>
                        <td title={item.nextAction}>{short(item.nextAction, 72)}</td>
                        <td>
                          <button
                            className="icon-button compact"
                            title="复制为可发送给 Agent 的继续修复请求"
                            type="button"
                            onClick={() => void copyFailurePrompt(item, index)}
                          >
                            {copiedFailureKey === `${item.category}-${item.command}-${index}` ? <Check size={14} /> : <Copy size={14} />}
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              ) : null}
              {summary.failures?.map((item) => <div className="notice danger" key={`failure-${item}`}>{item}</div>)}
              {summary.risks?.map((item) => <div className="notice warning" key={`risk-${item}`}>{item}</div>)}
            </div>
          )}
        </Panel>
        <Panel title="Commit Message 草稿">
          <pre className="json-block">{summary.commitMessage || '暂无建议'}</pre>
        </Panel>
      </div>
    </section>
  );
}

function SessionTokenPanel({
  sessionUsage,
  tasks,
  taskUsages,
  cost,
}: {
  sessionUsage?: TokenUsageSummary;
  tasks: AgentTask[];
  taskUsages: Record<string, TokenUsageSummary>;
  cost?: CostConfigView;
}) {
  const usage = sessionUsage || emptyTokenUsage();
  const estimatedCost = estimateUsageCost(usage, cost);
  const currency = cost?.currency || 'USD';
  return (
    <section className="stack token-panel">
      <div className="metric-grid">
        <Metric title="LLM 调用" value={formatTokenCount(usage.callCount)} desc="当前会话累计" />
        <Metric title="Prompt Tokens" value={formatTokenCount(usage.promptTokens)} desc="输入消耗" />
        <Metric title="Completion Tokens" value={formatTokenCount(usage.completionTokens)} desc="输出消耗" />
        <Metric title="Total Tokens" value={formatTokenCount(usage.totalTokens)} desc="会话总消耗" />
        <Metric title="Estimated Cost" value={formatCost(estimatedCost, currency)} desc="按当前成本规则估算" />
      </div>
      <Panel title="每轮对话 Token">
        <TaskTokenTable tasks={tasks} taskUsages={taskUsages} cost={cost} />
      </Panel>
      <div className="two-col">
        <TokenBreakdownTable title="按模型统计" data={usage.byModel} cost={cost} />
        <TokenBreakdownTable title="按阶段统计" data={usage.byPhase} />
      </div>
    </section>
  );
}

function TaskTokenTable({
  tasks,
  taskUsages,
  cost,
}: {
  tasks: AgentTask[];
  taskUsages: Record<string, TokenUsageSummary>;
  cost?: CostConfigView;
}) {
  if (!tasks.length) return <Empty text="暂无任务 Token 统计" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>轮次输入</th>
          <th>调用</th>
          <th>Prompt</th>
          <th>Completion</th>
          <th>Total</th>
          <th>成本</th>
          <th>更新时间</th>
        </tr>
      </thead>
      <tbody>
        {tasks.map((task) => {
          const usage = taskUsages[task.id] || emptyTokenUsage();
          const estimatedCost = estimateUsageCost(usage, cost);
          return (
            <tr key={task.id}>
              <td>{short(task.input, 60)}</td>
              <td>{formatTokenCount(usage.callCount)}</td>
              <td>{formatTokenCount(usage.promptTokens)}</td>
              <td>{formatTokenCount(usage.completionTokens)}</td>
              <td><strong>{formatTokenCount(usage.totalTokens)}</strong></td>
              <td>{formatCost(estimatedCost, cost?.currency || 'USD')}</td>
              <td>{formatDateTime(task.updatedAt)}</td>
            </tr>
          );
        })}
      </tbody>
    </Table>
  );
}

function TokenBreakdownTable({
  title,
  data,
  cost,
}: {
  title: string;
  data?: Record<string, TokenUsageSummary>;
  cost?: CostConfigView;
}) {
  const rows = Object.entries(data || {});
  return (
    <Panel title={title}>
      {!rows.length ? (
        <Empty text="暂无统计" />
      ) : (
        <Table>
          <thead>
            <tr>
              <th>名称</th>
              <th>调用</th>
              <th>Prompt</th>
              <th>Completion</th>
              <th>Total</th>
              {cost && <th>成本</th>}
            </tr>
          </thead>
          <tbody>
            {rows.map(([name, usage]) => {
              const rule = findCostRule(name, cost);
              const currency = rule?.currency || cost?.currency || 'USD';
              return (
                <tr key={name}>
                  <td>{name}</td>
                  <td>{formatTokenCount(usage.callCount)}</td>
                  <td>{formatTokenCount(usage.promptTokens)}</td>
                  <td>{formatTokenCount(usage.completionTokens)}</td>
                  <td><strong>{formatTokenCount(usage.totalTokens)}</strong></td>
                  {cost && <td>{formatCost(estimateTokenCost(usage, rule), currency)}</td>}
                </tr>
              );
            })}
          </tbody>
        </Table>
      )}
    </Panel>
  );
}

function SessionTable({ sessions, selectedId, onSelect }: { sessions: AgentSession[]; selectedId?: string; onSelect: (id: string) => void }) {
  if (!sessions.length) return <Empty text="暂无会话" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>标题</th>
          <th>会话ID</th>
          <th>用户</th>
          <th>更新时间</th>
        </tr>
      </thead>
      <tbody>
        {sessions.map((session) => (
          <tr key={session.id} className={session.id === selectedId ? 'selected' : ''} onClick={() => onSelect(session.id)}>
            <td>{short(session.title || session.summary || '未命名会话', 38)}</td>
            <td className="mono">{session.id}</td>
            <td>{session.userId || '-'}</td>
            <td>{formatDateTime(session.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function TaskStepSearchPanel({
  filter,
  taskResults,
  stepResults,
  loading,
  message,
  onFilterChange,
  onSearch,
  onReset,
  onOpenTask,
}: {
  filter: TaskStepSearchFilter;
  taskResults: AgentTask[];
  stepResults: AgentStep[];
  loading: boolean;
  message?: string;
  onFilterChange: (filter: TaskStepSearchFilter) => void;
  onSearch: () => void;
  onReset: () => void;
  onOpenTask: (taskId?: string) => void;
}) {
  const update = (patch: Partial<TaskStepSearchFilter>) => onFilterChange({ ...filter, ...patch });
  const hasResult = filter.mode === 'steps' ? stepResults.length > 0 : taskResults.length > 0;
  return (
    <div className="task-search-panel">
      <div className="task-search-head">
        <strong>跨任务检索</strong>
        <div className="task-search-actions">
          <div className="segmented-tabs compact-tabs">
            <button className={filter.mode === 'tasks' ? 'active' : undefined} type="button" onClick={() => update({ mode: 'tasks' })}>任务</button>
            <button className={filter.mode === 'steps' ? 'active' : undefined} type="button" onClick={() => update({ mode: 'steps' })}>步骤</button>
          </div>
          <button className="ghost-button compact-button" type="button" onClick={onReset}>重置</button>
        </div>
      </div>
      <div className="task-search-form">
        <input className="task-search-query" value={filter.query} onChange={(event) => update({ query: event.target.value })} placeholder={filter.mode === 'steps' ? '搜索工具名、输入、输出或错误' : '搜索任务输入、答案、metadata'} />
        <input value={filter.status} onChange={(event) => update({ status: event.target.value })} placeholder="状态" />
        {filter.mode === 'tasks' ? (
          <>
            <input value={filter.sessionId} onChange={(event) => update({ sessionId: event.target.value })} placeholder="会话ID" />
            <input value={filter.channelId} onChange={(event) => update({ channelId: event.target.value })} placeholder="渠道" />
            <input value={filter.userId} onChange={(event) => update({ userId: event.target.value })} placeholder="用户" />
          </>
        ) : (
          <>
            <input value={filter.taskId} onChange={(event) => update({ taskId: event.target.value })} placeholder="任务ID" />
            <input value={filter.toolId} onChange={(event) => update({ toolId: event.target.value })} placeholder="工具ID" />
            <select value={filter.riskLevel} onChange={(event) => update({ riskLevel: event.target.value })}>
              <option value="">全部风险</option>
              <option value="low">low</option>
              <option value="medium">medium</option>
              <option value="high">high</option>
              <option value="unknown">unknown</option>
            </select>
          </>
        )}
        <input className="task-search-limit" type="number" min={1} max={500} value={filter.limit} onChange={(event) => update({ limit: Number(event.target.value) || 100 })} aria-label="检索数量" />
        <button className="task-search-submit" type="button" disabled={loading} onClick={onSearch}>
          <Search size={14} />{loading ? '检索中' : '检索'}
        </button>
      </div>
      {message && <div className="config-message">{message}</div>}
      {hasResult && (
        <div className="task-search-results">
          {filter.mode === 'steps' ? (
            <>
              <StepSearchSummary steps={stepResults} />
              <StepSearchResults steps={stepResults} query={filter.query} onOpenTask={onOpenTask} />
            </>
          ) : (
            <>
              <TaskSearchSummary tasks={taskResults} />
              <TaskSearchResults tasks={taskResults} query={filter.query} onOpenTask={onOpenTask} />
            </>
          )}
        </div>
      )}
    </div>
  );
}

function TaskSearchSummary({ tasks }: { tasks: AgentTask[] }) {
  const statusCounts = countBy(tasks, (task) => task.status || 'unknown');
  const sessionCount = new Set(tasks.map((task) => task.sessionId).filter(Boolean)).size;
  const failed = statusCounts.FAILED || statusCounts.failed || 0;
  const completed = statusCounts.COMPLETED || statusCounts.completed || 0;
  const running = statusCounts.RUNNING || statusCounts.running || 0;
  return (
    <div className="task-search-summary" aria-label="任务检索聚合摘要">
      <span>任务 {tasks.length}</span>
      <span>失败 {failed}</span>
      <span>完成 {completed}</span>
      <span>运行中 {running}</span>
      <span>会话 {sessionCount}</span>
      <span>最多状态 {topCountLabel(statusCounts)}</span>
    </div>
  );
}

function TaskSearchResults({ tasks, query, onOpenTask }: { tasks: AgentTask[]; query: string; onOpenTask: (taskId?: string) => void }) {
  return (
    <Table>
      <thead><tr><th>状态</th><th>输入</th><th>会话</th><th>用户</th><th>更新时间</th></tr></thead>
      <tbody>
        {tasks.map((task) => (
          <tr key={task.id} onClick={() => onOpenTask(task.id)}>
            <td><span className={`pill ${task.status === 'FAILED' ? 'danger' : task.status === 'COMPLETED' ? 'success' : 'neutral'}`}>{task.status || '-'}</span></td>
            <td title={task.input}>{highlightSearchText(searchPreview(task.input, query, 56), query)}</td>
            <td className="mono" title={task.sessionId}>{highlightSearchText(searchPreview(task.sessionId, query, 28), query)}</td>
            <td>{highlightSearchText(searchPreview(task.userId, query, 24), query)}</td>
            <td>{formatDateTime(task.updatedAt)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function StepSearchSummary({ steps }: { steps: AgentStep[] }) {
  const statusCounts = countBy(steps, (step) => step.status || 'unknown');
  const riskCounts = countBy(steps, (step) => step.input?.riskLevel || 'unknown');
  const toolCounts = countBy(steps, (step) => step.name || step.type || 'unknown');
  const taskCount = new Set(steps.map((step) => step.taskId).filter(Boolean)).size;
  return (
    <div className="task-search-summary" aria-label="步骤检索聚合摘要">
      <span>步骤 {steps.length}</span>
      <span>失败 {(statusCounts.FAILED || statusCounts.failed || 0)}</span>
      <span>高危 {(riskCounts.high || riskCounts.HIGH || 0)}</span>
      <span>中危 {(riskCounts.medium || riskCounts.MEDIUM || 0)}</span>
      <span>任务 {taskCount}</span>
      <span>最多工具 {topCountLabel(toolCounts)}</span>
    </div>
  );
}

function StepSearchResults({ steps, query, onOpenTask }: { steps: AgentStep[]; query: string; onOpenTask: (taskId?: string) => void }) {
  return (
    <Table>
      <thead><tr><th>状态</th><th>步骤</th><th>风险</th><th>任务</th><th>输出/错误</th><th>完成时间</th></tr></thead>
      <tbody>
        {steps.map((step) => (
          <tr key={step.id} onClick={() => onOpenTask(step.taskId)}>
            <td><span className={`pill ${step.status === 'FAILED' ? 'danger' : step.status === 'COMPLETED' ? 'success' : 'neutral'}`}>{step.status || '-'}</span></td>
            <td>{highlightSearchText(searchPreview(step.name || step.type, query, 44), query)}</td>
            <td><span className={`pill ${riskClass(step.input?.riskLevel)}`}>{step.input?.riskLevel || '-'}</span></td>
            <td className="mono" title={step.taskId}>{highlightSearchText(searchPreview(step.taskId, query, 28), query)}</td>
            <td title={step.error || step.output}>{highlightSearchText(searchPreview(step.error || step.output, query, 64), query)}</td>
            <td>{formatDateTime(step.finishedAt || step.startedAt)}</td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function TaskTable({
  tasks,
  selectedTaskId,
  onViewTask,
}: {
  tasks: AgentTask[];
  selectedTaskId?: string;
  onViewTask?: (task: AgentTask) => void;
}) {
  if (!tasks.length) return <Empty text="暂无任务" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>输入</th>
          <th>状态</th>
          <th>任务ID</th>
          <th>更新时间</th>
          <th>结果</th>
        </tr>
      </thead>
      <tbody>
        {tasks.map((task) => (
          <tr
            key={task.id}
            className={`clickable-row ${task.id === selectedTaskId ? 'selected-row' : ''}`}
            onClick={() => onViewTask?.(task)}
          >
            <td>{short(task.input, 70)}</td>
            <td><span className={`pill ${task.status === 'FAILED' ? 'danger' : task.status === 'COMPLETED' ? 'success' : 'warning'}`}>{statusText(task.status) || '-'}</span></td>
            <td className="mono">{task.id}</td>
            <td>{formatDateTime(task.updatedAt)}</td>
            <td>
              <button
                type="button"
                className="link-button"
                title="查看任务结果"
                onClick={(event) => {
                  event.stopPropagation();
                  onViewTask?.(task);
                }}
              >
                查看
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function MessageChatView({ messages, compact = false }: { messages: AgentMessage[]; compact?: boolean }) {
  if (!messages.length) return <Empty text="暂无消息" />;
  return (
    <div className={`history-chat ${compact ? 'compact' : ''}`}>
      {messages.map((message) => (
        <ChatBubble
          key={message.id}
          message={agentMessageToChatMessage(message)}
          onApproveTool={() => undefined}
          onRejectTool={() => undefined}
          onToggleTools={() => undefined}
          onConfirmProjectDirectory={() => undefined}
        />
      ))}
    </div>
  );
}

function agentMessageToChatMessage(message: AgentMessage): ChatMessage {
  const role = message.role === 'user' ? 'user' : 'assistant';
  return {
    id: message.id,
    role,
    content: message.content || '',
    taskId: message.taskId,
    status: role === 'assistant' ? '已完成' : undefined,
    createdAt: message.createdAt ? new Date(message.createdAt).getTime() : Date.now(),
    attachments: attachmentsFromMessageMetadata(message.metadata),
    toolCalls: [],
    toolsCollapsed: true,
  };
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

function planToChatMessage(plan: PlanDraft): ChatMessage {
  const createdAt = Date.parse(plan.createdAt || plan.updatedAt || '') || Date.now();
  return {
    id: `plan-${plan.id}-${plan.version || 1}`,
    role: 'assistant',
    content: '',
    planId: plan.id,
    plan,
    status: planStatusText(plan.status),
    createdAt,
    finishedAt: Date.parse(plan.updatedAt || '') || createdAt,
    toolCalls: [],
    toolsCollapsed: true,
  };
}

function activePlansForChat(plans: PlanDraft[]) {
  const activePlans = plans
    .filter((plan) => (plan.status || 'DRAFT').toUpperCase() !== 'DONE')
    .sort((left, right) => (
      (Date.parse(right.updatedAt || right.createdAt || '') || 0)
      - (Date.parse(left.updatedAt || left.createdAt || '') || 0)
    ));
  // 聊天流里只恢复最近一个未结束计划；完整历史仍由后端 Plan API 保留，避免旧草稿反复占屏。
  return activePlans.slice(0, 1);
}

function mergePlanMessages(messages: ChatMessage[], plans: PlanDraft[]) {
  const planMessages = new Map<string, ChatMessage>();
  activePlansForChat(plans).forEach((plan) => {
    if (!plan.id) return;
    planMessages.set(plan.id, planToChatMessage(plan));
  });
  const withoutOldPlans = messages.filter((message) => !message.planId || !planMessages.has(message.planId));
  return [...withoutOldPlans, ...Array.from(planMessages.values())].sort((left, right) => left.createdAt - right.createdAt);
}

function EventTable({ events }: { events: AgentEvent[] }) {
  const [selectedEvent, setSelectedEvent] = useState<AgentEvent>();
  if (!events.length) return <Empty text="暂无日志" />;
  return (
    <section className="stack">
      <Table>
        <thead>
          <tr>
            <th>级别</th>
            <th>类型</th>
            <th>消息</th>
            <th>时间</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => (
            <tr
              key={event.id}
              className={`clickable-row ${event.id === selectedEvent?.id ? 'selected-row' : ''}`}
              onClick={() => setSelectedEvent(event)}
            >
              <td>{event.level || '-'}</td>
              <td>{event.type || '-'}</td>
              <td>{short(event.message, 100)}</td>
              <td>{formatDateTime(event.createdAt)}</td>
              <td>
                <button
                  type="button"
                  className="link-button"
                  title="查看日志详情"
                  onClick={(clickEvent) => {
                    clickEvent.stopPropagation();
                    setSelectedEvent(event);
                  }}
                >
                  查看
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </Table>
      {selectedEvent && (
        <Panel title="日志详情" action={<button onClick={() => setSelectedEvent(undefined)}>关闭</button>}>
          <div className="event-detail">
            <div className="session-summary">
              <span>日志ID：<span className="mono">{selectedEvent.id}</span></span>
              <span>任务ID：<span className="mono">{selectedEvent.taskId || '-'}</span></span>
              <span>类型：{selectedEvent.type || '-'}</span>
              <span>级别：{selectedEvent.level || '-'}</span>
              <span>时间：{formatDateTime(selectedEvent.createdAt)}</span>
            </div>
            <div className="event-message">{selectedEvent.message || '无消息内容'}</div>
            {selectedEvent.details && Object.keys(selectedEvent.details).length > 0 && <JsonBlock data={selectedEvent.details} />}
          </div>
        </Panel>
      )}
    </section>
  );
}

function ChannelPage({
  channels,
  adapters,
  draft,
  loading,
  saving,
  adapterReloading,
  adapterUploading,
  adapterDeleting,
  message,
  testText,
  testResult,
  outboundConversationId,
  outboundText,
  outboundResult,
  userBindings,
  users,
  bindingExternalUserId,
  bindingExternalUsername,
  bindingLocalUserId,
  bindingLoading,
  health,
  streamStatus,
  onRefresh,
  onReloadAdapters,
  onUploadAdapter,
  onDeleteAdapter,
  onSelect,
  onNew,
  onDraftChange,
  onSave,
  onDelete,
  onTestTextChange,
  onSubmitTest,
  onOutboundConversationIdChange,
  onOutboundTextChange,
  onSubmitOutboundTest,
  onCheckHealth,
  onRefreshStream,
  onStartStream,
  onStopStream,
  onRefreshUserBindings,
  onBindingExternalUserIdChange,
  onBindingExternalUsernameChange,
  onBindingLocalUserIdChange,
  onBindChannelUser,
  onUnbindChannelUser,
}: {
  channels: ChannelDefinition[];
  adapters: ChannelAdapterDescriptor[];
  draft: ChannelDefinition;
  loading: boolean;
  saving: boolean;
  adapterReloading: boolean;
  adapterUploading: boolean;
  adapterDeleting?: string;
  message?: string;
  testText: string;
  testResult?: ChannelInboundResult;
  outboundConversationId: string;
  outboundText: string;
  outboundResult?: ChannelOutboundTestResponse;
  userBindings: ChannelUserBindingView[];
  users: LocalUserView[];
  bindingExternalUserId: string;
  bindingExternalUsername: string;
  bindingLocalUserId: string;
  bindingLoading: boolean;
  health?: ChannelConnectivityStatus;
  streamStatus?: ChannelStreamStatus;
  onRefresh: () => void;
  onReloadAdapters: () => void;
  onUploadAdapter: (file: File) => void;
  onDeleteAdapter: (filename: string) => void;
  onSelect: (channel: ChannelDefinition) => void;
  onNew: () => void;
  onDraftChange: (draft: ChannelDefinition) => void;
  onSave: () => void;
  onDelete: (channelId?: string) => void;
  onTestTextChange: (value: string) => void;
  onSubmitTest: () => void;
  onOutboundConversationIdChange: (value: string) => void;
  onOutboundTextChange: (value: string) => void;
  onSubmitOutboundTest: () => void;
  onCheckHealth: () => void;
  onRefreshStream: () => void;
  onStartStream: () => void;
  onStopStream: () => void;
  onRefreshUserBindings: () => void;
  onBindingExternalUserIdChange: (value: string) => void;
  onBindingExternalUsernameChange: (value: string) => void;
  onBindingLocalUserIdChange: (value: string) => void;
  onBindChannelUser: () => void;
  onUnbindChannelUser: (externalUserId: string) => void;
}) {
  const adapterFileInputRef = useRef<HTMLInputElement>(null);
  const update = (patch: Partial<ChannelDefinition>) => onDraftChange({ ...draft, ...patch });
  const metadata = draft.metadata || {};
  const channelType = (draft.type || draft.id || '').toLowerCase();
  const yamlManaged = metadata['channel.source'] === 'yaml' || metadata['channel.readOnly'] === 'true';
  const outboundTargetLabel = channelType === 'ddio'
    ? 'DDIO 接收目标 receTargetID'
    : channelType === 'dingtalk'
      ? '外部会话'
      : '外部会话 / receive_id';
  const outboundTargetPlaceholder = channelType === 'ddio'
    ? 'DDIO receTargetID，个人或群目标 ID'
    : channelType === 'dingtalk'
      ? '钉钉 webhook 可留空'
      : '飞书 chat_id / receive_id';
  const outboundHelpText = channelType === 'ddio'
    ? '出站测试会直接调用当前通道的平台回写能力。DDIO 必须填写 receTargetID；如需发群消息，可在 Metadata 中设置 channel.ddio.chatScene=group。'
    : channelType === 'dingtalk'
      ? '出站测试会直接调用当前通道的平台回写能力。钉钉自定义机器人使用 webhook，不需要会话 ID。'
      : '出站测试会直接调用当前通道的平台回写能力。飞书需要填接收会话 ID；钉钉使用 webhook，不需要会话 ID。';
  const updateMetadata = (key: string, value: string) => onDraftChange({ ...draft, metadata: upsertMetadata(metadata, key, value) });
  const selectedId = draft.id;
  const enabledCount = channels.filter((channel) => channel.enabled).length;
  const builtinAdapterCount = channels.filter((channel) => channel.metadata?.adapter === 'builtin').length;
  const activeAdapterCount = adapters.filter((adapter) => adapter.active).length;
  const activeUsers = users.filter((user) => user.status !== 'disabled');
  const adapterJarName = (adapter: ChannelAdapterDescriptor) => {
    const location = adapter.location || '';
    if (!location.toLowerCase().endsWith('.jar')) return '';
    try {
      const pathname = new URL(location).pathname;
      return decodeURIComponent(pathname.split(/[\\/]/).filter(Boolean).pop() || '');
    } catch {
      return decodeURIComponent(location.split(/[\\/]/).filter(Boolean).pop() || '');
    }
  };

  return (
    <section className="stack">
      <div className="metric-grid compact-metrics">
        <Metric title="通道" value={channels.length} desc="已加载通道" />
        <Metric title="已启用" value={enabledCount} desc="可接收入站任务" />
        <Metric title="Adapter" value={activeAdapterCount || builtinAdapterCount} desc="当前生效实现" />
      </div>
      <Panel
        title="Adapter 运行时"
        action={(
          <div className="button-row">
            <input
              ref={adapterFileInputRef}
              type="file"
              accept=".jar"
              hidden
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (file) onUploadAdapter(file);
                event.target.value = '';
              }}
            />
            <button type="button" onClick={() => adapterFileInputRef.current?.click()} disabled={adapterUploading}>
              <Upload size={14} />{adapterUploading ? '导入中' : '导入 jar'}
            </button>
            <button type="button" onClick={onReloadAdapters} disabled={adapterReloading}>
              <RefreshCw size={14} />{adapterReloading ? '扫描中' : '重新扫描'}
            </button>
          </div>
        )}
      >
        {adapters.length ? (
          <div className="channel-list compact-list">
            {adapters.map((adapter, index) => (
              (() => {
                const jarName = adapterJarName(adapter);
                return (
                  <div className="channel-row passive" key={`${adapter.type}-${adapter.className}-${index}`}>
                    <span>
                      <strong>{adapter.type}</strong>
                      <small className="mono">{adapter.className || '-'}</small>
                    </span>
                    <span className="channel-row-meta">
                      <em className={`pill ${adapter.active ? 'success' : 'neutral'}`}>{adapter.active ? '生效' : '被覆盖'}</em>
                      <em className="pill neutral">{adapter.source || '-'}</em>
                      {adapter.location && <em className="pill neutral">location</em>}
                      {jarName && (
                        <button
                          type="button"
                          className="link-button danger-text"
                          disabled={adapterDeleting === jarName}
                          onClick={() => onDeleteAdapter(jarName)}
                        >
                          {adapterDeleting === jarName ? '删除中' : '删除'}
                        </button>
                      )}
                    </span>
                  </div>
                );
              })()
            ))}
          </div>
        ) : (
          <Empty text={loading ? '正在加载 Adapter...' : '暂无 Adapter 诊断信息'} />
        )}
      </Panel>
      <div className="two-col channel-layout">
        <Panel
          title="通道列表"
          action={<button type="button" onClick={onRefresh} disabled={loading}><RefreshCw size={14} />刷新</button>}
        >
          {channels.length ? (
            <div className="channel-list">
              {channels.map((channel) => (
                <button
                  type="button"
                  key={channel.id}
                  className={`channel-row ${selectedId === channel.id ? 'active' : ''}`}
                  onClick={() => onSelect(channel)}
                >
                  <span>
                    <strong>{channel.name || channel.id}</strong>
                    <small className="mono">{channel.id}</small>
                  </span>
                  <span className="channel-row-meta">
                    <em className="pill neutral">{channel.type || '-'}</em>
                    {channel.metadata?.['channel.accountId'] && <em className="pill neutral">账号:{channel.metadata['channel.accountId']}</em>}
                    {channel.metadata?.['channel.isDefaultAccount'] === 'true' && <em className="pill success">默认账号</em>}
                    <em className={`pill ${channel.enabled ? 'success' : 'neutral'}`}>{channel.enabled ? '启用' : '停用'}</em>
                    {channel.metadata?.builtin === 'true' && <em className="pill warning">内置</em>}
                    {(channel.metadata?.['channel.source'] === 'yaml' || channel.metadata?.['channel.readOnly'] === 'true') && <em className="pill warning">YAML</em>}
                    {channel.metadata?.adapter === 'builtin' && <em className="pill neutral">builtin</em>}
                  </span>
                </button>
              ))}
            </div>
          ) : (
            <Empty text={loading ? '正在加载通道...' : '暂无通道'} />
          )}
        </Panel>
        <Panel
          title="通道配置"
          action={(
            <div className="button-row">
              <button type="button" onClick={onNew}><Plus size={14} />新建</button>
              <button type="button" onClick={onCheckHealth} disabled={saving || !draft.id}><Plug size={14} />检测</button>
              <button type="button" onClick={onRefreshStream} disabled={saving || !draft.id}><RefreshCw size={14} />Stream</button>
              <button type="button" onClick={onStartStream} disabled={saving || !draft.id}><Play size={14} />启动</button>
              <button type="button" onClick={onStopStream} disabled={saving || !draft.id}><Square size={14} />停止</button>
              <button type="button" onClick={() => onDelete(draft.id)} disabled={saving || !draft.id}><Trash2 size={14} />删除覆盖</button>
            </div>
          )}
        >
          <form
            className="config-form"
            onSubmit={(event) => {
              event.preventDefault();
              onSave();
            }}
          >
            <div className="form-grid">
              <label className="form-field">
                <span>通道 ID</span>
                <input value={draft.id || ''} onChange={(event) => update({ id: event.target.value })} placeholder="feishu" />
              </label>
              <label className="form-field">
                <span>名称</span>
                <input value={draft.name || ''} onChange={(event) => update({ name: event.target.value })} placeholder="飞书" />
              </label>
              <label className="form-field">
                <span>类型</span>
                <select value={draft.type || 'api'} onChange={(event) => update({ type: event.target.value })}>
                  <option value="webui">webui</option>
                  <option value="api">api</option>
                  <option value="feishu">feishu</option>
                  <option value="dingtalk">dingtalk</option>
                  <option value="ddio">ddio</option>
                  <option value="custom">custom</option>
                </select>
              </label>
              <label className="form-field">
                <span>审批模式</span>
                <select value={draft.approvalMode || 'ask'} onChange={(event) => update({ approvalMode: event.target.value })}>
                  <option value="ask">请求批准</option>
                  <option value="auto">替我审批</option>
                  <option value="full">完全访问</option>
                  <option value="custom">自定义</option>
                </select>
              </label>
              <label className="form-field">
                <span>入站路径</span>
                <input value={draft.inboundPath || ''} onChange={(event) => update({ inboundPath: event.target.value })} placeholder="/api/v1/channels/feishu/inbound" />
              </label>
              <div className="form-field checkbox-field-group">
                <label><input type="checkbox" checked={Boolean(draft.enabled)} onChange={(event) => update({ enabled: event.target.checked })} />启用通道</label>
              </div>
              <label className="form-field">
                <span>高危工具白名单</span>
                <textarea
                  value={joinConfigLines(draft.approvedToolIds)}
                  onChange={(event) => update({ approvedToolIds: splitConfigLines(event.target.value) })}
                  placeholder="每行一个工具 ID，仅 custom 模式使用"
                />
              </label>
              {(channelType === 'feishu' || channelType === 'lark') && (
                <div className="form-field channel-platform-fields">
                  <span>飞书官方 HTTP 配置</span>
                  <div className="platform-config-grid">
                    <input value={metadata.verificationTokenEnv || ''} onChange={(event) => updateMetadata('verificationTokenEnv', event.target.value)} placeholder="Verification Token Env" />
                    <input value={metadata.encryptKeyEnv || ''} onChange={(event) => updateMetadata('encryptKeyEnv', event.target.value)} placeholder="Encrypt Key Env" />
                    <input value={metadata.appIdEnv || ''} onChange={(event) => updateMetadata('appIdEnv', event.target.value)} placeholder="App ID Env" />
                    <input value={metadata.appSecretEnv || ''} onChange={(event) => updateMetadata('appSecretEnv', event.target.value)} placeholder="App Secret Env" />
                    <input value={metadata.appId || ''} onChange={(event) => updateMetadata('appId', event.target.value)} placeholder="App ID（YML/本地直填优先）" />
                    <input value={metadata.appSecret || ''} onChange={(event) => updateMetadata('appSecret', event.target.value)} placeholder="App Secret（不推荐明文）" />
                    <input value={metadata.tenantAccessTokenEnv || ''} onChange={(event) => updateMetadata('tenantAccessTokenEnv', event.target.value)} placeholder="Tenant Access Token Env（可选）" />
                    <input value={metadata.connectionMode || ''} onChange={(event) => updateMetadata('connectionMode', event.target.value)} placeholder="connectionMode=http / long-connection" />
                      <select value={metadata.outboundMessageType || 'text'} onChange={(event) => updateMetadata('outboundMessageType', event.target.value)}>
                        <option value="text">出站：text</option>
                        <option value="post">出站：post</option>
                        <option value="image">出站：image</option>
                        <option value="file">出站：file</option>
                        <option value="interactive">出站：card</option>
                        <option value="attachments">出站：attachments</option>
                        <option value="auto">出站：auto</option>
                      </select>
                  </div>
                  <small>优先使用环境变量保存密钥；HTTP 回调和长连接共用 appId/appSecret，connectionMode 可标记 http 或 long-connection，出站支持 text、post、image、file、card、attachments 和 auto。</small>
                </div>
              )}
              {channelType === 'dingtalk' && (
                <div className="form-field channel-platform-fields">
                  <span>钉钉自定义机器人配置</span>
                  <div className="platform-config-grid">
                    <input value={metadata.webhookUrlEnv || ''} onChange={(event) => updateMetadata('webhookUrlEnv', event.target.value)} placeholder="Webhook URL Env" />
                    <input value={metadata.secretEnv || ''} onChange={(event) => updateMetadata('secretEnv', event.target.value)} placeholder="加签 Secret Env" />
                    <input value={metadata.webhookUrl || ''} onChange={(event) => updateMetadata('webhookUrl', event.target.value)} placeholder="Webhook URL（不推荐明文）" />
                    <input value={metadata.clientIdEnv || ''} onChange={(event) => updateMetadata('clientIdEnv', event.target.value)} placeholder="Stream Client ID Env" />
                    <input value={metadata.clientSecretEnv || ''} onChange={(event) => updateMetadata('clientSecretEnv', event.target.value)} placeholder="Stream Client Secret Env" />
                    <input value={metadata.clientId || ''} onChange={(event) => updateMetadata('clientId', event.target.value)} placeholder="Stream Client ID（直填优先）" />
                    <input value={metadata.clientSecret || ''} onChange={(event) => updateMetadata('clientSecret', event.target.value)} placeholder="Stream Client Secret（不推荐明文）" />
                    <input value={metadata.appKey || ''} onChange={(event) => updateMetadata('appKey', event.target.value)} placeholder="兼容 AppKey" />
                    <input value={metadata.appSecret || ''} onChange={(event) => updateMetadata('appSecret', event.target.value)} placeholder="兼容 AppSecret" />
                    <input value={metadata.connectionMode || ''} onChange={(event) => updateMetadata('connectionMode', event.target.value)} placeholder="connectionMode=http / stream" />
                    <select value={metadata.outboundMessageType || 'text'} onChange={(event) => updateMetadata('outboundMessageType', event.target.value)}>
                      <option value="text">出站：text</option>
                      <option value="markdown">出站：markdown</option>
                    </select>
                    <input value={metadata.markdownTitle || ''} onChange={(event) => updateMetadata('markdownTitle', event.target.value)} placeholder="Markdown 标题（默认 ClawAgent）" />
                  </div>
                  <small>HTTP 机器人使用 webhook；Stream 模式使用 clientId/clientSecret，生产环境建议只填 env 名称；出站可选 text 或 markdown。</small>
                </div>
              )}
              {channelType === 'ddio' && (
                <div className="form-field channel-platform-fields">
                  <span>DDIO 官方 HTTP 配置</span>
                  <div className="platform-config-grid">
                    <input value={metadata.appIdEnv || ''} onChange={(event) => updateMetadata('appIdEnv', event.target.value)} placeholder="App ID Env" />
                    <input value={metadata.appSecretEnv || ''} onChange={(event) => updateMetadata('appSecretEnv', event.target.value)} placeholder="App Secret Env" />
                    <input value={metadata.baseUrlEnv || ''} onChange={(event) => updateMetadata('baseUrlEnv', event.target.value)} placeholder="Base URL Env" />
                    <input value={metadata.appId || ''} onChange={(event) => updateMetadata('appId', event.target.value)} placeholder="App ID（直填优先）" />
                    <input value={metadata.appSecret || ''} onChange={(event) => updateMetadata('appSecret', event.target.value)} placeholder="App Secret（不推荐明文）" />
                    <input value={metadata.baseUrl || ''} onChange={(event) => updateMetadata('baseUrl', event.target.value)} placeholder="https://host:10443" />
                    <select value={metadata['channel.ddio.chatScene'] || 'user'} onChange={(event) => updateMetadata('channel.ddio.chatScene', event.target.value)}>
                      <option value="user">场景：个人</option>
                      <option value="group">场景：群组</option>
                    </select>
                  </div>
                  <small>DDIO 出站需要 appId/appSecret 和 receTargetID，baseUrl 可直填或通过 env 读取；直填值优先于环境变量。</small>
                </div>
              )}
              <label className="form-field">
                <span>Metadata</span>
                <textarea
                  value={joinKeyValueLines(draft.metadata)}
                  onChange={(event) => update({ metadata: parseKeyValueLines(event.target.value) })}
                  placeholder={"adapter=builtin\nadapterLevel=official-http\nverificationTokenEnv=FEISHU_VERIFICATION_TOKEN\nencryptKeyEnv=FEISHU_ENCRYPT_KEY\nappIdEnv=FEISHU_APP_ID\nappSecretEnv=FEISHU_APP_SECRET\nwebhookUrlEnv=DINGTALK_WEBHOOK_URL\nsecretEnv=DINGTALK_SECRET\nbaseUrlEnv=DDIO_BASE_URL"}
                />
              </label>
            </div>
            <p className="muted compact-text">
              通道是外部 IM/HTTP 的统一入口。飞书支持 URL 校验、Verification Token、Encrypt Key 解密和文本回写；钉钉支持自定义机器人入站解析、Webhook 加签和 Stream；DDIO 支持 HTTP 回写。
            </p>
            {yamlManaged && (
              <div className="config-message">
                当前通道来自 YAML 配置，优先级高于 channels.json。请修改 application.yml 或 .clawagent/config/clawagent.yml 后刷新配置。
              </div>
            )}
            {message && <div className="config-message">{message}</div>}
            {health && (
              <div className={`channel-health ${health.ready ? 'ready' : 'failed'}`}>
                <div>
                  <strong>{health.ready ? '配置可用' : '配置需处理'}</strong>
                  <small>{health.probedRemote ? '已探测平台接口' : '本地配置检查'}</small>
                </div>
                <p>{health.message || '检测完成。'}</p>
                {health.missingKeys?.length ? (
                  <p className="muted compact-text">缺失：{health.missingKeys.join('、')}</p>
                ) : null}
                {health.details && Object.keys(health.details).length > 0 && (
                  <code>{Object.entries(health.details).map(([key, value]) => `${key}=${value}`).join('  ')}</code>
                )}
              </div>
            )}
            {streamStatus && (
              <div className={`channel-health ${streamStatus.status === 'running' ? 'ready' : streamStatus.status === 'failed' ? 'failed' : ''}`}>
                <div>
                  <strong>Stream：{streamStatus.status || '-'}</strong>
                  <small>{streamStatus.mode || '未设置'}</small>
                </div>
                <p>{streamStatus.message || 'Stream 状态已更新。'}</p>
                {streamStatus.details && Object.keys(streamStatus.details).length > 0 && (
                  <code>{Object.entries(streamStatus.details).map(([key, value]) => `${key}=${value}`).join('  ')}</code>
                )}
              </div>
            )}
            <div className="form-actions">
              <button className="send-button" type="submit" disabled={saving || yamlManaged}>{saving ? '保存中...' : yamlManaged ? 'YAML 管理' : '保存通道'}</button>
            </div>
          </form>
        </Panel>
      </div>
      <Panel
        title="外部用户绑定"
        action={<button type="button" onClick={onRefreshUserBindings} disabled={bindingLoading || !draft.id}><RefreshCw size={14} />刷新</button>}
      >
        <div className="config-form channel-binding-section">
          <div className="channel-binding-form">
            <label className="form-field">
              <span>外部用户 ID</span>
              <input
                value={bindingExternalUserId}
                onChange={(event) => onBindingExternalUserIdChange(event.target.value)}
                placeholder="飞书 open_id / 钉钉 senderStaffId / DDIO userId"
              />
            </label>
            <label className="form-field">
              <span>外部用户名</span>
              <input
                value={bindingExternalUsername}
                onChange={(event) => onBindingExternalUsernameChange(event.target.value)}
                placeholder="可选，便于审计识别"
              />
            </label>
            <label className="form-field">
              <span>本地用户</span>
              <select value={bindingLocalUserId} onChange={(event) => onBindingLocalUserIdChange(event.target.value)}>
                <option value="">选择本地用户</option>
                {activeUsers.map((user) => (
                  <option key={user.id} value={user.id}>
                    {(user.displayName || user.username || user.id)}{user.role ? ` · ${user.role}` : ''}
                  </option>
                ))}
              </select>
            </label>
            <div className="form-field channel-binding-actions">
              <span>操作</span>
              <button type="button" className="primary-button" onClick={onBindChannelUser} disabled={bindingLoading || !draft.id}>
                <Link2 size={14} />绑定
              </button>
            </div>
          </div>
          <p className="muted compact-text">
            通道收到外部消息后，会先按这张表把平台用户映射为本地用户，再合并用户、通道和 Agent 的权限策略。
          </p>
          {userBindings.length ? (
            <Table>
              <thead>
                <tr>
                  <th>外部用户</th>
                  <th>本地用户</th>
                  <th>状态</th>
                  <th>更新时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {userBindings.map((binding) => (
                  <tr key={binding.id}>
                    <td>
                      <strong>{binding.externalUsername || binding.externalUserId}</strong>
                      <small className="mono block-text">{binding.externalUserId}</small>
                    </td>
                    <td>
                      <strong>{binding.localUsername || binding.localUserId}</strong>
                      <small className="mono block-text">{binding.localUserId}</small>
                    </td>
                    <td><span className={`pill ${binding.status === 'active' ? 'success' : 'neutral'}`}>{binding.status || '-'}</span></td>
                    <td>{formatDateTime(binding.updatedAt || binding.createdAt)}</td>
                    <td>
                      <button type="button" className="link-button danger-text" disabled={bindingLoading} onClick={() => onUnbindChannelUser(binding.externalUserId)}>
                        解绑
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </Table>
          ) : (
            <Empty text={bindingLoading ? '正在加载用户绑定...' : '当前通道暂无外部用户绑定'} />
          )}
        </div>
      </Panel>
      <Panel title="入站测试">
        <div className="config-form">
          <label className="form-field">
            <span>测试消息</span>
            <textarea value={testText} onChange={(event) => onTestTextChange(event.target.value)} />
          </label>
          <div className="form-actions">
            <button className="primary-button" type="button" disabled={saving || !draft.id} onClick={onSubmitTest}>
              <Send size={14} />提交到当前通道
            </button>
          </div>
          {testResult && (
            <pre className="code-block">{JSON.stringify(testResult, null, 2)}</pre>
          )}
        </div>
      </Panel>
      <Panel title="出站测试">
        <div className="config-form">
          <label className="form-field">
            <span>{outboundTargetLabel}</span>
            <input
              value={outboundConversationId}
              onChange={(event) => onOutboundConversationIdChange(event.target.value)}
              placeholder={outboundTargetPlaceholder}
            />
          </label>
          <label className="form-field">
            <span>测试内容</span>
            <textarea value={outboundText} onChange={(event) => onOutboundTextChange(event.target.value)} />
          </label>
          <p className="muted compact-text">
            {outboundHelpText}
          </p>
          <div className="form-actions">
            <button className="primary-button" type="button" disabled={saving || !draft.id} onClick={onSubmitOutboundTest}>
              <Send size={14} />发送出站测试
            </button>
          </div>
          {outboundResult && (
            <div className={`channel-health ${outboundResult.sent ? 'ready' : 'failed'}`}>
              <div>
                <strong>{outboundResult.sent ? '发送成功' : '发送失败'}</strong>
                <small>{outboundResult.status || '-'}</small>
              </div>
              <p>{outboundResult.message || '出站测试完成。'}</p>
              {outboundResult.details && Object.keys(outboundResult.details).length > 0 && (
                <code>{Object.entries(outboundResult.details).map(([key, value]) => `${key}=${value}`).join('  ')}</code>
              )}
            </div>
          )}
        </div>
      </Panel>
    </section>
  );
}

function AuthPage({
  tokens,
  tokenName,
  config,
  loading,
  saving,
  message,
  createdToken,
  users,
  currentUser,
  currentSession,
  loginUsername,
  loginPassword,
  userUsername,
  userPassword,
  userDisplayName,
  userRole,
  userPermissionMode,
  userApprovedToolIds,
  userLoading,
  userSaving,
  userMessage,
  sessions,
  sessionLoading,
  onRefresh,
  onClearCreatedToken,
  onTokenNameChange,
  onCreate,
  onRevoke,
  onLoginUsernameChange,
  onLoginPasswordChange,
  onLogin,
  onLogout,
  onUserUsernameChange,
  onUserPasswordChange,
  onUserDisplayNameChange,
  onUserRoleChange,
  onUserPermissionModeChange,
  onUserApprovedToolIdsChange,
  onCreateUser,
  onChangeUserPassword,
  onUpdateUserPermissions,
  onDisableUser,
  onRevokeSession,
}: {
  tokens: ApiTokenView[];
  tokenName: string;
  config?: RuntimeConfigSnapshot;
  loading: boolean;
  saving: boolean;
  message?: string;
  createdToken?: ApiTokenCreateResponse;
  users: LocalUserView[];
  currentUser?: LocalUserView;
  currentSession?: LocalUserSessionView;
  loginUsername: string;
  loginPassword: string;
  userUsername: string;
  userPassword: string;
  userDisplayName: string;
  userRole: string;
  userPermissionMode: string;
  userApprovedToolIds: string;
  userLoading: boolean;
  userSaving: boolean;
  userMessage?: string;
  sessions: LocalUserSessionView[];
  sessionLoading: boolean;
  onRefresh: () => void;
  onClearCreatedToken: () => void;
  onTokenNameChange: (value: string) => void;
  onCreate: () => void;
  onRevoke: (tokenId: string) => void;
  onLoginUsernameChange: (value: string) => void;
  onLoginPasswordChange: (value: string) => void;
  onLogin: () => void;
  onLogout: () => void;
  onUserUsernameChange: (value: string) => void;
  onUserPasswordChange: (value: string) => void;
  onUserDisplayNameChange: (value: string) => void;
  onUserRoleChange: (value: string) => void;
  onUserPermissionModeChange: (value: string) => void;
  onUserApprovedToolIdsChange: (value: string) => void;
  onCreateUser: () => void;
  onChangeUserPassword: (userId: string) => void;
  onUpdateUserPermissions: (user: LocalUserView) => void;
  onDisableUser: (userId: string) => void;
  onRevokeSession: (sessionId: string) => void;
}) {
  const activeTokens = tokens.filter((token) => token.status === 'active');
  const recentlyUsedTokens = tokens.filter((token) => token.lastUsedAt).length;
  const activeUsers = users.filter((user) => user.status === 'active');
  const activeSessions = sessions.filter((session) => session.status === 'active');
  const authEnabled = Boolean(config?.auth?.required || config?.auth?.apiTokenRequired);
  const supportedRoles = config?.auth?.supportedRoles?.length
    ? config.auth.supportedRoles
    : ['owner', 'admin', 'operator', 'viewer', 'user'];
  const rolePolicyEntries = Object.entries(config?.auth?.rolePolicies || {});
  const firstUser = users.length === 0;
  type AuthSection = 'tokens' | 'users' | 'sessions' | 'policy';
  const [section, setSection] = useState<AuthSection>('tokens');
  const [copiedTokenId, setCopiedTokenId] = useState<string>();
  const sectionTabs: Array<{ key: AuthSection; label: string; count?: number }> = [
    { key: 'tokens', label: '访问令牌', count: tokens.length },
    { key: 'users', label: '本地用户', count: users.length },
    { key: 'sessions', label: '登录会话', count: activeSessions.length },
    { key: 'policy', label: '策略', count: rolePolicyEntries.length },
  ];
  const [tokenDialogOpen, setTokenDialogOpen] = useState(false);
  const [userDialogOpen, setUserDialogOpen] = useState(false);
  const copyTokenValue = useCallback(async (token: ApiTokenView) => {
    const value = token.token || token.tokenPrefix || '';
    if (!value) return;
    let copied = false;
    try {
      await navigator.clipboard.writeText(value);
      copied = true;
    } catch {
      const textarea = document.createElement('textarea');
      textarea.value = value;
      textarea.setAttribute('readonly', 'true');
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      document.body.appendChild(textarea);
      textarea.select();
      try {
        copied = document.execCommand('copy');
      } finally {
        document.body.removeChild(textarea);
      }
    }
    // 部分浏览器上下文会禁用 Clipboard API，复制失败时要给出明确反馈。
    setCopiedTokenId(copied ? token.id : `failed:${token.id}`);
    window.setTimeout(() => {
      setCopiedTokenId((current) => (current === token.id || current === `failed:${token.id}` ? undefined : current));
    }, 1600);
  }, []);
  return (
    <section className="stack">
      <div className="metric-grid compact-metrics">
        <Metric title="API Token" value={tokens.length} desc="本地保存记录" />
        <Metric title="Active" value={activeTokens.length} desc="可用于 API 鉴权" />
        <Metric title="本地用户" value={users.length} desc={`${activeUsers.length} 个可用`} />
        <Metric title="登录会话" value={sessions.length} desc={`${activeSessions.length} 个活跃`} />
        <Metric title="强鉴权" value={authEnabled ? '已开启' : '未开启'} desc={authEnabled ? '匹配接口需要 API Token 或本地会话' : '本地 WebUI 默认不拦截'} />
        <Metric title="已使用" value={recentlyUsedTokens} desc="有 lastUsedAt 记录" />
      </div>
      <div className="auth-section-tabs segmented-tabs">
        {sectionTabs.map((tab) => (
          <button
            type="button"
            key={tab.key}
            className={section === tab.key ? 'active' : ''}
            onClick={() => setSection(tab.key)}
          >
            {tab.label}
            {typeof tab.count === 'number' && <span className="tab-count">{tab.count}</span>}
          </button>
        ))}
        <button type="button" className="auth-refresh-button" onClick={onRefresh} disabled={loading || userLoading || sessionLoading}>
          <RefreshCw size={14} />刷新
        </button>
      </div>

      {section === 'tokens' && (
        <Panel
          title="Token 列表"
          action={<button type="button" onClick={() => { onClearCreatedToken(); setTokenDialogOpen(true); }}><Plus size={14} />新建 Token</button>}
        >
          <p className="auth-help-text">
            Token 用于外部系统调用 ClawAgent API。列表支持复制完整 Token；旧版本创建的 Token 只有前缀，无法恢复明文。
          </p>
          {tokens.length ? (
            <table className="data-table">
              <thead>
                <tr>
                  <th>名称</th>
                  <th>Token</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th>最后使用</th>
                  <th>次数</th>
                  <th>最后访问</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {tokens.map((token) => (
                  <tr key={token.id}>
                    <td>{token.name || token.id}</td>
                    <td className="auth-token-cell">
                      <div className="copy-inline token-copy-inline mono">
                        <span className="auth-token-value">{token.token || token.tokenPrefix || '-'}</span>
                        {(token.token || token.tokenPrefix) && (
                          <button type="button" title={token.token ? '复制完整 Token' : '复制 Token 前缀'} onClick={() => void copyTokenValue(token)}>
                            {copiedTokenId === token.id ? <Check size={14} /> : <Copy size={14} />}
                            {copiedTokenId === token.id ? '已复制' : copiedTokenId === `failed:${token.id}` ? '复制失败' : '复制'}
                          </button>
                        )}
                      </div>
                    </td>
                    <td><span className={`pill ${token.status === 'active' ? 'success' : 'neutral'}`}>{token.status || '-'}</span></td>
                    <td>{formatDateTime(token.createdAt)}</td>
                    <td>{formatDateTime(token.lastUsedAt)}</td>
                    <td>{token.usageCount ?? 0}</td>
                    <td className="mono compact-text">{token.lastUsedMethod && token.lastUsedPath ? `${token.lastUsedMethod} ${token.lastUsedPath}` : '-'}</td>
                    <td>
                      <button type="button" disabled={saving} onClick={() => onRevoke(token.id)}>
                        <Trash2 size={14} />删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty text={loading ? '正在加载 API Token...' : '暂无 API Token'} />
          )}
        </Panel>
      )}

      {section === 'users' && (
        <div className="stack">
          <div className="auth-usage-note">
            <strong>本地用户怎么使用？</strong>
            <span>这里负责创建和维护用户。登录入口放在客户端：App 远程模式在左下角登录；本地模式默认信任本机，不要求登录。</span>
          </div>
          <Panel
            title="用户列表"
            action={<button type="button" onClick={() => setUserDialogOpen(true)}><Plus size={14} />新建用户</button>}
          >
            {users.length ? (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>用户名</th>
                    <th>显示名</th>
                    <th>角色</th>
                    <th>权限</th>
                    <th>工具白名单</th>
                    <th>状态</th>
                    <th>创建时间</th>
                    <th>改密时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {users.map((user) => (
                    <tr key={user.id}>
                      <td className="mono">{user.username || user.id}</td>
                      <td>{user.displayName || '-'}</td>
                      <td>{user.role || '-'}</td>
                      <td>{user.metadata?.permissionMode || user.metadata?.toolPermissionMode || '-'}</td>
                      <td className="mono compact-text">{user.metadata?.approvedToolIds || user.metadata?.toolIds || '-'}</td>
                      <td><span className={`pill ${user.status === 'active' ? 'success' : 'neutral'}`}>{user.status || '-'}</span></td>
                      <td>{formatDateTime(user.createdAt)}</td>
                      <td>{formatDateTime(user.lastPasswordChangedAt)}</td>
                      <td>
                        <div className="row-actions">
                          <button type="button" disabled={userSaving || user.status !== 'active'} onClick={() => onChangeUserPassword(user.id)}>
                            改密
                          </button>
                          <button type="button" disabled={userSaving || user.status !== 'active'} onClick={() => onUpdateUserPermissions(user)}>
                            权限
                          </button>
                          <button type="button" disabled={userSaving || user.status !== 'active'} onClick={() => onDisableUser(user.id)}>
                            <Trash2 size={14} />禁用
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <Empty text={userLoading ? '正在加载本地用户...' : '暂无本地用户'} />
            )}
          </Panel>
        </div>
      )}

      {section === 'sessions' && (
        <Panel title="登录会话">
          {sessions.length ? (
            <table className="data-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>角色</th>
                <th>状态</th>
                <th>Token 前缀</th>
                <th>创建时间</th>
                <th>过期时间</th>
                <th>最后使用</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {sessions.map((session) => (
                <tr key={session.sessionId}>
                  <td>
                    <div className="strong">{session.displayName || session.username || '-'}</div>
                    <div className="muted mono compact-text">{session.username || session.userId || '-'}</div>
                  </td>
                  <td>{session.role || '-'}</td>
                  <td><span className={`pill ${session.status === 'active' ? 'success' : 'neutral'}`}>{session.status || '-'}</span></td>
                  <td className="mono">{session.tokenPrefix || '-'}</td>
                  <td>{formatDateTime(session.createdAt)}</td>
                  <td>{formatDateTime(session.expiresAt)}</td>
                  <td>{formatDateTime(session.lastUsedAt)}</td>
                  <td>
                    <button type="button" disabled={userSaving || session.status !== 'active'} onClick={() => onRevokeSession(session.sessionId)}>
                      <Trash2 size={14} />撤销
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <Empty text={sessionLoading ? '正在加载本地登录会话...' : '暂无本地登录会话'} />
        )}
        </Panel>
      )}

      {section === 'policy' && (
        <Panel title="角色策略模板">
          <p className="auth-help-text">
            策略模板是按角色预设的权限规则，用来给本地用户、Token、通道用户和设备做默认权限合并。当前没有数据，是因为配置里还没定义角色策略；没有配置时会走用户自身权限字段和系统默认值。
          </p>
          {rolePolicyEntries.length ? (
            <table className="data-table">
              <thead>
                <tr>
                  <th>角色</th>
                  <th>状态</th>
                  <th>权限模式</th>
                  <th>审批模式</th>
                  <th>工具白名单</th>
                </tr>
              </thead>
              <tbody>
                {rolePolicyEntries.map(([role, policy]) => (
                  <tr key={role}>
                    <td className="mono">{role}</td>
                    <td><span className={`pill ${policy?.enabled === false ? 'neutral' : 'success'}`}>{policy?.enabled === false ? '禁用' : '启用'}</span></td>
                    <td>{policy?.permissionMode || '-'}</td>
                    <td>{policy?.approvalMode || '-'}</td>
                    <td className="mono compact-text">{policy?.approvedToolIds?.length ? policy.approvedToolIds.join(', ') : '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Empty text="暂无角色策略模板；未配置时仅 viewer 默认 read-only，用户自身权限字段仍可参与合并。" />
          )}
        </Panel>
      )}
      {tokenDialogOpen && (
        <Modal onClose={() => setTokenDialogOpen(false)}>
          <div className="modal-head">
            <div>
              <h3>新建 Token</h3>
              <p>完整 Token 只展示一次，请创建后立即复制。</p>
            </div>
            <button type="button" onClick={() => setTokenDialogOpen(false)}><X size={16} /></button>
          </div>
          <form
            className="config-form"
            onSubmit={(event) => {
              event.preventDefault();
              onCreate();
            }}
          >
            <label className="form-field">
              <span>名称</span>
              <input value={tokenName} onChange={(event) => onTokenNameChange(event.target.value)} placeholder="CI / IM Gateway / Local Client" autoFocus />
            </label>
            <p className="muted compact-text">
              生成后只展示一次明文；服务端只保存 SHA-256 哈希和前缀。
            </p>
            {createdToken?.token && (
              <div className="token-once">
                <small>请立即保存完整 Token</small>
                <code>{createdToken.token}</code>
                <button type="button" onClick={() => void navigator.clipboard.writeText(createdToken.token || '')}><Copy size={14} />复制完整 Token</button>
              </div>
            )}
            {message && <div className="config-message">{message}</div>}
            <div className="form-actions">
              <button type="button" onClick={() => setTokenDialogOpen(false)}>关闭</button>
              <button className="send-button" type="submit" disabled={saving}>{saving ? '生成中...' : '生成 API Token'}</button>
            </div>
          </form>
        </Modal>
      )}
      {userDialogOpen && (
        <Modal onClose={() => setUserDialogOpen(false)}>
          <div className="modal-head">
            <div>
              <h3>{firstUser ? '初始化 owner' : '新建本地用户'}</h3>
              <p>创建后可在 App 远程模式登录使用；本地模式默认信任本机，不要求登录。</p>
            </div>
            <button type="button" onClick={() => setUserDialogOpen(false)}><X size={16} /></button>
          </div>
          <form
            className="config-form"
            onSubmit={(event) => {
              event.preventDefault();
              onCreateUser();
            }}
          >
            <div className="form-grid two">
              <label className="form-field">
                <span>用户名</span>
                <input value={userUsername} onChange={(event) => onUserUsernameChange(event.target.value)} placeholder="admin / operator" autoFocus />
              </label>
              <label className="form-field">
                <span>显示名</span>
                <input value={userDisplayName} onChange={(event) => onUserDisplayNameChange(event.target.value)} placeholder="本地管理员" />
              </label>
              <label className="form-field">
                <span>密码</span>
                <input type="password" value={userPassword} onChange={(event) => onUserPasswordChange(event.target.value)} placeholder="至少 6 位" />
              </label>
              <label className="form-field">
                <span>角色</span>
                <select value={firstUser ? 'owner' : userRole} onChange={(event) => onUserRoleChange(event.target.value)} disabled={firstUser}>
                  {supportedRoles.map((role) => (
                    <option value={role} key={role}>{role}</option>
                  ))}
                </select>
              </label>
              <label className="form-field">
                <span>权限模式</span>
                <select value={userPermissionMode} onChange={(event) => onUserPermissionModeChange(event.target.value)}>
                  <option value="ask">ask</option>
                  <option value="auto">auto</option>
                  <option value="custom">custom</option>
                  <option value="read-only">read-only</option>
                  <option value="full">full</option>
                  <option value="full-access">full-access</option>
                </select>
              </label>
              <label className="form-field wide">
                <span>工具白名单</span>
                <textarea
                  rows={3}
                  value={userApprovedToolIds}
                  onChange={(event) => onUserApprovedToolIdsChange(event.target.value)}
                  placeholder="builtin.filesystem.read_text_file&#10;builtin.execute.command"
                />
              </label>
            </div>
            <p className="muted compact-text">
              {firstUser ? '首个本地用户会通过 setup 初始化为 owner。' : '用户维度权限会在任务入口与 Channel/Device/Agent 策略合并。'}
            </p>
            {userMessage && <div className="config-message">{userMessage}</div>}
            <div className="form-actions">
              <button type="button" onClick={() => setUserDialogOpen(false)}>关闭</button>
              <button className="send-button" type="submit" disabled={userSaving}>{userSaving ? '创建中...' : (firstUser ? '初始化 owner' : '创建用户')}</button>
            </div>
          </form>
        </Modal>
      )}
    </section>
  );
}

function DevicePage({
  devices,
  deviceName,
  deviceType,
  devicePermissionMode,
  deviceApprovedToolIds,
  pairingTtlSeconds,
  createdPairing,
  loading,
  saving,
  message,
  onRefresh,
  onDeviceNameChange,
  onDeviceTypeChange,
  onDevicePermissionModeChange,
  onDeviceApprovedToolIdsChange,
  onPairingTtlSecondsChange,
  onRegister,
  onCreatePairingCode,
  onHeartbeat,
  onRotateSecret,
  onBindUser,
  onUpdatePermissions,
  onRevoke,
}: {
  devices: DeviceView[];
  deviceName: string;
  deviceType: string;
  devicePermissionMode: string;
  deviceApprovedToolIds: string;
  pairingTtlSeconds: number;
  createdPairing?: DevicePairingCodeResponse;
  loading: boolean;
  saving: boolean;
  message?: string;
  onRefresh: () => void;
  onDeviceNameChange: (value: string) => void;
  onDeviceTypeChange: (value: string) => void;
  onDevicePermissionModeChange: (value: string) => void;
  onDeviceApprovedToolIdsChange: (value: string) => void;
  onPairingTtlSecondsChange: (value: number) => void;
  onRegister: () => void;
  onCreatePairingCode: () => void;
  onHeartbeat: (deviceId: string) => void;
  onRotateSecret: (deviceId: string) => void;
  onBindUser: (deviceId: string) => void;
  onUpdatePermissions: (deviceId: string) => void;
  onRevoke: (deviceId: string) => void;
}) {
  const activeDevices = devices.filter((device) => device.status === 'active');
  const desktopDevices = devices.filter((device) => device.type === 'desktop');
  const pairingDevices = devices.filter((device) => device.status === 'pairing');
  const [deviceDialogOpen, setDeviceDialogOpen] = useState(false);
  return (
    <section className="stack">
      <div className="metric-grid compact-metrics">
        <Metric title="Device" value={devices.length} desc="登记设备" />
        <Metric title="Active" value={activeDevices.length} desc="未撤销设备" />
        <Metric title="Desktop" value={desktopDevices.length} desc="桌面端登记" />
        <Metric title="Pairing" value={pairingDevices.length} desc="等待配对" />
      </div>
      <Panel
        title="设备列表"
        action={(
          <div className="inline-actions">
            <button type="button" onClick={() => setDeviceDialogOpen(true)} disabled={saving}>
              <Plus size={14} />登记设备
            </button>
            <button type="button" onClick={onRefresh} disabled={loading}>
              <RefreshCw size={14} />刷新
            </button>
          </div>
        )}
      >
        {message && <div className="config-message">{message}</div>}
        {devices.length ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>类型</th>
                <th>状态</th>
                <th>配对</th>
                <th>密钥</th>
                <th>绑定用户</th>
                <th>权限</th>
                <th>最后心跳</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((device) => (
                <tr key={device.id}>
                  <td>{device.name || device.id}</td>
                  <td>{device.type || '-'}</td>
                  <td><span className={`pill ${device.status === 'active' ? 'success' : 'neutral'}`}>{device.status || '-'}</span></td>
                  <td>{device.pairedAt ? formatDateTime(device.pairedAt) : device.pairingCodeExpiresAt ? `过期 ${formatDateTime(device.pairingCodeExpiresAt)}` : '-'}</td>
                  <td className="mono">{device.deviceSecretPrefix || '-'}</td>
                  <td>{device.boundUsername || device.boundUserId || '-'}</td>
                  <td>
                    <div className="stack compact-text">
                      <span>{device.permissionMode || 'ask'}</span>
                      <span className="muted">{device.approvedToolIds?.length ? `${device.approvedToolIds.length} 个白名单` : '无白名单'}</span>
                    </div>
                  </td>
                  <td>{formatDateTime(device.lastSeenAt)}</td>
                  <td>
                    <div className="inline-actions">
                      <button type="button" disabled={saving || device.status !== 'active'} onClick={() => onHeartbeat(device.id)}>
                        <RefreshCw size={14} />心跳
                      </button>
                      <button type="button" disabled={saving || device.status === 'revoked'} onClick={() => onBindUser(device.id)}>
                        绑定
                      </button>
                      <button type="button" disabled={saving || device.status !== 'active'} onClick={() => onRotateSecret(device.id)}>
                        轮换
                      </button>
                      <button type="button" disabled={saving || device.status === 'revoked'} onClick={() => onUpdatePermissions(device.id)}>
                        权限
                      </button>
                      <button type="button" disabled={saving || device.status !== 'active'} onClick={() => onRevoke(device.id)}>
                        <Trash2 size={14} />撤销
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <Empty text={loading ? '正在加载设备...' : '暂无设备登记'} />
        )}
      </Panel>

      {deviceDialogOpen && (
        <Modal onClose={() => setDeviceDialogOpen(false)}>
          <div className="modal-head">
            <div>
              <h3>登记设备</h3>
              <p>登记本机设备，或生成配对码给桌面壳、浏览器扩展、外部网关使用。</p>
            </div>
            <button type="button" className="icon-button" onClick={() => setDeviceDialogOpen(false)} aria-label="关闭">
              <X size={16} />
            </button>
          </div>
          <form
            className="config-form modal-body"
            onSubmit={(event) => {
              event.preventDefault();
              onRegister();
            }}
          >
            <label className="form-field">
              <span>设备名称</span>
              <input value={deviceName} onChange={(event) => onDeviceNameChange(event.target.value)} placeholder="Local Desktop / Browser Extension" />
            </label>
            <label className="form-field">
              <span>设备类型</span>
              <select value={deviceType} onChange={(event) => onDeviceTypeChange(event.target.value)}>
                <option value="desktop">desktop</option>
                <option value="browser">browser</option>
                <option value="gateway">gateway</option>
                <option value="local">local</option>
              </select>
            </label>
            <label className="form-field">
              <span>权限模式</span>
              <select value={devicePermissionMode} onChange={(event) => onDevicePermissionModeChange(event.target.value)}>
                <option value="ask">ask</option>
                <option value="auto">auto</option>
                <option value="custom">custom</option>
                <option value="read-only">read-only</option>
                <option value="full-access">full-access</option>
              </select>
            </label>
            <label className="form-field">
              <span>配对码有效期</span>
              <input type="number" min={60} max={3600} step={60} value={pairingTtlSeconds} onChange={(event) => onPairingTtlSecondsChange(Number(event.target.value) || 600)} />
            </label>
            <label className="form-field">
              <span>高危工具白名单</span>
              <textarea value={deviceApprovedToolIds} onChange={(event) => onDeviceApprovedToolIdsChange(event.target.value)} placeholder="builtin.execute.command" />
            </label>
            <p className="muted compact-text">
              直接登记用于管理台本地录入；配对码用于桌面壳、浏览器扩展或外部网关绑定，设备密钥只在客户端完成配对时返回一次。
            </p>
            {createdPairing?.code && (
              <div className="token-once">
                <small>配对码，过期前在客户端输入</small>
                <code>{createdPairing.code}</code>
                <span className="muted compact-text">过期：{formatDateTime(createdPairing.expiresAt)}</span>
                <button type="button" onClick={() => void navigator.clipboard.writeText(createdPairing.code || '')}><Copy size={14} />复制</button>
              </div>
            )}
            {message && <div className="config-message">{message}</div>}
            <div className="form-actions">
              <button className="send-button" type="submit" disabled={saving}>{saving ? '登记中...' : '登记设备'}</button>
              <button type="button" onClick={onCreatePairingCode} disabled={saving}>
                生成配对码
              </button>
            </div>
          </form>
        </Modal>
      )}
    </section>
  );
}

function SkillsPage({
  tools,
  mcpServers,
  mcpImportJson,
  mcpUpdating,
  mcpMessage,
  skills,
  skillInstallText,
  skillUpdating,
  skillMessage,
  config,
  configDraft,
  configSaving,
  configMessage,
  onMcpImportJsonChange,
  onMcpImport,
  onMcpRefresh,
  onMcpConnection,
  onMcpDelete,
  onMcpSave,
  onSkillInstallTextChange,
  onSkillInstall,
  onSkillRefresh,
  onSkillToggle,
  onSkillDelete,
  onSkillSave,
  onConfigDraftChange,
  onConfigSave,
}: {
  tools: ToolDefinition[];
  mcpServers: McpServerRegistration[];
  mcpImportJson: string;
  mcpUpdating?: string;
  mcpMessage?: string;
  skills: SkillRegistration[];
  skillInstallText: string;
  skillUpdating?: string;
  skillMessage?: string;
  config?: RuntimeConfigSnapshot;
  configDraft: ModelConfigUpdate;
  configSaving: boolean;
  configMessage?: string;
  onMcpImportJsonChange: (value: string) => void;
  onMcpImport: () => void;
  onMcpRefresh: () => void;
  onMcpConnection: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
  onMcpDelete?: (serverId: string) => void;
  onMcpSave?: (serverId: string, config: McpServerConfig) => Promise<void>;
  onSkillInstallTextChange: (value: string) => void;
  onSkillInstall: () => void;
  onSkillRefresh: () => void;
  onSkillToggle?: (skillId: string, enabled: boolean) => void;
  onSkillDelete?: (skillId: string) => void;
  onSkillSave?: (skillId: string, manifest: NonNullable<SkillRegistration['manifest']>) => Promise<void>;
  onConfigDraftChange: (draft: ModelConfigUpdate) => void;
  onConfigSave: () => void;
}) {
  const [tab, setTab] = useState<SkillTab>('tools');
  return (
    <section className="stack">
      <div className="settings-tab-row">
        <button className={tab === 'tools' ? 'active' : undefined} onClick={() => setTab('tools')}><Wrench size={15} />系统工具</button>
        <button className={tab === 'mcp' ? 'active' : undefined} onClick={() => setTab('mcp')}><Plug size={15} />MCP</button>
        <button className={tab === 'skills' ? 'active' : undefined} onClick={() => setTab('skills')}><Zap size={15} />Skill</button>
      </div>
      {tab === 'tools' && (
        <section className="stack">
          <Panel title="系统工具" action={<span className="muted">系统工具只读，审批白名单可在工具行直接维护。</span>}>
            <SystemToolManager
              tools={tools}
              draft={configDraft}
              saving={configSaving}
              onDraftChange={onConfigDraftChange}
              onSave={onConfigSave}
            />
          </Panel>
          <CapabilityConfigPanel
            tools={tools}
            draft={configDraft}
            config={config}
            saving={configSaving}
            message={configMessage}
            onChange={onConfigDraftChange}
            onSave={onConfigSave}
          />
        </section>
      )}
      {tab === 'mcp' && (
        <Panel title="MCP">
          <McpManager
            servers={mcpServers}
            importJson={mcpImportJson}
            updating={mcpUpdating}
            message={mcpMessage}
            onImportJsonChange={onMcpImportJsonChange}
            onImport={onMcpImport}
            onRefresh={onMcpRefresh}
            onConnection={onMcpConnection}
            onDelete={onMcpDelete}
            onSave={onMcpSave}
          />
        </Panel>
      )}
      {tab === 'skills' && (
        <Panel title="Skill">
          <SkillManager
            skills={skills}
            installText={skillInstallText}
            updatingSkillId={skillUpdating}
            message={skillMessage}
            onInstallTextChange={onSkillInstallTextChange}
            onInstall={onSkillInstall}
            onRefresh={onSkillRefresh}
            onToggle={onSkillToggle}
            onDelete={onSkillDelete}
            onSave={onSkillSave}
          />
        </Panel>
      )}
    </section>
  );
}

function SystemToolManager({
  tools,
  draft,
  saving,
  onDraftChange,
  onSave,
}: {
  tools: ToolDefinition[];
  draft: ModelConfigUpdate;
  saving: boolean;
  onDraftChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
}) {
  const [query, setQuery] = useState('');
  const [selectedTool, setSelectedTool] = useState<ToolDefinition | undefined>();
  const normalizedQuery = query.trim().toLowerCase();
  const approvedToolIds = draft.localApprovedToolIds || [];
  const mode = draft.localPermissionMode || 'ask';
  const toggleApproval = (toolId: string) => {
    const next = approvedToolIds.includes(toolId)
      ? approvedToolIds.filter((item) => item !== toolId)
      : [...approvedToolIds, toolId];
    onDraftChange({
      ...draft,
      localPermissionMode: 'custom',
      localApprovedToolIds: next,
    });
  };
  const visibleTools = tools.filter((tool) => {
    if (!normalizedQuery) return true;
    return [tool.id, tool.name, tool.description, tool.riskLevel]
      .filter(Boolean)
      .some((value) => String(value).toLowerCase().includes(normalizedQuery));
  });
  const highRiskCount = tools.filter((tool) => riskClass(tool.riskLevel) !== 'success').length;
  return (
    <div className="stack">
      <div className="switch-list-toolbar">
        <div className="segmented-tabs">
          <button className="active" type="button">All {tools.length}</button>
          <button type="button" disabled>High Risk {highRiskCount}</button>
        </div>
        <div className="inline-actions">
          <span className="muted">{visibleTools.length} shown · {approvedToolIds.length} approved</span>
          <button type="button" onClick={onSave} disabled={saving}>
            {saving ? '保存中...' : '保存审批配置'}
          </button>
        </div>
      </div>
      <input className="switch-list-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Filter system tools" />
      <ToolList
        tools={visibleTools}
        approvedToolIds={approvedToolIds}
        permissionMode={mode}
        onToggleApproval={toggleApproval}
        onSelect={setSelectedTool}
      />
      {selectedTool && (
        <ToolDetailModal
          tool={selectedTool}
          approved={approvedToolIds.includes(selectedTool.id)}
          permissionMode={mode}
          onToggleApproval={toggleApproval}
          onClose={() => setSelectedTool(undefined)}
        />
      )}
    </div>
  );
}

function ToolList({
  tools,
  approvedToolIds = [],
  permissionMode,
  onToggleApproval,
  onSelect,
}: {
  tools: ToolDefinition[];
  approvedToolIds?: string[];
  permissionMode?: string;
  onToggleApproval?: (toolId: string) => void;
  onSelect: (tool: ToolDefinition) => void;
}) {
  if (!tools.length) return <Empty text="暂无工具" />;
  return (
    <div className="switch-list">
      {tools.map((tool) => {
        const risk = riskClass(tool.riskLevel);
        const approved = approvedToolIds.includes(tool.id);
        return (
          <div
            className="switch-card clickable-card"
            key={tool.id}
            role="button"
            tabIndex={0}
            onClick={() => onSelect(tool)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                onSelect(tool);
              }
            }}
          >
            <span className={`switch-status-dot ${risk === 'danger' ? 'failed' : 'ready'}`} />
            <div className="switch-card-main">
              <div className="switch-card-title">
                <strong>{tool.name || tool.id}</strong>
                <span className="mono">{tool.id}</span>
                <span className={`pill ${risk}`}>{tool.riskLevel || 'unknown'}</span>
                {approved && <span className="pill success">已批准</span>}
              </div>
              <p>{short(tool.description, 180) || '暂无描述'}</p>
              <small className="muted">{toolPermissionLabel(tool, permissionMode, approvedToolIds)}</small>
            </div>
            {onToggleApproval && (
              <button
                type="button"
                className="icon-button"
                title={approved ? '从审批白名单移除' : '加入审批白名单'}
                aria-label={approved ? '从审批白名单移除' : '加入审批白名单'}
                onClick={(event) => {
                  event.stopPropagation();
                  onToggleApproval(tool.id);
                }}
              >
                <ShieldCheck size={16} />
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

function ToolDetailModal({
  tool,
  approved = false,
  permissionMode,
  onToggleApproval,
  onClose,
}: {
  tool: ToolDefinition;
  approved?: boolean;
  permissionMode?: string;
  onToggleApproval?: (toolId: string) => void;
  onClose: () => void;
}) {
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal-shell compact-modal" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>系统工具详情</h3>
            <small className="muted mono">{tool.id}</small>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><X size={16} /></button>
        </div>
        <div className="detail-modal-body">
          <section className="detail-section">
            <h4>基础信息</h4>
            <div className="detail-grid">
              <span>工具ID</span><strong className="mono">{tool.id}</strong>
              <span>名称</span><strong>{tool.name || '-'}</strong>
              <span>风险</span><strong><span className={`pill ${riskClass(tool.riskLevel)}`}>{tool.riskLevel || 'unknown'}</span></strong>
              <span>审批结论</span><strong>{toolPermissionLabel(tool, permissionMode, approved ? [tool.id] : [])}</strong>
            </div>
          </section>
          <section className="detail-section">
            <h4>描述</h4>
            <p className="detail-text">{tool.description || '暂无描述'}</p>
          </section>
          {onToggleApproval && (
            <section className="detail-section">
              <h4>审批配置</h4>
              <p className="detail-text">这里维护的是本地 custom 模式的高危工具白名单，保存后由后端策略接口持久化。</p>
              <button type="button" onClick={() => onToggleApproval(tool.id)}>
                {approved ? '从白名单移除' : '加入白名单'}
              </button>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}

function ToolTable({ tools, compact = false }: { tools: ToolDefinition[]; compact?: boolean }) {
  if (!tools.length) return <Empty text="暂无工具" />;
  return (
    <Table>
      <thead>
        <tr>
          <th>工具ID</th>
          <th>名称</th>
          <th>风险</th>
          {!compact && <th>描述</th>}
        </tr>
      </thead>
      <tbody>
        {tools.map((tool) => (
          <tr key={tool.id}>
            <td className="mono">{tool.id}</td>
            <td>{tool.name || '-'}</td>
            <td><span className={`pill ${riskClass(tool.riskLevel)}`}>{tool.riskLevel || 'unknown'}</span></td>
            {!compact && <td>{short(tool.description, 120)}</td>}
          </tr>
        ))}
      </tbody>
    </Table>
  );
}

function McpManager({
  servers,
  importJson,
  updating,
  message,
  onImportJsonChange,
  onImport,
  onRefresh,
  onConnection,
  onDelete,
  onSave,
}: {
  servers: McpServerRegistration[];
  importJson: string;
  updating?: string;
  message?: string;
  onImportJsonChange: (value: string) => void;
  onImport: () => void;
  onRefresh: () => void;
  onConnection: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
  onDelete?: (serverId: string) => void;
  onSave?: (serverId: string, config: McpServerConfig) => Promise<void>;
}) {
  const [selectedServer, setSelectedServer] = useState<McpServerRegistration | undefined>();
  return (
    <div className="stack">
      <div className="management-editor">
        <label className="form-field">
          <span>导入 MCP JSON</span>
          <textarea
            value={importJson}
            onChange={(event) => onImportJsonChange(event.target.value)}
            placeholder='{"mcpServers":{"anysearch":{"type":"streamable-http","url":"https://api.anysearch.com/mcp","headers":{}}}}'
          />
        </label>
        <div className="form-actions inline-actions">
          <button type="button" disabled={updating === 'import' || !importJson.trim()} onClick={onImport}>
            {updating === 'import' ? '导入中...' : '导入并连接'}
          </button>
          <button type="button" onClick={onRefresh}>刷新</button>
        </div>
        {message && <div className="config-message">{message}</div>}
      </div>
      <McpList servers={servers} updating={updating} onConnection={onConnection} onDelete={onDelete} onSelect={setSelectedServer} />
      {selectedServer && <McpDetailModal server={selectedServer} onClose={() => setSelectedServer(undefined)} onSave={onSave} />}
    </div>
  );
}

function McpList({
  servers,
  updating,
  onConnection,
  onDelete,
  onSelect,
}: {
  servers: McpServerRegistration[];
  updating?: string;
  onConnection?: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
  onDelete?: (serverId: string) => void;
  onSelect?: (server: McpServerRegistration) => void;
}) {
  if (!servers.length) return <Empty text="暂无 MCP Server" />;
  return (
    <div className="switch-list">
      {servers.map((server, index) => {
          const config = server.config || {};
          const id = server.id || config.id || `mcp-${index}`;
          const transport = config.transport || config.type || config.transportType || '-';
          const endpointOrCommand = config.endpoint || config.url || config.command || '-';
          const connected = server.connected || server.status === 'CONNECTED';
          return (
            <div
              className="switch-card clickable-card"
              key={id}
              title={server.message || undefined}
              role="button"
              tabIndex={0}
              onClick={() => onSelect?.(server)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onSelect?.(server);
                }
              }}
            >
              <span className={`switch-status-dot ${connected ? 'ready' : server.status === 'FAILED' ? 'failed' : 'disabled'}`} />
              <div className="switch-card-main">
                <div className="switch-card-title">
                  <strong>{server.name || config.name || id}</strong>
                  <span className={`pill ${connected ? 'success' : server.status === 'FAILED' ? 'danger' : 'neutral'}`}>{server.status || '-'}</span>
                  <span className="pill neutral">{transport}</span>
                  <span className="pill neutral">{server.tools?.length ?? 0} tools</span>
                </div>
                <p className="mono">{short(endpointOrCommand, 120)}</p>
                {server.message && <small>{server.message}</small>}
              </div>
              <div className="switch-card-actions" onClick={(event) => event.stopPropagation()}>
                <ToggleSwitch
                  checked={connected}
                  disabled={updating === `connect:${id}` || updating === `disconnect:${id}`}
                  label={connected ? '断开 MCP' : '连接 MCP'}
                  onChange={() => onConnection?.(id, connected ? 'disconnect' : 'connect')}
                />
                <button className="tiny-button" disabled={!connected || updating === `refresh:${id}`} onClick={() => onConnection?.(id, 'refresh')}>刷新工具</button>
                {onDelete && (
                  <button className="icon-button danger" disabled={updating === `delete:${id}`} onClick={() => onDelete(id)} aria-label="删除 MCP Server">
                    <Trash2 size={14} />
                  </button>
                )}
              </div>
            </div>
          );
        })}
    </div>
  );
}

function McpDetailModal({
  server,
  onClose,
  onSave,
}: {
  server: McpServerRegistration;
  onClose: () => void;
  onSave?: (serverId: string, config: McpServerConfig) => Promise<void>;
}) {
  const config = server.config || {};
  const id = server.id || config.id || '-';
  const transport = config.transport || config.type || config.transportType || '-';
  const endpointOrCommand = config.endpoint || config.url || config.command || '-';
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(() => prettyJson(config));
  const [autoApproveDraft, setAutoApproveDraft] = useState(() => joinConfigLines(config.autoApprove || []));
  const [error, setError] = useState<string>();
  const saveDraft = async () => {
    if (!onSave || id === '-') return;
    try {
      setError(undefined);
      const parsed = JSON.parse(draft) as McpServerConfig;
      await onSave(id, { ...parsed, id });
      setAutoApproveDraft(joinConfigLines(parsed.autoApprove || []));
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };
  const saveAutoApprove = async () => {
    if (!onSave || id === '-') return;
    try {
      setError(undefined);
      // MCP 审批白名单是高频配置项，单独保存能避免用户为了 autoApprove 手写整段 JSON。
      const nextConfig = { ...config, id, autoApprove: splitConfigLines(autoApproveDraft) };
      await onSave(id, nextConfig);
      setDraft(prettyJson(nextConfig));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal-shell compact-modal" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>MCP Server 详情</h3>
            <small className="muted mono">{id}</small>
          </div>
          <div className="inline-actions">
            {onSave && (
              <button type="button" className="tiny-button" onClick={() => setEditing((value) => !value)}>
                {editing ? '查看详情' : '编辑配置'}
              </button>
            )}
            <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><X size={16} /></button>
          </div>
        </div>
        <div className="detail-modal-body">
          {editing && (
            <section className="detail-section">
              <h4>编辑配置</h4>
              <textarea className="json-editor-textarea" value={draft} onChange={(event) => setDraft(event.target.value)} />
              {error && <div className="config-message danger">{error}</div>}
              <div className="form-actions inline-actions">
                <button type="button" onClick={saveDraft}>保存配置</button>
                <button type="button" onClick={() => { setDraft(prettyJson(config)); setEditing(false); setError(undefined); }}>取消</button>
              </div>
            </section>
          )}
          <section className="detail-section">
            <h4>基础信息</h4>
            <div className="detail-grid">
              <span>名称</span><strong>{server.name || config.name || id}</strong>
              <span>状态</span><strong><span className={`pill ${server.status === 'CONNECTED' ? 'success' : server.status === 'FAILED' ? 'danger' : 'neutral'}`}>{server.status || '-'}</span></strong>
              <span>传输</span><strong>{transport}</strong>
              <span>工具数</span><strong>{server.tools?.length ?? 0}</strong>
              <span>注册时间</span><strong>{formatDateTime(server.registeredAt)}</strong>
              <span>说明</span><strong>{server.message || '-'}</strong>
            </div>
          </section>
          <section className="detail-section">
            <h4>连接配置</h4>
            <div className="detail-grid">
              <span>Endpoint/Command</span><strong className="mono">{endpointOrCommand}</strong>
              <span>工作目录</span><strong className="mono">{config.cwd || '-'}</strong>
              <span>超时秒数</span><strong>{config.timeoutSeconds ?? '-'}</strong>
              <span>启用状态</span><strong>{config.enabled === false || config.disabled ? '禁用' : '启用'}</strong>
            </div>
          </section>
          <section className="detail-section">
            <h4>参数与授权</h4>
            {onSave && (
              <div className="inline-policy-editor">
                <label className="form-field">
                  <span>MCP autoApprove</span>
                  <textarea
                    value={autoApproveDraft}
                    onChange={(event) => setAutoApproveDraft(event.target.value)}
                    placeholder={"每行一个 MCP tool 名称或规则\nsearch\nfilesystem.read"}
                  />
                </label>
                <div className="form-actions inline-actions">
                  <button type="button" onClick={saveAutoApprove}>保存审批配置</button>
                </div>
                <p className="muted compact-text">
                  命中的 MCP 工具会按低风险注册；未命中的外部 MCP 工具默认按高风险进入审批链路。
                </p>
              </div>
            )}
            <pre className="json-block detail-json">{prettyJson({
              args: config.args || [],
              headers: config.headers || {},
              env: config.env || {},
              autoApprove: config.autoApprove || [],
              tools: server.tools || [],
            })}</pre>
          </section>
        </div>
      </div>
    </div>
  );
}

function splitConfigLines(value: string) {
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean);
}

function joinConfigLines(values?: string[]) {
  return (values || []).join('\n');
}

function parseKeyValueLines(value: string) {
  const result: Record<string, string> = {};
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .forEach((line) => {
      const index = line.indexOf('=');
      if (index <= 0) return;
      const key = line.slice(0, index).trim();
      const itemValue = line.slice(index + 1).trim();
      if (key) result[key] = itemValue;
    });
  return result;
}

function joinKeyValueLines(values?: Record<string, string>) {
  return Object.entries(values || {})
    .map(([key, value]) => `${key}=${value}`)
    .join('\n');
}

function upsertMetadata(values: Record<string, string>, key: string, value: string) {
  const next = { ...values };
  const normalized = value.trim();
  if (normalized) {
    next[key] = normalized;
  } else {
    delete next[key];
  }
  // 专用字段只维护平台凭证，adapter 标识仍由内置模板或 Metadata 高级区决定。
  return next;
}

function splitProjectCommandLines(value: string) {
  const result: Record<string, string[]> = {};
  value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .forEach((line) => {
      const match = line.match(/^(.+?)\s*=>\s*(.+)$/);
      if (!match) return;
      const projectPath = match[1].trim();
      const command = match[2].trim();
      if (!projectPath || !command) return;
      result[projectPath] = [...(result[projectPath] || []), command];
    });
  return result;
}

function joinProjectCommandLines(values?: Record<string, string[]>) {
  return Object.entries(values || {})
    .flatMap(([projectPath, commands]) => (commands || []).map((command) => `${projectPath} => ${command}`))
    .join('\n');
}

function buildSetupWizardSteps(draft: ModelConfigUpdate, config?: RuntimeConfigSnapshot, health?: LocalHealthView): SetupWizardStep[] {
  const healthItems = health?.items || [];
  const healthItem = (key: string) => healthItems.find((item) => item.key === key);
  const workspaceReady = Boolean((draft.localWorkspaceRoot || '').trim()) && Boolean(draft.localAllowedRoots?.length);
  const modelReady = Boolean(config?.effectiveModel?.apiKeyConfigured && config.effectiveModel?.baseUrl && config.effectiveModel?.model);
  const permissionReady = Boolean(draft.localPermissionMode);
  const healthReady = health?.status === 'UP';
  const integrationReady = ['mcp', 'skill'].every((key) => ['ok', 'warning'].includes(healthItem(key)?.status || ''));
  return [
    { label: '工作区', done: workspaceReady, detail: draft.localWorkspaceRoot || '.clawagent/workspace' },
    { label: '模型', done: modelReady, detail: config?.effectiveModel?.model || draft.model || '-' },
    { label: '权限', done: permissionReady, detail: draft.localPermissionMode || 'ask' },
    { label: '健康检查', done: healthReady, detail: health ? localHealthText(health.status) : '未检查' },
    { label: 'MCP / Skill', done: integrationReady, detail: `${healthItem('mcp')?.summary || 'MCP 未检查'} / ${healthItem('skill')?.summary || 'Skill 未检查'}` },
  ];
}

function SetupWizardContent({
  steps,
  health,
  healthLoading,
  saving,
  onHealthRefresh,
  onSave,
}: {
  steps: SetupWizardStep[];
  health?: LocalHealthView;
  healthLoading: boolean;
  saving: boolean;
  onHealthRefresh: (deep?: boolean) => void;
  onSave: () => void;
}) {
  const actionItems = (health?.items || []).filter((item) => item.status === 'error' || item.status === 'warning');
  return (
    <div className="setup-wizard">
      <div className="setup-wizard-steps">
        {steps.map((step) => (
          <div className={`setup-wizard-step ${step.done ? 'done' : ''}`} key={step.label}>
            <span className="setup-wizard-dot" />
            <strong>{step.label}</strong>
            <small>{step.detail}</small>
          </div>
        ))}
      </div>
      <div className="setup-wizard-health">
        <strong>需处理项</strong>
        {actionItems.length ? (
          <div className="local-health-list compact">
            {actionItems.map((item) => (
              <div className="local-health-row" key={item.key || item.label}>
                <span className={`pill ${localHealthClass(item.status)}`}>{localHealthText(item.status)}</span>
                <div>
                  <strong>{item.label || item.key || '-'}</strong>
                  <p>{item.summary || '-'}</p>
                  {item.detail && <code>{item.detail}</code>}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p>{health ? '关键本地配置已通过普通健康检查。' : '先运行普通检查，查看需要处理的本地配置项。'}</p>
        )}
      </div>
      <div className="setup-wizard-actions">
        <button type="button" onClick={() => onHealthRefresh(false)} disabled={healthLoading}>
          <RefreshCw size={14} />普通检查
        </button>
        <button type="button" onClick={() => onHealthRefresh(true)} disabled={healthLoading}>
          深度检查模型
        </button>
        <button type="button" onClick={onSave} disabled={saving}>
          {saving ? '保存中...' : '保存配置'}
        </button>
      </div>
    </div>
  );
}

function CostConfigPanel({
  draft,
  config,
  saving,
  message,
  onChange,
  onSave,
}: {
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  message?: string;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
}) {
  const rules = draft.costRules || {};
  const modelOptions = Object.entries(config?.models || {})
    .map(([id, model]) => model.model || id)
    .filter((value, index, all) => value && all.indexOf(value) === index);
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const updateRule = (model: string, patch: Partial<CostRuleView>) => {
    update({ costRules: { ...rules, [model]: { ...rules[model], ...patch } } });
  };
  const addRule = () => {
    const candidate = modelOptions.find((model) => !rules[model]) || `model-${Object.keys(rules).length + 1}`;
    update({ costRules: { ...rules, [candidate]: { inputPerMillion: 0, outputPerMillion: 0, currency: draft.costCurrency || 'USD' } } });
  };
  const renameRule = (oldModel: string, nextModel: string) => {
    const model = nextModel.trim();
    if (!model || model === oldModel) return;
    const next = { ...rules };
    const value = next[oldModel];
    delete next[oldModel];
    next[model] = value;
    update({ costRules: next });
  };
  const removeRule = (model: string) => {
    const next = { ...rules };
    delete next[model];
    update({ costRules: next });
  };

  return (
    <Panel
      title="成本规则"
      action={<span className="muted">按模型配置每百万 Token 单价，只用于管理台估算。</span>}
    >
      <form
        className="config-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        <div className="form-grid">
          <label className="form-field">
            <span>默认币种</span>
            <input
              value={draft.costCurrency || 'USD'}
              onChange={(event) => update({ costCurrency: event.target.value.toUpperCase() })}
              placeholder="USD"
            />
          </label>
          <div className="form-field">
            <span>规则数量</span>
            <button type="button" onClick={addRule}><Plus size={14} />新增模型规则</button>
          </div>
        </div>
        <div className="cost-rule-list">
          {Object.entries(rules).map(([model, rule]) => (
            <div className="cost-rule-row" key={model}>
              <label className="form-field">
                <span>模型 ID / 模型名</span>
                <input defaultValue={model} onBlur={(event) => renameRule(model, event.target.value)} list="cost-model-options" />
              </label>
              <label className="form-field">
                <span>输入 / 百万 Token</span>
                <input
                  type="number"
                  min="0"
                  step="0.0001"
                  value={rule.inputPerMillion ?? 0}
                  onChange={(event) => updateRule(model, { inputPerMillion: Number(event.target.value) })}
                />
              </label>
              <label className="form-field">
                <span>输出 / 百万 Token</span>
                <input
                  type="number"
                  min="0"
                  step="0.0001"
                  value={rule.outputPerMillion ?? 0}
                  onChange={(event) => updateRule(model, { outputPerMillion: Number(event.target.value) })}
                />
              </label>
              <label className="form-field">
                <span>币种</span>
                <input
                  value={rule.currency || draft.costCurrency || 'USD'}
                  onChange={(event) => updateRule(model, { currency: event.target.value.toUpperCase() })}
                />
              </label>
              <button className="icon-button danger" type="button" onClick={() => removeRule(model)} aria-label="删除成本规则"><Trash2 size={15} /></button>
            </div>
          ))}
          {!Object.keys(rules).length && (
            <div className="empty-state">暂无成本规则。新增模型规则后，Token 页会按模型估算成本。</div>
          )}
        </div>
        <datalist id="cost-model-options">
          {modelOptions.map((model) => <option key={model} value={model} />)}
        </datalist>
        <p className="muted compact-text">
          成本估算不会修改原始 Token 记录。模型名优先匹配 Token 统计里的 byModel key；未匹配到价格规则时，页面只展示 Token，不展示成本。
        </p>
        {message && <div className="config-message">{message}</div>}
        <div className="form-actions">
          <button className="send-button" type="submit" disabled={saving}>{saving ? '保存中...' : '保存成本规则'}</button>
        </div>
      </form>
    </Panel>
  );
}

function CapabilityConfigPanel({
  tools,
  draft,
  config,
  saving,
  message,
  onChange,
  onSave,
}: {
  tools: ToolDefinition[];
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  message?: string;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
}) {
  const mode = draft.localPermissionMode || config?.local?.permissionMode || 'ask';
  const approvedToolIds = draft.localApprovedToolIds || config?.local?.approvedToolIds || [];
  const workspaceRoot = draft.localWorkspaceRoot || config?.local?.workspaceRoot || '.clawagent/workspace';
  const defaultShell = draft.localDefaultShell || config?.local?.defaultShell || defaultShellOption();
  const allowedRoots = draft.localAllowedRoots || config?.local?.allowedRoots || [];
  const sensitivePathPatterns = draft.localSensitivePathPatterns || config?.local?.sensitivePathPatterns || [];
  const policy = config?.policy;
  const capabilityRows = useMemo(() => BUILTIN_CAPABILITIES.map((capability) => {
    const matchedTools = capabilityTools(capability, tools);
    return {
      capability,
      tools: matchedTools,
      enabled: capabilityEnabled(matchedTools),
      risk: capabilityRisk(matchedTools),
    };
  }), [tools]);
  const matchedToolIds = new Set(capabilityRows.flatMap((row) => row.tools.map((tool) => tool.id)));
  const otherTools = tools.filter((tool) => !matchedToolIds.has(tool.id));
  const highRiskTools = tools.filter((tool) => (tool.riskLevel || '').toLowerCase() === 'high');
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const toggleApprovedTool = (toolId: string) => {
    const next = approvedToolIds.includes(toolId)
      ? approvedToolIds.filter((item) => item !== toolId)
      : [...approvedToolIds, toolId];
    update({ localApprovedToolIds: next });
  };

  return (
    <div className="stack">
      <Panel
        title="内置能力"
        action={<span className="muted">基于当前已注册工具自动归类；未注册能力只作为后续桌面化预留。</span>}
      >
        <div className="capability-summary">
          <div><strong>{tools.length}</strong><span>已注册工具</span></div>
          <div><strong>{capabilityRows.filter((row) => row.enabled).length}</strong><span>已启用能力域</span></div>
          <div><strong>{highRiskTools.length}</strong><span>高风险工具</span></div>
          <div><strong>{mode}</strong><span>当前权限模式</span></div>
        </div>
        <div className="capability-grid">
          {capabilityRows.map(({ capability, tools: capabilityToolList, enabled, risk }) => (
            <section className="model-config-card capability-card" key={capability.id}>
              <div className="model-config-card-head">
                <strong>{capability.title}</strong>
                <span className={`pill ${enabled ? 'success' : 'neutral'}`}>{enabled ? '已注册' : '预留'}</span>
              </div>
              <p className="compact-text">{capability.description}</p>
              <div className="capability-meta">
                <span>风险</span><strong><span className={`pill ${riskClass(risk)}`}>{risk}</span></strong>
                <span>默认参数</span><strong>{capability.defaultParams}</strong>
                <span>审计策略</span><strong>{capability.auditPolicy}</strong>
              </div>
              <div className="capability-tool-list">
                {capabilityToolList.length ? capabilityToolList.map((tool) => (
                  <label className="capability-tool-row" key={tool.id} title={tool.description || tool.id}>
                    <input
                      type="checkbox"
                      disabled={mode !== 'custom' || (tool.riskLevel || '').toLowerCase() !== 'high'}
                      checked={(tool.riskLevel || '').toLowerCase() !== 'high' || approvedToolIds.includes(tool.id)}
                      onChange={() => toggleApprovedTool(tool.id)}
                    />
                    <span className="mono">{tool.id}</span>
                    <em className={`pill ${riskClass(tool.riskLevel)}`}>{tool.riskLevel || 'unknown'}</em>
                    <small>{toolPermissionLabel(tool, mode, approvedToolIds)}</small>
                  </label>
                )) : (
                  <div className="empty-state compact">当前没有匹配的内置工具。</div>
                )}
              </div>
            </section>
          ))}
        </div>
      </Panel>
      <Panel
        title="能力权限配置"
        action={<span className={`pill ${mode === 'ask' ? 'warning' : mode === 'full' ? 'danger' : 'success'}`}>{mode}</span>}
      >
        <form
          className="config-form"
          onSubmit={(event) => {
            event.preventDefault();
            onSave();
          }}
        >
          <div className="form-grid">
            <label className="form-field">
              <span>权限模式</span>
              <select value={mode} onChange={(event) => update({ localPermissionMode: event.target.value })}>
                <option value="ask">请求批准</option>
                <option value="auto">替我审批</option>
                <option value="full">完全访问</option>
                <option value="custom">自定义</option>
              </select>
            </label>
            <label className="form-field">
              <span>高危工具白名单</span>
              <textarea
                value={joinConfigLines(approvedToolIds)}
                onChange={(event) => update({ localApprovedToolIds: splitConfigLines(event.target.value) })}
                placeholder={"仅 custom 模式使用；每行一个工具 ID\nbuiltin.execute.command\nbuiltin.process.start"}
              />
            </label>
          </div>
          <p className="muted compact-text">
            ask 模式下高危工具等待用户确认；auto 模式会自动批准明确高危工具，但风险分类、allowed roots、敏感路径拦截和审计仍然强制执行；custom 模式只默认放行白名单里的高危工具。
          </p>
          <details className="advanced-config policy-field-editor" open>
            <summary>本地权限规则</summary>
            <div className="form-grid">
              <label className="form-field">
                <span>默认工作区</span>
                <input
                  value={workspaceRoot}
                  onChange={(event) => update({ localWorkspaceRoot: event.target.value })}
                  placeholder=".clawagent/workspace"
                />
              </label>
              <label className="form-field">
                <span>默认 Shell</span>
                <select value={defaultShell} onChange={(event) => update({ localDefaultShell: event.target.value })}>
                  <option value="powershell">PowerShell</option>
                  <option value="pwsh">PowerShell 7</option>
                  <option value="cmd">cmd</option>
                  <option value="sh">sh</option>
                  <option value="bash">bash</option>
                </select>
              </label>
            </div>
            <div className="form-grid">
              <label className="form-field">
                <span>允许访问目录</span>
                <textarea
                  value={joinConfigLines(allowedRoots)}
                  onChange={(event) => update({ localAllowedRoots: splitConfigLines(event.target.value) })}
                  placeholder={"每行一个 allowed root；会同步到 execute/filesystem\n.clawagent/workspace\nD:\\workspace\\project-a"}
                />
              </label>
              <label className="form-field">
                <span>敏感路径规则</span>
                <textarea
                  value={joinConfigLines(sensitivePathPatterns)}
                  onChange={(event) => update({ localSensitivePathPatterns: splitConfigLines(event.target.value) })}
                  placeholder={"每行一个 glob；filesystem 直接拦截，execute 命中后升为高危审批\n**/.env\n**/*.pem\n**/.ssh/**"}
                />
              </label>
            </div>
            <p className="muted compact-text">
              这些字段会同步到本地工具环境：allowed roots 是 execute/filesystem 的访问边界；敏感路径会拦截文件读取，并让命令执行进入高危审批。
            </p>
          </details>
          {policy && (
            <section className="policy-snapshot">
              <div className="model-config-card-head">
                <strong>当前生效策略</strong>
                <span className="pill neutral">{policy.approval?.source || 'local'}</span>
              </div>
              <div className="policy-snapshot-grid">
                <div><span>审批模式</span><strong>{policy.approval?.mode || mode}</strong></div>
                <div><span>作用域</span><strong>{policy.approval?.scope || 'local'}</strong></div>
                <div><span>高危自动批准</span><strong>{policy.approval?.autoApprovesHighRisk ? '是' : '否'}</strong></div>
                <div><span>完全访问</span><strong>{policy.approval?.fullAccess ? '是' : '否'}</strong></div>
                <div><span>默认目录</span><strong className="mono">{policy.permission?.defaultCwd || config?.local?.workspaceRoot || '-'}</strong></div>
              </div>
              {policy.approval?.overrideReason && (
                <p className="muted compact-text">策略覆盖原因：{policy.approval.overrideReason}</p>
              )}
              {(policy.approval?.conflictNotes || []).length > 0 && (
                <p className="muted compact-text">策略冲突提示：{policy.approval?.conflictNotes?.join('；')}</p>
              )}
              <div className="policy-snapshot-columns">
                <div>
                  <small>有效规则</small>
                  <ul>
                    {(policy.effectiveRules || []).map((rule) => <li key={rule}>{rule}</li>)}
                  </ul>
                </div>
                <div>
                  <small>允许根目录</small>
                  <ul>
                    {(policy.permission?.allowedRoots || []).map((root) => <li className="mono" key={root}>{root}</li>)}
                  </ul>
                </div>
              </div>
              {(policy.resolutionOrder || []).length > 0 && (
                <div className="policy-snapshot-columns">
                  <div>
                    <small>策略解析顺序</small>
                    <ul>
                      {(policy.resolutionOrder || []).map((layer) => (
                        <li key={`${layer.order || 0}-${layer.key || layer.source}`}>
                          <span className="mono">{layer.order || '-'}</span>
                          {' '}
                          <strong>{layer.scope || layer.key || '-'}</strong>
                          {' '}
                          <span className={`pill ${layer.status === 'active' ? 'success' : 'neutral'}`}>{layer.status || '-'}</span>
                          <br />
                          <span>{layer.description || layer.source || '-'}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                  <div>
                    <small>策略来源</small>
                    <ul>
                      {(policy.resolutionOrder || []).map((layer) => (
                        <li className="mono" key={`${layer.key || layer.source}-source`}>{layer.source || '-'}</li>
                      ))}
                    </ul>
                  </div>
                </div>
              )}
              {(policy.pendingEnhancements || []).length > 0 && (
                <p className="muted compact-text">后续增强：{policy.pendingEnhancements?.join('、')}</p>
              )}
            </section>
          )}
          {otherTools.length > 0 && (
            <details className="advanced-config">
              <summary>未归类工具 {otherTools.length} 个</summary>
              <ToolTable tools={otherTools} compact />
            </details>
          )}
          {message && <div className="config-message">{message}</div>}
          <div className="form-actions">
            <button className="send-button" type="submit" disabled={saving}>{saving ? '保存中...' : '保存能力权限'}</button>
          </div>
        </form>
      </Panel>
    </div>
  );
}

function LocalConfigPanel({
  draft,
  config,
  saving,
  message,
  health,
  healthLoading,
  onChange,
  onSave,
  onHealthRefresh,
}: {
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  message?: string;
  health?: LocalHealthView;
  healthLoading: boolean;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
  onHealthRefresh: (deep?: boolean) => void;
}) {
  const [wizardOpen, setWizardOpen] = useState(true);
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const wizardSteps = buildSetupWizardSteps(draft, config, health);
  return (
    <div className="stack">
      <Panel
        title="本地部署向导"
        action={(
          <div className="panel-actions">
            <span className={`pill ${wizardSteps.every((step) => step.done) ? 'success' : 'warning'}`}>
              {wizardSteps.filter((step) => step.done).length}/{wizardSteps.length}
            </span>
            <button type="button" onClick={() => setWizardOpen((value) => !value)}>
              {wizardOpen ? <ChevronUp size={14} /> : <ChevronDown size={14} />}
              {wizardOpen ? '收起' : '展开'}
            </button>
          </div>
        )}
      >
        {wizardOpen && (
          <SetupWizardContent
            steps={wizardSteps}
            health={health}
            healthLoading={healthLoading}
            saving={saving}
            onHealthRefresh={onHealthRefresh}
            onSave={onSave}
          />
        )}
      </Panel>
      <Panel
        title="本地健康检查"
        action={(
          <div className="panel-actions">
            <span className={`pill ${localHealthClass(health?.status)}`}>{localHealthText(health?.status)}</span>
            <button type="button" onClick={() => onHealthRefresh(false)} disabled={healthLoading}>
              <RefreshCw size={14} />{healthLoading ? '检查中...' : '重新检查'}
            </button>
            <button type="button" onClick={() => onHealthRefresh(true)} disabled={healthLoading}>
              深度检查
            </button>
          </div>
        )}
      >
        <div className="local-health-list">
          {(health?.items || []).map((item) => (
            <div className="local-health-row" key={item.key || item.label}>
              <span className={`pill ${localHealthClass(item.status)}`}>{localHealthText(item.status)}</span>
              <div>
                <strong>{item.label || item.key || '-'}</strong>
                <p>{item.summary || '-'}</p>
                {item.detail && <code>{item.detail}</code>}
              </div>
            </div>
          ))}
          {!health?.items?.length && (
            <div className="empty-state">{healthLoading ? '正在检查本地配置...' : '暂无健康检查结果。'}</div>
          )}
        </div>
      </Panel>
      <Panel title="本地配置目录" action={<span className="muted">保存到本地覆盖 YAML，部分运行时配置立即用于页面和摘要。</span>}>
        <div className="definition-list">
          <div><span>工作目录</span><strong className="mono">{config?.cwd || '-'}</strong></div>
          <div><span>配置根</span><strong className="mono">{config?.configRoot || '.clawagent'}</strong></div>
          <div><span>覆盖 YAML</span><strong className="mono">{config?.configPath || '.clawagent/config/clawagent.yml'}</strong></div>
        </div>
      </Panel>
      <Panel title="本地开发任务" action={<span className="pill neutral">workspace / test</span>}>
        <form
          className="config-form"
          onSubmit={(event) => {
            event.preventDefault();
            onSave();
          }}
        >
          <label className="form-field">
            <span>默认工作区</span>
            <input
              value={draft.localWorkspaceRoot || ''}
              onChange={(event) => update({ localWorkspaceRoot: event.target.value })}
              placeholder=".clawagent/workspace"
            />
          </label>
          <label className="form-field">
            <span>允许访问目录</span>
            <textarea
              value={joinConfigLines(draft.localAllowedRoots)}
              onChange={(event) => update({ localAllowedRoots: splitConfigLines(event.target.value) })}
              placeholder={"每行一个 allowed root；会同步到 execute/filesystem\n.clawagent/workspace\nD:\\workspace\\project-a"}
            />
          </label>
          <div className="form-grid">
            <label className="form-field">
              <span>默认 Shell</span>
              <select
                value={draft.localDefaultShell || defaultShellOption()}
                onChange={(event) => update({ localDefaultShell: event.target.value })}
              >
                <option value="powershell">PowerShell</option>
                <option value="pwsh">PowerShell 7</option>
                <option value="cmd">cmd</option>
                <option value="sh">sh</option>
                <option value="bash">bash</option>
              </select>
            </label>
            <label className="form-field">
              <span>权限模式</span>
              <select
                value={draft.localPermissionMode || 'ask'}
                onChange={(event) => update({ localPermissionMode: event.target.value })}
              >
                <option value="ask">请求批准</option>
                <option value="auto">替我审批</option>
                <option value="full">完全访问</option>
                <option value="custom">自定义</option>
              </select>
            </label>
          </div>
          <label className="form-field">
            <span>自定义批准工具</span>
            <textarea
              value={joinConfigLines(draft.localApprovedToolIds)}
              onChange={(event) => update({ localApprovedToolIds: splitConfigLines(event.target.value) })}
              placeholder={"每行一个工具 ID；仅 custom 模式默认使用\nbuiltin.execute.command\nbuiltin.process.start"}
            />
          </label>
          <div className="form-grid">
            <label className="form-field">
              <span>最近项目目录</span>
              <textarea
                value={joinConfigLines(draft.localRecentProjects)}
                onChange={(event) => update({ localRecentProjects: splitConfigLines(event.target.value) })}
                placeholder={"每行一个项目路径\nD:\\workspace\\project-a"}
              />
            </label>
            <label className="form-field">
              <span>测试命令</span>
              <textarea
                value={joinConfigLines(draft.localTestCommands)}
                onChange={(event) => update({ localTestCommands: splitConfigLines(event.target.value) })}
                placeholder={"每行一个验证命令\nmvn test\nnpm test\npytest"}
              />
            </label>
          </div>
          <label className="form-field">
            <span>项目测试命令</span>
            <textarea
              value={joinProjectCommandLines(draft.localProjectTestCommands)}
              onChange={(event) => update({ localProjectTestCommands: splitProjectCommandLines(event.target.value) })}
              placeholder={"每行一个映射：项目路径 => 验证命令\nD:\\workspace\\project-a => mvn test\nD:\\workspace\\project-b => npm run build"}
            />
          </label>
          <label className="form-field">
            <span>工作区忽略规则</span>
            <textarea
              value={joinConfigLines(draft.localIgnorePatterns)}
              onChange={(event) => update({ localIgnorePatterns: splitConfigLines(event.target.value) })}
              placeholder={"每行一个 glob；用于搜索、文件审查和摘要\n**/.git/**\n**/node_modules/**\n**/target/**"}
            />
          </label>
          <label className="form-field">
            <span>敏感路径规则</span>
            <textarea
              value={joinConfigLines(draft.localSensitivePathPatterns)}
              onChange={(event) => update({ localSensitivePathPatterns: splitConfigLines(event.target.value) })}
              placeholder={"每行一个 glob；filesystem 直接拦截，execute 命中后升为高危审批\n**/.env\n**/*.pem\n**/.ssh/**"}
            />
          </label>
          <p className="muted compact-text">
            允许访问目录会同步到底层 execute/filesystem 工具；项目测试命令会按当前任务项目路径优先匹配；忽略规则只影响批量搜索、文件审查和摘要，敏感路径规则会阻止 filesystem 直接访问，并让 execute 命令进入高危审批。
          </p>
          {message && <div className="config-message">{message}</div>}
          <div className="form-actions">
            <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存本地配置'}</button>
          </div>
        </form>
      </Panel>
    </div>
  );
}

function ModelConfigPanel({
  draft,
  config,
  saving,
  testing,
  testResult,
  message,
  onChange,
  onSave,
  onSaveModel,
  onTestModel,
}: {
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  testing: boolean;
  testResult?: ModelApiTestResponse;
  message?: string;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
  onSaveModel: (request: ModelConfigUpsertRequest) => void;
  onTestModel: (request: ModelConfigUpsertRequest) => void;
}) {
  const [showChatKey, setShowChatKey] = useState(false);
  const [showNewModelDialog, setShowNewModelDialog] = useState(false);
  const [newModel, setNewModel] = useState<ModelConfigUpsertRequest>({
    id: '',
    provider: '',
    baseUrl: '',
    model: '',
    apiKey: '',
    temperature: 0.2,
    timeoutSeconds: 60,
    vision: false,
  });
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const configuredModels = config?.models || {};
  const modelProfiles = [
    {
      id: 'deepseek-v4-flash',
      label: 'DeepSeek V4 Flash',
      patch: {
        client: 'openai-compatible',
        provider: 'deepseek',
        baseUrl: 'https://api.deepseek.com',
        model: 'deepseek-v4-flash',
        temperature: 0.2,
        timeoutSeconds: 60,
        vision: false,
      },
    },
    {
      id: 'siliconflow-qwen3-8b',
      label: 'SiliconFlow Qwen3-8B 免费',
      patch: {
        client: 'openai-compatible',
        provider: 'siliconflow',
        baseUrl: 'https://api.siliconflow.cn/v1',
        model: 'Qwen/Qwen3-8B',
        temperature: 0.2,
        timeoutSeconds: 60,
        vision: false,
      },
    },
    {
      id: 'qwen3-vl',
      label: 'DashScope Qwen3-VL',
      patch: {
        client: 'openai-compatible',
        provider: 'dashscope',
        baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
        model: 'qwen3-vl-flash',
        temperature: 0.2,
        timeoutSeconds: 60,
        vision: true,
      },
    },
  ];
  const modelOptions = [
    ...modelProfiles.map((profile) => ({
      ...profile,
      // 同名模型以服务端配置为准，预设只负责提供展示名和默认兜底值。
      patch: { ...profile.patch, ...(configuredModels[profile.id] || {}) },
    })),
    ...Object.entries(configuredModels)
      .filter(([id]) => !modelProfiles.some((profile) => profile.id === id))
      .map(([id, model]) => ({ id, label: `${id} (${model.model || 'custom'})`, patch: model })),
  ];
  const applyModelProfile = (profileId: string, target: 'chat' | 'memory' | 'vision') => {
    if (!profileId && target === 'vision') {
      update({ visionModel: '' });
      return;
    }
    const profile = modelOptions.find((item) => item.id === profileId);
    if (!profile) return;
    const patch = profile.patch as Partial<ModelConfigUpdate>;
    update({
      ...(target === 'chat'
        ? {
          defaultModel: profile.id,
          client: 'openai-compatible',
          provider: patch.provider || '',
          baseUrl: patch.baseUrl || '',
          model: patch.model || profile.id,
          apiKey: patch.apiKey || '',
          temperature: patch.temperature ?? draft.temperature,
          timeoutSeconds: patch.timeoutSeconds ?? draft.timeoutSeconds,
          vision: patch.vision ?? false,
        }
        : target === 'memory'
          ? { memoryModel: profile.id }
          : { visionModel: profile.id }),
    });
  };
  const testCurrentModel = () => onTestModel({
    id: draft.defaultModel || draft.model || 'current',
    provider: draft.provider,
    baseUrl: draft.baseUrl,
    model: draft.model,
    apiKey: draft.apiKey || config?.effectiveModel?.apiKey || '',
    temperature: draft.temperature,
    timeoutSeconds: draft.timeoutSeconds,
    vision: draft.vision,
  });
  return (
    <Panel
      title="模型 API"
      action={
        <div className="inline-actions">
          <button type="button" onClick={() => setShowNewModelDialog(true)}><Plus size={14} />添加模型</button>
          <span className={`pill ${config?.restartRequired ? 'warning' : 'neutral'}`}>{config?.restartRequired ? '重启生效' : '当前配置'}</span>
        </div>
      }
    >
      <form
        className="config-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        <div className="model-config-grid two">
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>聊天模型</strong>
              <span className="pill neutral">{draft.model || draft.defaultModel || '-'}</span>
            </div>
            <label className="form-field">
              <span>模型</span>
              <select value={draft.defaultModel || ''} onChange={(event) => applyModelProfile(event.target.value, 'chat')}>
                <option value="">请选择聊天模型</option>
                {modelOptions.map((profile) => <option key={profile.id} value={profile.id}>{profile.label}</option>)}
              </select>
            </label>
            <label className="form-field">
              <span>API Key</span>
              <div className="secret-input-row">
                <input type={showChatKey ? 'text' : 'password'} value={draft.apiKey || ''} onChange={(event) => update({ apiKey: event.target.value })} placeholder="请输入 API Key" />
                <button type="button" onClick={() => setShowChatKey((current) => !current)}>{showChatKey ? '隐藏' : '查看'}</button>
              </div>
            </label>
            <details className="advanced-config">
              <summary>高级参数</summary>
              <div className="form-grid">
                <label className="form-field">
                  <span>Provider</span>
                  <input value={draft.provider || ''} onChange={(event) => update({ provider: event.target.value })} />
                </label>
                <label className="form-field">
                  <span>Base URL</span>
                  <input value={draft.baseUrl || ''} onChange={(event) => update({ baseUrl: event.target.value })} />
                </label>
                <label className="form-field">
                  <span>真实模型名</span>
                  <input value={draft.model || ''} onChange={(event) => update({ model: event.target.value })} />
                </label>
                <label className="form-field">
                  <span>Temperature</span>
                  <input type="number" min="0" max="2" step="0.1" value={draft.temperature ?? 0.2} onChange={(event) => update({ temperature: Number(event.target.value) })} />
                </label>
                <label className="form-field">
                  <span>超时秒数</span>
                  <input type="number" min="1" step="1" value={draft.timeoutSeconds ?? 60} onChange={(event) => update({ timeoutSeconds: Number(event.target.value) })} />
                </label>
                <label className="form-field checkbox-field">
                  <span>支持图片输入</span>
                  <input type="checkbox" checked={!!draft.vision} onChange={(event) => update({ vision: event.target.checked })} />
                </label>
              </div>
            </details>
            <div className="inline-actions">
              <button type="button" disabled={testing || !draft.baseUrl || !draft.model || !(draft.apiKey || config?.effectiveModel?.apiKey)} onClick={testCurrentModel}>
                {testing ? '测试中...' : '在线测试'}
              </button>
            </div>
          </section>
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>记忆模型</strong>
              <span className="pill neutral">{draft.memoryModel || '复用聊天模型'}</span>
            </div>
            <label className="form-field">
              <span>模型</span>
              <select value={draft.memoryModel || draft.defaultModel || ''} onChange={(event) => applyModelProfile(event.target.value, 'memory')}>
                <option value="">复用聊天模型</option>
                {modelOptions.map((profile) => <option key={profile.id} value={profile.id}>{profile.label}</option>)}
              </select>
            </label>
            <p className="muted compact-text">用于判断哪些内容值得进入长期记忆。通常可以选择便宜模型。</p>
          </section>
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>图片理解模型</strong>
              <span className="pill neutral">{draft.visionModel || '未配置'}</span>
            </div>
            <label className="form-field">
              <span>模型</span>
              <select value={draft.visionModel || ''} onChange={(event) => applyModelProfile(event.target.value, 'vision')}>
                <option value="">不启用图片理解</option>
                {modelOptions.map((profile) => <option key={profile.id} value={profile.id}>{profile.label}</option>)}
              </select>
            </label>
            <p className="muted compact-text">默认模型支持图片输入时会直接看图；否则图片附件会先交给该模型生成文字描述，再进入主对话链路。</p>
          </section>
        </div>
        <details className="advanced-config">
          <summary>运行参数</summary>
          <div className="form-grid">
            <label className="form-field">
              <span>运行模式</span>
              <select value={draft.mode || 'llm'} onChange={(event) => update({ mode: event.target.value })}>
                <option value="llm">llm</option>
                <option value="rule">rule</option>
              </select>
            </label>
            <label className="form-field">
              <span>Planner</span>
              <select value={draft.planner || 'react'} onChange={(event) => update({ planner: event.target.value })}>
                <option value="react">react</option>
                <option value="llm">llm</option>
                <option value="rule">rule</option>
              </select>
            </label>
            <label className="form-field">
              <span>模型客户端</span>
              <select value={draft.client || 'openai-compatible'} onChange={(event) => update({ client: event.target.value })}>
                <option value="openai-compatible">openai-compatible</option>
                <option value="spring-ai">spring-ai</option>
              </select>
            </label>
          </div>
        </details>
        <div className="config-note">
          Chat API Key 保存到本地配置；当前状态：
          <strong>{config?.effectiveModel?.apiKeyConfigured ? '已配置' : '未检测到'}</strong>
        </div>
        <div className="config-note">
          保存路径：<strong className="mono">{config?.configPath || '.clawagent/config/clawagent.yml'}</strong>
        </div>
        {testResult && (
          <div className={`config-message ${testResult.success ? 'success-message' : ''}`}>
            <strong>{testResult.success ? '测试通过' : '测试失败'}</strong>
            <span> · HTTP {testResult.statusCode ?? 0} · {testResult.elapsedMs ?? 0}ms · Prompt {testResult.promptTokens ?? 0} / Completion {testResult.completionTokens ?? 0} / Total {testResult.totalTokens ?? 0}</span>
            <p>{testResult.message || '-'}</p>
            {testResult.rawError && <pre className="inline-error-block">{testResult.rawError}</pre>}
          </div>
        )}
        {message && <div className="config-message">{message}</div>}
        <div className="form-actions">
          <button className="send-button" type="submit" disabled={saving}>{saving ? '保存中...' : '保存模型配置'}</button>
        </div>
      </form>
      {showNewModelDialog && (
        <div className="modal-backdrop" onClick={() => setShowNewModelDialog(false)}>
          <div className="modal-shell compact-modal" onClick={(event) => event.stopPropagation()}>
            <div className="modal-head">
              <h3>添加模型</h3>
              <button className="tiny-button" type="button" onClick={() => setShowNewModelDialog(false)}><X size={14} />关闭</button>
            </div>
            <div className="config-form">
              <div className="form-grid">
                <label className="form-field">
                  <span>模型 ID</span>
                  <input value={newModel.id} onChange={(event) => setNewModel({ ...newModel, id: event.target.value })} placeholder="例如 siliconflow-qwen3-32b" />
                </label>
                <label className="form-field">
                  <span>Provider</span>
                  <input value={newModel.provider || ''} onChange={(event) => setNewModel({ ...newModel, provider: event.target.value })} placeholder="siliconflow" />
                </label>
                <label className="form-field">
                  <span>Base URL</span>
                  <input value={newModel.baseUrl || ''} onChange={(event) => setNewModel({ ...newModel, baseUrl: event.target.value })} placeholder="https://api.siliconflow.cn/v1" />
                </label>
                <label className="form-field checkbox-field">
                  <span>支持图片输入</span>
                  <input type="checkbox" checked={!!newModel.vision} onChange={(event) => setNewModel({ ...newModel, vision: event.target.checked })} />
                </label>
                <label className="form-field">
                  <span>真实模型名</span>
                  <input value={newModel.model || ''} onChange={(event) => setNewModel({ ...newModel, model: event.target.value })} placeholder="Qwen/..." />
                </label>
                <label className="form-field wide">
                  <span>API Key</span>
                  <input type="password" value={newModel.apiKey || ''} onChange={(event) => setNewModel({ ...newModel, apiKey: event.target.value })} placeholder="不填则复用当前聊天模型 Key" />
                </label>
              </div>
              <div className="form-actions inline-actions">
                <button type="button" disabled={testing || !newModel.baseUrl || !newModel.model || !(newModel.apiKey || draft.apiKey)} onClick={() => onTestModel({ ...newModel, apiKey: newModel.apiKey || draft.apiKey || '' })}>测试</button>
                <button className="send-button" type="button" disabled={saving || !newModel.id.trim()} onClick={() => {
                  onSaveModel({ ...newModel, apiKey: newModel.apiKey || draft.apiKey || '' });
                  setShowNewModelDialog(false);
                }}>保存模型</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </Panel>
  );
}

function EmbeddingConfigPanel({
  draft,
  config,
  saving,
  knowledgeVectorStatus,
  memoryVectorStatus,
  message,
  onChange,
  onSave,
}: {
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  knowledgeVectorStatus: VectorStatusView[];
  memoryVectorStatus: VectorStatusView[];
  message?: string;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
}) {
  const [showEmbeddingKey, setShowEmbeddingKey] = useState(false);
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const embeddingProfiles = [
    {
      id: 'siliconflow-bge-m3',
      label: 'SiliconFlow BGE-M3 免费',
      patch: {
        embeddingProvider: 'siliconflow',
        embeddingBaseUrl: 'https://api.siliconflow.cn/v1',
        embeddingModel: 'BAAI/bge-m3',
        embeddingDimensions: 0,
        embeddingTimeoutSeconds: 60,
      },
    },
  ];
  const applyEmbeddingProfile = (profileId: string) => {
    const profile = embeddingProfiles.find((item) => item.id === profileId);
    if (profile) update({ ...profile.patch, embeddingApiKey: draft.embeddingApiKey || config?.embedding?.apiKey || '' });
  };
  const knowledgeReady = knowledgeVectorStatus.filter((item) => item.vectorized).length;
  const memoryReady = memoryVectorStatus.filter((item) => item.vectorized).length;
  return (
    <Panel
      title="向量模型"
      action={<span className={`pill ${config?.embedding?.apiKeyConfigured ? 'success' : 'warning'}`}>{config?.embedding?.apiKeyConfigured ? '已配置' : '未配置'}</span>}
    >
      <form
        className="config-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        <div className="model-config-grid two">
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>Embedding API</strong>
              <span className="pill neutral">{draft.embeddingModel || '-'}</span>
            </div>
            <label className="form-field">
              <span>模型</span>
              <select value={draft.embeddingModel ? 'siliconflow-bge-m3' : ''} onChange={(event) => applyEmbeddingProfile(event.target.value)}>
                <option value="">请选择向量模型</option>
                {embeddingProfiles.map((profile) => <option key={profile.id} value={profile.id}>{profile.label}</option>)}
              </select>
            </label>
            <label className="form-field">
              <span>API Key</span>
              <div className="secret-input-row">
                <input type={showEmbeddingKey ? 'text' : 'password'} value={draft.embeddingApiKey || ''} onChange={(event) => update({ embeddingApiKey: event.target.value })} placeholder="请输入 Embedding API Key" />
                <button type="button" onClick={() => setShowEmbeddingKey((current) => !current)}>{showEmbeddingKey ? '隐藏' : '查看'}</button>
              </div>
            </label>
            <div className="form-grid">
              <label className="form-field">
                <span>Provider</span>
                <input value={draft.embeddingProvider || ''} onChange={(event) => update({ embeddingProvider: event.target.value })} />
              </label>
              <label className="form-field">
                <span>Base URL</span>
                <input value={draft.embeddingBaseUrl || ''} onChange={(event) => update({ embeddingBaseUrl: event.target.value })} />
              </label>
              <label className="form-field">
                <span>真实模型名</span>
                <input value={draft.embeddingModel || ''} onChange={(event) => update({ embeddingModel: event.target.value })} />
              </label>
              <label className="form-field">
                <span>维度</span>
                <input type="number" min="0" step="1" value={draft.embeddingDimensions ?? 0} onChange={(event) => update({ embeddingDimensions: Number(event.target.value) })} />
              </label>
              <label className="form-field">
                <span>超时秒数</span>
                <input type="number" min="1" step="1" value={draft.embeddingTimeoutSeconds ?? 60} onChange={(event) => update({ embeddingTimeoutSeconds: Number(event.target.value) })} />
              </label>
            </div>
          </section>
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>向量化状态</strong>
              <span className="pill neutral">chunk/vector</span>
            </div>
            <div className="vector-status-summary">
              <div><span>知识库</span><strong>{knowledgeReady}/{knowledgeVectorStatus.length}</strong></div>
              <div><span>记忆</span><strong>{memoryReady}/{memoryVectorStatus.length}</strong></div>
            </div>
            <VectorStatusList title="知识库文档" items={knowledgeVectorStatus} />
            <VectorStatusList title="长期记忆" items={memoryVectorStatus} />
          </section>
        </div>
        <div className="config-note">
          Embedding API Key 保存到本地配置；当前状态：
          <strong>{config?.embedding?.apiKeyConfigured ? '已配置' : '未检测到'}</strong>
        </div>
        {message && <div className="config-message">{message}</div>}
        <div className="form-actions">
          <button className="send-button" type="submit" disabled={saving}>{saving ? '保存中...' : '保存向量模型配置'}</button>
        </div>
      </form>
    </Panel>
  );
}

function MemoryExtractionConfigPanel({
  draft,
  config,
  saving,
  message,
  onChange,
  onSave,
}: {
  draft: ModelConfigUpdate;
  config?: RuntimeConfigSnapshot;
  saving: boolean;
  message?: string;
  onChange: (draft: ModelConfigUpdate) => void;
  onSave: () => void;
}) {
  const update = (patch: Partial<ModelConfigUpdate>) => onChange({ ...draft, ...patch });
  const extraction = config?.memoryExtraction || {};
  const governance = config?.memoryGovernance || {};
  const isBatchMode = draft.memoryExtractionMode === 'batch';
  return (
    <Panel
      title="记忆处理"
      action={<span className={`pill ${draft.memoryExtractionEnabled === false ? 'warning' : 'success'}`}>{draft.memoryExtractionEnabled === false ? '已关闭' : '已启用'}</span>}
    >
      <form
        className="config-form"
        onSubmit={(event) => {
          event.preventDefault();
          onSave();
        }}
      >
        <div className="model-config-grid two">
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>触发方式</strong>
              <span className="pill neutral">候选记忆</span>
            </div>
            <label className="checkbox-line">
              <input
                type="checkbox"
                checked={draft.memoryExtractionEnabled ?? true}
                onChange={(event) => update({ memoryExtractionEnabled: event.target.checked })}
              />
              <span>启用候选记忆提炼</span>
            </label>
            <div className="radio-card-group">
              <label className={`radio-card ${draft.memoryExtractionMode !== 'batch' ? 'selected' : ''}`}>
                <input
                  type="radio"
                  name="memoryExtractionMode"
                  checked={draft.memoryExtractionMode !== 'batch'}
                  onChange={() => update({ memoryExtractionMode: 'after-task-async' })}
                />
                <span>
                  <strong>任务异步处理</strong>
                  <small>每轮任务结束后立即放到后台处理。</small>
                </span>
              </label>
              <label className={`radio-card ${draft.memoryExtractionMode === 'batch' ? 'selected' : ''}`}>
                <input
                  type="radio"
                  name="memoryExtractionMode"
                  checked={draft.memoryExtractionMode === 'batch'}
                  onChange={() => update({ memoryExtractionMode: 'batch' })}
                />
                <span>
                  <strong>定时增量处理</strong>
                  <small>按定时间隔或累计条数触发批处理。</small>
                </span>
              </label>
            </div>
            <p className="muted compact-text">这是二选一策略。聊天主链路只负责入队，后台再调用记忆模型判断是否生成 pending 记忆。</p>
          </section>
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>{isBatchMode ? '批处理参数' : '当前策略'}</strong>
              <span className="pill neutral">{isBatchMode ? `${draft.memoryExtractionBatchSize ?? extraction.batchSize ?? 100} 条/批` : '无参数'}</span>
            </div>
            {isBatchMode ? (
              <>
                <div className="form-grid">
                  <label className="form-field">
                    <span>定时间隔秒数</span>
                    <input type="number" min="1" step="1" value={draft.memoryExtractionIntervalSeconds ?? 60} onChange={(event) => update({ memoryExtractionIntervalSeconds: Number(event.target.value) })} />
                  </label>
                  <label className="form-field">
                    <span>批次大小</span>
                    <input type="number" min="1" step="1" value={draft.memoryExtractionBatchSize ?? 100} onChange={(event) => update({ memoryExtractionBatchSize: Number(event.target.value) })} />
                  </label>
                </div>
                <p className="muted compact-text">达到批次大小会立即触发后台消费；定时任务负责处理未满批次的增量内容。</p>
              </>
            ) : (
              <div className="strategy-summary">
                <strong>任务完成后立即进入后台队列</strong>
                <p>当前策略没有业务参数。每轮任务结束后只做快速入队，后台线程随后处理候选记忆，不阻塞聊天回复。</p>
              </div>
            )}
          </section>
          <section className="model-config-card">
            <div className="model-config-card-head">
              <strong>长期记忆治理</strong>
              <span className={`pill ${draft.memoryGovernanceAutoArchiveEnabled ? 'success' : 'neutral'}`}>
                {draft.memoryGovernanceAutoArchiveEnabled ? '自动归档' : '只评分'}
              </span>
            </div>
            <div className="form-grid">
              <label className="form-field">
                <span>开始降权天数</span>
                <input type="number" min="0" step="1" value={draft.memoryGovernanceStaleAfterDays ?? governance.staleAfterDays ?? 30} onChange={(event) => update({ memoryGovernanceStaleAfterDays: Number(event.target.value) })} />
              </label>
              <label className="form-field">
                <span>最大降权天数</span>
                <input type="number" min="1" step="1" value={draft.memoryGovernanceVeryStaleAfterDays ?? governance.veryStaleAfterDays ?? 180} onChange={(event) => update({ memoryGovernanceVeryStaleAfterDays: Number(event.target.value) })} />
              </label>
              <label className="form-field">
                <span>未命中归档天数</span>
                <input type="number" min="0" step="1" value={draft.memoryGovernanceArchiveAfterDays ?? governance.archiveAfterDays ?? 365} onChange={(event) => update({ memoryGovernanceArchiveAfterDays: Number(event.target.value) })} />
              </label>
              <label className="form-field">
                <span>质量归档阈值</span>
                <input type="number" min="0" max="1" step="0.01" value={draft.memoryGovernanceArchiveBelowQuality ?? governance.archiveBelowQuality ?? 0.15} onChange={(event) => update({ memoryGovernanceArchiveBelowQuality: Number(event.target.value) })} />
              </label>
            </div>
            <label className="checkbox-line">
              <input
                type="checkbox"
                checked={draft.memoryGovernanceAutoArchiveEnabled ?? governance.autoArchiveEnabled ?? false}
                onChange={(event) => update({ memoryGovernanceAutoArchiveEnabled: event.target.checked })}
              />
              <span>启用自动归档 active 长期记忆</span>
            </label>
            <p className="muted compact-text">关闭时只更新 qualityScore、staleDays 等指标，不自动改变记忆状态。</p>
          </section>
        </div>
        {message && <div className="config-message">{message}</div>}
        <div className="form-actions">
          <button className="send-button" type="submit" disabled={saving}>{saving ? '保存中...' : '保存记忆配置'}</button>
        </div>
      </form>
    </Panel>
  );
}

function VectorStatusList({ title, items }: { title: string; items: VectorStatusView[] }) {
  return (
    <div className="vector-status-list">
      <strong>{title}</strong>
      {items.length === 0 ? (
        <small className="muted">暂无数据</small>
      ) : items.slice(0, 8).map((item) => (
        <div key={item.id}>
          <span title={item.name || item.id}>{short(item.name || item.id, 28)}</span>
          <em className={`pill ${item.vectorized ? 'success' : 'warning'}`}>{item.vectorCount ?? 0}/{item.chunkCount ?? 0}</em>
        </div>
      ))}
    </div>
  );
}

function SkillManager({
  skills,
  installText,
  updatingSkillId,
  message,
  onInstallTextChange,
  onInstall,
  onRefresh,
  onToggle,
  onDelete,
  onSave,
}: {
  skills: SkillRegistration[];
  installText: string;
  updatingSkillId?: string;
  message?: string;
  onInstallTextChange: (value: string) => void;
  onInstall: () => void;
  onRefresh: () => void;
  onToggle?: (skillId: string, enabled: boolean) => void;
  onDelete?: (skillId: string) => void;
  onSave?: (skillId: string, manifest: NonNullable<SkillRegistration['manifest']>) => Promise<void>;
}) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<'all' | 'ready' | 'disabled'>('all');
  const [selectedSkill, setSelectedSkill] = useState<SkillRegistration | undefined>();
  const readyCount = skills.filter((skill) => skill.manifest?.enabled).length;
  const disabledCount = skills.length - readyCount;
  const normalizedQuery = query.trim().toLowerCase();
  const visibleSkills = skills.filter((skill) => {
    const manifest = skill.manifest || {};
    const enabled = Boolean(manifest.enabled);
    if (filter === 'ready' && !enabled) return false;
    if (filter === 'disabled' && enabled) return false;
    if (!normalizedQuery) return true;
    return [
      manifest.id,
      manifest.name,
      manifest.description,
      skill.installedPath,
    ].filter(Boolean).some((value) => String(value).toLowerCase().includes(normalizedQuery));
  });
  return (
    <div className="stack">
      <div className="management-editor">
        <label className="form-field">
          <span>安装 Skill</span>
          <textarea
            value={installText}
            onChange={(event) => onInstallTextChange(event.target.value)}
            placeholder="粘贴 GitHub 仓库 URL、SKILL.md 原文，或 { manifest, content, resourceFiles } JSON 包"
          />
        </label>
        <div className="form-actions inline-actions">
          <button type="button" disabled={updatingSkillId === 'install' || !installText.trim()} onClick={onInstall}>
            {updatingSkillId === 'install' ? '安装中...' : '安装并加载'}
          </button>
          <button type="button" disabled={updatingSkillId === 'refresh'} onClick={onRefresh}>
            {updatingSkillId === 'refresh' ? '刷新中...' : '刷新目录'}
          </button>
        </div>
        {message && <div className="config-message">{message}</div>}
      </div>
      <div className="switch-list-toolbar">
        <div className="segmented-tabs">
          <button className={filter === 'all' ? 'active' : undefined} onClick={() => setFilter('all')}>All {skills.length}</button>
          <button className={filter === 'ready' ? 'active' : undefined} onClick={() => setFilter('ready')}>Ready {readyCount}</button>
          <button className={filter === 'disabled' ? 'active' : undefined} onClick={() => setFilter('disabled')}>Disabled {disabledCount}</button>
        </div>
        <span className="muted">{visibleSkills.length} shown</span>
      </div>
      <input className="switch-list-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Filter installed skills" />
      <SkillList skills={visibleSkills} updatingSkillId={updatingSkillId} onToggle={onToggle} onDelete={onDelete} onSelect={setSelectedSkill} />
      {selectedSkill && <SkillDetailModal skill={selectedSkill} onClose={() => setSelectedSkill(undefined)} onSave={onSave} />}
    </div>
  );
}

function SkillList({
  skills,
  updatingSkillId,
  onToggle,
  onDelete,
  onSelect,
}: {
  skills: SkillRegistration[];
  updatingSkillId?: string;
  onToggle?: (skillId: string, enabled: boolean) => void;
  onDelete?: (skillId: string) => void;
  onSelect?: (skill: SkillRegistration) => void;
}) {
  if (!skills.length) return <Empty text="暂无 Skill" />;
  return (
    <div className="switch-list">
      {skills.map((skill, index) => {
          const manifest = skill.manifest || {};
          const id = manifest.id || `skill-${index}`;
          const enabled = Boolean(manifest.enabled);
          return (
            <div
              className="switch-card clickable-card"
              key={id}
              role="button"
              tabIndex={0}
              onClick={() => onSelect?.(skill)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault();
                  onSelect?.(skill);
                }
              }}
            >
              <span className={`switch-status-dot ${enabled ? 'ready' : 'disabled'}`} />
              <div className="switch-card-main">
                <div className="switch-card-title">
                  <strong>{manifest.name || id}</strong>
                  <span className="mono">{id}</span>
                  <span className={`pill ${enabled ? 'success' : 'neutral'}`}>{skill.status || (enabled ? 'enabled' : 'disabled')}</span>
                  <span className="pill neutral">{manifest.tools?.length ?? 0} tools</span>
                </div>
                <p>{short(manifest.description, 160) || '暂无描述'}</p>
                <small className="mono">{skill.installedPath || '-'}</small>
              </div>
              {(onToggle || onDelete) && (
                <div className="switch-card-actions" onClick={(event) => event.stopPropagation()}>
                  {onToggle && (
                  <ToggleSwitch
                    checked={enabled}
                    disabled={updatingSkillId === id}
                    label={enabled ? '禁用 Skill' : '启用 Skill'}
                    onChange={() => onToggle(id, enabled)}
                  />
                  )}
                  {onDelete && (
                    <button className="icon-button danger" disabled={updatingSkillId === `delete:${id}`} onClick={() => onDelete(id)} aria-label="删除 Skill">
                      <Trash2 size={14} />
                    </button>
                  )}
                </div>
              )}
            </div>
          );
        })}
    </div>
  );
}

function SkillDetailModal({
  skill,
  onClose,
  onSave,
}: {
  skill: SkillRegistration;
  onClose: () => void;
  onSave?: (skillId: string, manifest: NonNullable<SkillRegistration['manifest']>) => Promise<void>;
}) {
  const manifest = skill.manifest || {};
  const id = manifest.id || '-';
  const enabled = Boolean(manifest.enabled);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(() => prettyJson(manifest));
  const [error, setError] = useState<string>();
  const saveDraft = async () => {
    if (!onSave || id === '-') return;
    try {
      setError(undefined);
      const parsed = JSON.parse(draft) as NonNullable<SkillRegistration['manifest']>;
      await onSave(id, { ...parsed, id });
      setEditing(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  };
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal-shell compact-modal" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>Skill 详情</h3>
            <small className="muted mono">{id}</small>
          </div>
          <div className="inline-actions">
            {onSave && (
              <button type="button" className="tiny-button" onClick={() => setEditing((value) => !value)}>
                {editing ? '查看详情' : '编辑 Manifest'}
              </button>
            )}
            <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><X size={16} /></button>
          </div>
        </div>
        <div className="detail-modal-body">
          {editing && (
            <section className="detail-section">
              <h4>编辑 Manifest</h4>
              <textarea className="json-editor-textarea" value={draft} onChange={(event) => setDraft(event.target.value)} />
              {error && <div className="config-message danger">{error}</div>}
              <div className="form-actions inline-actions">
                <button type="button" onClick={saveDraft}>保存 Manifest</button>
                <button type="button" onClick={() => { setDraft(prettyJson(manifest)); setEditing(false); setError(undefined); }}>取消</button>
              </div>
            </section>
          )}
          <section className="detail-section">
            <h4>基础信息</h4>
            <div className="detail-grid">
              <span>名称</span><strong>{manifest.name || id}</strong>
              <span>版本</span><strong>{manifest.version || '-'}</strong>
              <span>状态</span><strong><span className={`pill ${enabled ? 'success' : 'neutral'}`}>{skill.status || (enabled ? 'enabled' : 'disabled')}</span></strong>
              <span>启用状态</span><strong>{enabled ? '启用' : '禁用'}</strong>
              <span>安装时间</span><strong>{formatDateTime(skill.installedAt)}</strong>
              <span>入口文件</span><strong className="mono">{manifest.entrypoint || '-'}</strong>
              <span>安装目录</span><strong className="mono">{skill.installedPath || '-'}</strong>
              <span>说明</span><strong>{skill.message || '-'}</strong>
            </div>
          </section>
          <section className="detail-section">
            <h4>描述</h4>
            <p className="detail-text">{manifest.description || '暂无描述'}</p>
          </section>
          <section className="detail-section">
            <h4>工具与权限</h4>
            <pre className="json-block detail-json">{prettyJson({
              tools: manifest.tools || [],
              permissions: manifest.permissions || [],
              metadata: manifest.metadata || {},
            })}</pre>
          </section>
        </div>
      </div>
    </div>
  );
}

function ToggleSwitch({
  checked,
  disabled,
  label,
  onChange,
}: {
  checked: boolean;
  disabled?: boolean;
  label: string;
  onChange: () => void;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      aria-pressed={checked}
      className={`toggle-switch ${checked ? 'on' : ''}`}
      disabled={disabled}
      onClick={onChange}
    >
      <span />
    </button>
  );
}

function Table({ children }: { children: React.ReactNode }) {
  return <div className="table-wrap"><table>{children}</table></div>;
}
