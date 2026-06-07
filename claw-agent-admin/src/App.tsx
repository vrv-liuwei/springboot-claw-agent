import {
  BarChart3,
  Bot,
  Check,
  CheckCircle,
  ChevronDown,
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
  Plug,
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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api';
import type {
  AgentEvent,
  AgentMessage,
  AgentSession,
  AgentTask,
  AttachmentParseResult,
  AutomationDefinition,
  AutomationRun,
  AutomationUpsertRequest,
  HealthStatus,
  KnowledgeDocument,
  KnowledgeProviderView,
  KnowledgeSearchHit,
  MemoryHitLog,
  MemoryItem,
  MemorySearchHit,
  MemoryUpsertRequest,
  McpServerConfig,
  ModelApiTestResponse,
  ModelConfigUpsertRequest,
  McpServerRegistration,
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

const BRAND_LOGO_URL = '/admin/brand/clawagent-logo.svg';
const AGENT_AVATAR_URL = '/admin/brand/clawagent-avatar.svg';
const USER_AVATAR_URL = '/admin/brand/user-avatar.svg';

type NavKey = 'chat' | 'overview' | 'sessions' | 'automations' | 'knowledge' | 'memory' | 'skills' | 'config' | 'logs' | 'nodes';
type DetailTab = 'tasks' | 'messages' | 'events' | 'todos' | 'tokens';
type ToolStatus = 'running' | 'completed' | 'failed';
type KnowledgeSearchMode = 'keyword' | 'vector' | 'hybrid';
type MemorySearchMode = 'keyword' | 'vector' | 'hybrid';
type ConfigTab = 'models' | 'embedding' | 'memory' | 'local';
type SkillTab = 'tools' | 'mcp' | 'skills';

type SystemLogFilter = {
  from: string;
  to: string;
  level: string;
  keyword: string;
  logger: string;
  userId: string;
  sessionId: string;
  taskId: string;
  limit: number;
};

type ToolCallView = {
  stepId?: string;
  toolId?: string;
  status: ToolStatus;
  outputLength?: number;
  elapsedMs?: number;
  error?: string;
  todoTitle?: string;
  message?: string;
};

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  attachments?: ChatAttachment[];
  taskId?: string;
  status?: string;
  progress?: string;
  createdAt: number;
  finishedAt?: number;
  durationMs?: number;
  tokenUsage?: TokenUsageSummary;
  toolCalls: ToolCallView[];
  toolsCollapsed: boolean;
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
  loading: boolean;
  error?: string;
};

type ApprovalSettings = {
  allowHighRiskTools: boolean;
  approvedToolIds: string[];
};

type ApprovalMode = 'ask' | 'auto' | 'full';

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
    limit: 200,
  };
}

function mergeNestedTokenUsage(
  target: Record<string, TokenUsageSummary> | undefined,
  source: Record<string, TokenUsageSummary> | undefined,
) {
  const merged: Record<string, TokenUsageSummary> = { ...(target || {}) };
  Object.entries(source || {}).forEach(([key, value]) => {
    merged[key] = mergeTokenUsage(merged[key] || emptyTokenUsage(), value);
  });
  return merged;
}

function mergeTokenUsage(target: TokenUsageSummary, source?: TokenUsageSummary): TokenUsageSummary {
  if (!source) return target;
  // 概述页需要展示所有会话的总消耗，这里只聚合数值字段，避免修改后端接口。
  target.callCount = (target.callCount ?? 0) + (source.callCount ?? 0);
  target.promptTokens = (target.promptTokens ?? 0) + (source.promptTokens ?? 0);
  target.completionTokens = (target.completionTokens ?? 0) + (source.completionTokens ?? 0);
  target.totalTokens = (target.totalTokens ?? 0) + (source.totalTokens ?? 0);
  target.byModel = mergeNestedTokenUsage(target.byModel, source.byModel);
  target.byPhase = mergeNestedTokenUsage(target.byPhase, source.byPhase);
  return target;
}

function aggregateTokenUsage(usages: Array<TokenUsageSummary | undefined>): TokenUsageSummary {
  const total = emptyTokenUsage();
  usages.forEach((usage) => mergeTokenUsage(total, usage));
  return total;
}

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
    ],
  },
  {
    title: '系统',
    items: [
      { key: 'config', label: '配置', icon: Settings },
      { key: 'logs', label: '日志', icon: ScrollText },
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
  config: { title: '配置', subtitle: '运行配置与本地 .clawagent 目录状态。' },
  logs: { title: '日志', subtitle: '任务事件和运行日志入口。' },
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
  if (saved === 'auto' || saved === 'full') return saved;
  return window.localStorage.getItem('clawagent.approval.allowHighRiskTools') === 'true' ? 'full' : 'ask';
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

function appendParsedAttachmentSummary(input: string, attachments: ComposerAttachment[], parsed: AttachmentParseResult[]) {
  if (!attachments.length) return input;
  const parsedByName = new Map(parsed.map((item) => [item.name || item.id, item]));
  const sections = attachments.map((attachment, index) => {
    const result = parsedByName.get(attachment.name);
    const header = `${index + 1}. ${attachment.name} (${formatFileSize(attachment.size)})`;
    if (!result) {
      return `${header}\n解析状态：未返回解析结果`;
    }
    const meta = [
      `附件ID：${result.id || '-'}`,
      `类型：${result.kind || 'document'}`,
      `存储：${result.storageProvider || '-'} ${result.storagePath || ''}`.trim(),
      `解析：${result.message || '-'}`,
      `字符：${result.extractedChars || 0}/${result.originalChars || 0}${result.truncated ? '，已截断' : ''}`,
    ].join('\n');
    if (result.kind === 'image') {
      return `${header}\n${meta}\n说明：这是图片附件，当前文本模型请求先携带图片元信息，后续可接入多模态模型读取原始文件。`;
    }
    return `${header}\n${meta}\n内容：\n${result.extractedText || ''}`;
  }).join('\n\n---\n');
  return `${input}\n\n[附件解析结果]\n${sections}`;
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
    const items = JSON.parse(raw) as Array<AttachmentParseResult & { provider?: string }>;
    return items
      .filter((item) => item?.id)
      .map((item) => {
        const image = isImageAttachment({ kind: item.kind, contentType: item.contentType });
        return {
          id: item.id,
          name: item.name || item.id,
          size: item.size || 0,
          kind: item.kind,
          contentType: item.contentType,
          previewUrl: image ? api.attachmentViewUrl(item.id) : undefined,
          viewUrl: api.attachmentViewUrl(item.id),
          downloadUrl: api.attachmentDownloadUrl(item.id),
          message: item.message,
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
  return {
    mode: model.mode || 'llm',
    client: model.client || 'openai-compatible',
    defaultModel: model.defaultModel || effective.model || '',
    memoryModel: model.memoryModel || model.defaultModel || effective.model || '',
    planner: model.planner || 'react',
    provider: effective.provider || '',
    baseUrl: effective.baseUrl || '',
    model: effective.model || model.defaultModel || '',
    apiKey: effective.apiKey || '',
    temperature: effective.temperature ?? 0.2,
    timeoutSeconds: effective.timeoutSeconds ?? 60,
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
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [events, setEvents] = useState<AgentEvent[]>([]);
  const [tokenUsage, setTokenUsage] = useState<TokenUsageSummary>();
  const [taskTokenUsages, setTaskTokenUsages] = useState<Record<string, TokenUsageSummary>>({});
  const [allSessionTokenUsage, setAllSessionTokenUsage] = useState<TokenUsageSummary>(emptyTokenUsage());
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
  const [automations, setAutomations] = useState<AutomationDefinition[]>([]);
  const [selectedAutomationId, setSelectedAutomationId] = useState<string>();
  const [automationRuns, setAutomationRuns] = useState<AutomationRun[]>([]);
  const [automationDraft, setAutomationDraft] = useState<AutomationUpsertRequest>(() => defaultAutomationDraft());
  const [automationSaving, setAutomationSaving] = useState(false);
  const [automationMessage, setAutomationMessage] = useState<string>();
  const [automationRunDetail, setAutomationRunDetail] = useState<AutomationRunDetail>();
  const [taskDetail, setTaskDetail] = useState<TaskDetail>();
  const [taskDetailTab, setTaskDetailTab] = useState<'result' | 'messages' | 'events' | 'tokens'>('result');
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
  const [modelTestResult, setModelTestResult] = useState<ModelApiTestResponse>();
  const [modelTesting, setModelTesting] = useState(false);
  const [systemLogFilter, setSystemLogFilter] = useState<SystemLogFilter>(() => defaultSystemLogFilter());
  const [systemLogs, setSystemLogs] = useState<SystemLogLine[]>([]);
  const [systemLogSources, setSystemLogSources] = useState<SystemLogSource[]>([]);
  const [systemLogLoading, setSystemLogLoading] = useState(false);
  const [systemLogMessage, setSystemLogMessage] = useState<string>();
  const [selectedSystemLog, setSelectedSystemLog] = useState<SystemLogLine>();
  const [todos, setTodos] = useState<TodoItem[]>([]);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [attachments, setAttachments] = useState<ComposerAttachment[]>([]);
  const [running, setRunning] = useState(false);
  const [approvalSettings, setApprovalSettings] = useState<ApprovalSettings>(() => readApprovalSettings());
  const [approvalMode, setApprovalMode] = useState<ApprovalMode>(() => readApprovalMode());
  const [lastTaskId, setLastTaskId] = useState<string>();
  const abortRef = useRef<AbortController>();
  const runningTaskRef = useRef<string>();
  const todoPollerRef = useRef<number>();
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

  const loadTodos = useCallback(async (sessionId = currentSessionId) => {
    if (!sessionId) {
      setTodos([]);
      return;
    }
    setTodos(await api.todos(sessionId));
  }, [currentSessionId]);

  const loadAllSessionTokenUsage = useCallback(async (sessionData: AgentSession[]) => {
    if (!sessionData.length) {
      setAllSessionTokenUsage(emptyTokenUsage());
      return;
    }
    // 概述页展示“所有已加载会话”的 Token 总量；单个会话查询失败时跳过，避免卡住首页。
    const usages = await Promise.all(
      sessionData.map((session) => api.sessionTokenUsage(session.id).catch(() => undefined)),
    );
    setAllSessionTokenUsage(aggregateTokenUsage(usages));
  }, []);

  const restoreSessionMessages = useCallback(async (sessionId: string) => {
    const history = await api.sessionMessages(sessionId, 40);
    if (!history.length) {
      setChatMessages([createAssistantMessage('已恢复最近会话。')]);
      return;
    }
    setChatMessages(history.map(agentMessageToChatMessage));
  }, []);

  const loadCore = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const [
        healthData,
        sessionData,
        toolData,
        mcpData,
        skillData,
        automationData,
        configData,
        providerData,
        knowledgeData,
        knowledgeVectorData,
        memoryData,
        memoryVectorData,
        candidateData,
        memoryHitData,
      ] = await Promise.all([
        api.health(),
        api.sessions(1000),
        api.tools(),
        api.mcpServers(),
        api.skills(),
        api.automations(),
        api.runtimeConfig(),
        api.knowledgeProviders().catch(() => [] as KnowledgeProviderView[]),
        api.knowledgeDocuments('console').catch(() => [] as KnowledgeDocument[]),
        api.knowledgeVectorStatus('console').catch(() => [] as VectorStatusView[]),
        api.memoryItems({ userId: 'console', limit: 200 }).catch(() => [] as MemoryItem[]),
        api.memoryVectorStatus('console').catch(() => [] as VectorStatusView[]),
        api.memoryCandidates('console').catch(() => [] as MemoryItem[]),
        api.memoryHits({ userId: 'console', limit: 100 }).catch(() => [] as MemoryHitLog[]),
      ]);
      setHealth(healthData);
      setSessions(sessionData);
      void loadAllSessionTokenUsage(sessionData);
      setTools(toolData);
      setMcpServers(mcpData);
      setSkills(skillData);
      setAutomations(automationData);
      setSelectedAutomationId((current) => current || automationData[0]?.id);
      setKnowledgeProviders(providerData);
      setKnowledgeDocuments(knowledgeData);
      setKnowledgeVectorStatus(knowledgeVectorData);
      setSelectedKnowledgeDocumentIds((current) => current.filter((id) => knowledgeData.some((document) => document.id === id)));
      setActiveKnowledgeDocumentIds((current) => current.filter((id) => knowledgeData.some((document) => document.id === id)));
      setMemoryItems(memoryData);
      setMemoryVectorStatus(memoryVectorData);
      setMemoryCandidates(candidateData);
      setMemoryHits(memoryHitData);
      setSelectedMemoryId((current) => current && memoryData.some((item) => item.id === current) ? current : memoryData[0]?.id);
      setRuntimeConfig(configData);
      setModelDraft(draftFromConfig(configData));
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
          setChatMessages([createAssistantMessage('已创建新的当前会话。')]);
          setTodos([]);
        }
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setLoading(false);
    }
  }, [currentSessionId, loadAllSessionTokenUsage, loadTodos, restoreSessionMessages]);

  const refreshKnowledgeDocuments = useCallback(async () => {
    setKnowledgeLoading(true);
    setKnowledgeMessage(undefined);
    try {
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

  const refreshAfterChatTask = useCallback(async (sessionId: string, refreshKnowledge: boolean) => {
    const requests: Promise<unknown>[] = [
      loadTodos(sessionId),
      api.sessions(1000)
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
      const [taskData, messageData, eventData, todoData, usageData] = await Promise.all([
        api.sessionTasks(sessionId),
        api.sessionMessages(sessionId),
        api.sessionEvents(sessionId),
        api.todos(sessionId),
        api.sessionTokenUsage(sessionId),
      ]);
      const usageEntries = await Promise.all(
        taskData.map((task) => api.taskTokenUsage(task.id).then((usage) => [task.id, usage] as const).catch(() => undefined)),
      );
      setTasks(taskData);
      setMessages(messageData);
      setEvents(eventData);
      setSessionTodos(todoData);
      setTokenUsage(usageData);
      setTaskTokenUsages(Object.fromEntries(usageEntries.filter(Boolean) as Array<readonly [string, TokenUsageSummary]>));
      setTaskDetail(undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, []);

  const viewTaskDetail = useCallback(async (task: AgentTask) => {
    setTaskDetailTab('result');
    setTaskDetail({ task, messages: [], events: [], loading: true });
    try {
      const [taskData, messageData, eventData, usageData] = await Promise.all([
        api.task(task.id),
        api.taskMessages(task.id, 100),
        api.taskEvents(task.id, 200),
        api.taskTokenUsage(task.id),
      ]);
      setTaskDetail({
        task: taskData,
        messages: messageData,
        events: eventData,
        tokenUsage: usageData,
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

  useEffect(() => {
    void loadCore();
    return () => {
      if (todoPollerRef.current) window.clearInterval(todoPollerRef.current);
    };
  }, [loadCore]);

  useEffect(() => {
    if (selectedSessionId) {
      void loadSessionDetail(selectedSessionId);
    }
  }, [loadSessionDetail, selectedSessionId]);

  useEffect(() => {
    if (active === 'logs' && !systemLogs.length && !systemLogLoading) {
      void loadSystemLogs();
    }
  }, [active, loadSystemLogs, systemLogLoading, systemLogs.length]);

  useEffect(() => {
    window.localStorage.setItem('clawagent.approval.allowHighRiskTools', String(approvalSettings.allowHighRiskTools));
    window.localStorage.setItem('clawagent.approval.approvedToolIds', JSON.stringify(approvalSettings.approvedToolIds));
    window.localStorage.setItem('clawagent.approval.mode', approvalMode);
  }, [approvalMode, approvalSettings]);

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
    setApprovalSettings((current) => ({
      ...current,
      // 现阶段后端只有高危工具放行标记，替我审批和完全访问都映射为放行高危工具。
      allowHighRiskTools: mode !== 'ask',
    }));
  }, []);

  const toggleApprovedTool = useCallback((toolId: string) => {
    setApprovalSettings((current) => {
      const exists = current.approvedToolIds.includes(toolId);
      return {
        ...current,
        approvedToolIds: exists
          ? current.approvedToolIds.filter((item) => item !== toolId)
          : [...current.approvedToolIds, toolId],
      };
    });
  }, []);

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
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        todoTitle: data.todoTitle,
        message: `正在调用 ${data.toolId || data.message || '未知工具'}`,
      }, false, `正在调用工具：${data.toolId || data.message || '未知工具'}`));
    } else if (event.name === 'tool.succeeded') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'completed',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        todoTitle: data.todoTitle,
        message: `调用成功 ${data.toolId || data.message || '未知工具'}`,
      }, false, `工具调用成功：${data.toolId || data.message || '未知工具'}`));
      void loadTodos();
    } else if (event.name === 'tool.failed') {
      updateMessage(assistantId, (message) => upsertToolCall(message, data.stepId, {
        status: 'failed',
        stepId: data.stepId,
        toolId: data.toolId || data.message,
        elapsedMs: Number(data.elapsedMs || 0),
        outputLength: Number(data.outputLength || 0),
        error: data.error || data.message,
        todoTitle: data.todoTitle,
        message: `调用失败 ${data.toolId || data.message || '未知工具'}`,
      }, false, `工具调用失败：${data.toolId || data.message || '未知工具'}`));
      void loadTodos();
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
        toolsCollapsed: true,
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

  const submit = useCallback(async () => {
    if (running) {
      await cancelRunningTask();
      return;
    }
    const text = normalizeEscapedNewlines(input).trim();
    if ((!text && !attachments.length && !selectedKnowledgeDocumentIds.length) || !currentSessionId) return;
    const submittedAttachments = attachments;
    const baseText = text || (selectedKnowledgeDocumentIds.length ? '请结合选中的知识库文件回答。' : '请处理以下附件。');
    setInput('');
    clearAttachments();
    setRunning(true);
    const startedAt = Date.now();
    const assistant = createAssistantMessage('', submittedAttachments.length ? '解析附件中...' : '规划中...');
    const userMessage = createUserMessage(baseText, toChatAttachments(submittedAttachments));
    setChatMessages((current) => [...current, userMessage, assistant]);
    startTodoPolling(currentSessionId);
    const controller = new AbortController();
    abortRef.current = controller;
    try {
      let parsedAttachments: AttachmentParseResult[] = [];
      if (submittedAttachments.length) {
        // 附件上传后只回传轻量 metadata 和 knowledgeDocumentId，正文由后端知识库检索按需注入模型上下文。
        const parsed = await api.parseAttachments(submittedAttachments.map((attachment) => attachment.file), 'console', controller.signal);
        parsedAttachments = parsed.attachments || [];
        updateMessage(userMessage.id, { attachments: toChatAttachments(submittedAttachments, parsedAttachments) });
        const [documents, vectorStatus] = await Promise.all([
          api.knowledgeDocuments('console').catch(() => knowledgeDocuments),
          api.knowledgeVectorStatus('console').catch(() => knowledgeVectorStatus),
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
      const approvedToolIds = approvalSettings.allowHighRiskTools
        ? highRiskTools.map((tool) => tool.id)
        : approvalSettings.approvedToolIds;
      const response = await api.submitStream({
        input: baseText,
        sessionId: currentSessionId,
        channelId: 'webui',
        userId: 'console',
        metadata: {
          approvalMode,
          allowHighRiskTools: String(approvalSettings.allowHighRiskTools),
          ...(parsedAttachments.length ? { attachments: JSON.stringify(attachmentMetadata(parsedAttachments)) } : {}),
          ...(parsedAttachments.length ? { attachmentIds: parsedAttachments.map((attachment) => attachment.id).filter(Boolean).join(',') } : {}),
          ...(parsedAttachments.length ? { attachmentStoragePaths: parsedAttachments.map((attachment) => attachment.storagePath).filter(Boolean).join(',') } : {}),
          ...(attachmentKnowledgeDocumentIds.length ? { attachmentKnowledgeDocumentIds: attachmentKnowledgeDocumentIds.join(',') } : {}),
          ...(requestKnowledgeDocumentIds.length ? { 'knowledge.enabled': 'true', 'knowledge.documentIds': JSON.stringify(requestKnowledgeDocumentIds) } : {}),
          ...(knowledgeScope ? { 'knowledge.scope': knowledgeScope } : {}),
          ...(approvedToolIds.length ? { approvedToolIds: approvedToolIds.join(',') } : {}),
        },
      }, controller.signal);
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
  }, [activeKnowledgeDocumentIds, approvalMode, approvalSettings.allowHighRiskTools, approvalSettings.approvedToolIds, attachments, cancelRunningTask, clearAttachments, currentSessionId, handleStreamEvent, highRiskTools, input, knowledgeDocuments, knowledgeVectorStatus, refreshAfterChatTask, running, selectedKnowledgeDocumentIds, startTodoPolling, stopTodoPolling, updateMessage]);

  const createNewSession = useCallback(async () => {
    const created = await api.createSessionId();
    setCurrentSessionId(created.sessionId);
    setSelectedSessionId(undefined);
    setLastTaskId(undefined);
    setTodos([]);
    setSessionTodos([]);
    setTasks([]);
    setMessages([]);
    setEvents([]);
    setTokenUsage(undefined);
    setTaskTokenUsages({});
    setActiveKnowledgeDocumentIds([]);
    setChatMessages([createAssistantMessage('已创建新的当前会话。')]);
  }, []);

  const saveModelConfig = useCallback(async () => {
    setConfigSaving(true);
    setConfigMessage(undefined);
    try {
      const saved = await api.saveModelConfig(modelDraft);
      setRuntimeConfig(saved);
      setModelDraft(draftFromConfig(saved));
      setConfigMessage(saved.message || '模型配置已保存，重启服务后生效。');
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

  const installSkill = useCallback(async () => {
    const text = skillInstallText.trim();
    if (!text) return;
    setSkillUpdating('install');
    setSkillMessage(undefined);
    try {
      let request: SkillInstallRequest;
      try {
        request = JSON.parse(text) as SkillInstallRequest;
      } catch {
        // 粘贴 SKILL.md 时后端会自动转换成系统 Skill manifest。
        request = { content: text };
      }
      await api.installSkill(request);
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
    void loadAutomationRuns(selectedAutomationId);
  }, [loadAutomationRuns, selectedAutomationId]);

  const allSessionTotalTokens = allSessionTokenUsage.totalTokens ?? 0;
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
            messages={chatMessages}
            todos={todos}
            lastTaskId={lastTaskId}
            highRiskTools={highRiskTools}
            approvalSettings={approvalSettings}
            approvalMode={approvalMode}
            knowledgeDocuments={knowledgeDocuments}
            selectedKnowledgeDocumentIds={selectedKnowledgeDocumentIds}
            onInputChange={(value) => setInput(normalizeEscapedNewlines(value))}
            onAddAttachments={addAttachments}
            onRemoveAttachment={removeAttachment}
            onToggleKnowledgeDocument={toggleKnowledgeDocument}
            onSetKnowledgeDocuments={setSelectedKnowledgeDocuments}
            onSubmit={() => void submit()}
            onNewSession={() => void createNewSession()}
            onRefreshTodos={() => void loadTodos()}
            onToggleTools={(messageId) => updateMessage(messageId, (message) => ({ ...message, toolsCollapsed: !message.toolsCollapsed }))}
            onApprovalModeChange={changeApprovalMode}
            onToggleApprovedTool={toggleApprovedTool}
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
                    title="会话 Token 总量"
                    value={allSessionTotalTokens}
                    desc="已加载会话聚合"
                    onClick={() => {
                      setActive('sessions');
                      setDetailTab('tokens');
                    }}
                  />
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
                  <SessionTable sessions={sessions} selectedId={selectedSessionId} onSelect={setSelectedSessionId} />
                </Panel>
                <Panel title={selectedSession ? short(selectedSession.title || selectedSession.id, 70) : '会话详情'}>
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
                onMcpImportJsonChange={setMcpImportJson}
                onMcpImport={() => void importMcpConfig()}
                onMcpRefresh={() => void refreshMcpData()}
                onMcpConnection={(serverId, action) => void updateMcpConnection(serverId, action)}
                onSkillInstallTextChange={setSkillInstallText}
                onSkillInstall={() => void installSkill()}
                onSkillToggle={toggleSkill}
              />
            )}

            {active === 'config' && (
              <section className="stack">
                <div className="settings-tab-row">
                  <button className={configTab === 'models' ? 'active' : undefined} onClick={() => setConfigTab('models')}><Bot size={15} />模型 API</button>
                  <button className={configTab === 'embedding' ? 'active' : undefined} onClick={() => setConfigTab('embedding')}><Database size={15} />向量模型</button>
                  <button className={configTab === 'memory' ? 'active' : undefined} onClick={() => setConfigTab('memory')}><Clock size={15} />记忆处理</button>
                  <button className={configTab === 'local' ? 'active' : undefined} onClick={() => setConfigTab('local')}><Settings size={15} />本地配置</button>
                </div>
                {configTab === 'local' && (
                  <Panel title="本地配置目录" action={<span className="muted">页面只保存本地覆盖配置，模型客户端重启后生效。</span>}>
                    <div className="definition-list">
                      <div><span>工作目录</span><strong className="mono">{runtimeConfig?.cwd || '-'}</strong></div>
                      <div><span>配置根</span><strong className="mono">{runtimeConfig?.configRoot || '.clawagent'}</strong></div>
                      <div><span>覆盖 YAML</span><strong className="mono">{runtimeConfig?.configPath || '.clawagent/config/clawagent.yml'}</strong></div>
                      <div><span>说明</span><strong>页面只保存本地覆盖配置，模型客户端重启后生效。</strong></div>
                    </div>
                  </Panel>
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
                    onSave={saveModelConfig}
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

            {active === 'nodes' && (
              <UnavailablePanel
                title="节点"
                text="节点页面预留给 M4/M5 的 Worker、隔离执行器、远程执行节点和健康检查。本阶段没有后端节点 API，因此只显示占位状态。"
              />
            )}
          </>
        )}
      </main>
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

function ChatPage({
  currentSessionId,
  input,
  attachments,
  running,
  messages,
  todos,
  lastTaskId,
  highRiskTools,
  approvalSettings,
  approvalMode,
  knowledgeDocuments,
  selectedKnowledgeDocumentIds,
  onInputChange,
  onAddAttachments,
  onRemoveAttachment,
  onToggleKnowledgeDocument,
  onSetKnowledgeDocuments,
  onSubmit,
  onNewSession,
  onRefreshTodos,
  onToggleTools,
  onApprovalModeChange,
  onToggleApprovedTool,
}: {
  currentSessionId?: string;
  input: string;
  attachments: ComposerAttachment[];
  running: boolean;
  messages: ChatMessage[];
  todos: TodoItem[];
  lastTaskId?: string;
  highRiskTools: ToolDefinition[];
  approvalSettings: ApprovalSettings;
  approvalMode: ApprovalMode;
  knowledgeDocuments: KnowledgeDocument[];
  selectedKnowledgeDocumentIds: string[];
  onInputChange: (value: string) => void;
  onAddAttachments: (files: FileList | File[]) => void;
  onRemoveAttachment: (attachmentId: string) => void;
  onToggleKnowledgeDocument: (documentId: string) => void;
  onSetKnowledgeDocuments: (documentIds: string[]) => void;
  onSubmit: () => void;
  onNewSession: () => void;
  onRefreshTodos: () => void;
  onToggleTools: (messageId: string) => void;
  onApprovalModeChange: (mode: ApprovalMode) => void;
  onToggleApprovedTool: (toolId: string) => void;
}) {
  const chatStreamRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const showTodoPanel = todos.length > 0;

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
    const stream = chatStreamRef.current;
    if (!stream) return;
    const scrollToBottom = () => window.requestAnimationFrame(() => {
      stream.scrollTop = stream.scrollHeight;
    });
    scrollToBottom();
    // 流式 markdown、工具调用列表展开时会改变内容高度，监听尺寸变化才能持续贴到底部。
    const observer = new ResizeObserver(scrollToBottom);
    observer.observe(stream);
    Array.from(stream.children).forEach((child) => observer.observe(child));
    return () => observer.disconnect();
  }, [messages, running]);

  return (
    <section className="chat-page">
      <div className="chat-page-head">
        <div>
          <h1>聊天</h1>
          <p>用于快速干预的 Agent 会话、工具链路和 Todo 计划。</p>
        </div>
        <div className="session-chip">
          <span className="mono">{currentSessionId || '等待会话'}</span>
          <button className="icon-button" onClick={onRefreshTodos} title="刷新 Todo">
            <RefreshCw size={16} />
          </button>
        </div>
      </div>

      <div className={`chat-workspace${showTodoPanel ? '' : ' no-todos'}`}>
        <div className="chat-stream" ref={chatStreamRef}>
          {messages.map((message) => (
            <ChatBubble key={message.id} message={message} onToggleTools={onToggleTools} />
          ))}
        </div>
        {showTodoPanel && (
          <aside className="todo-panel">
            <div className="panel-title-row">
              <h2>当前会话 Todo</h2>
              <button className="tiny-button" onClick={onRefreshTodos}>刷新</button>
            </div>
            <TodoList todos={todos} lastTaskId={lastTaskId} />
          </aside>
        )}
      </div>

      <div className="composer-shell">
        <AttachmentPreviewList attachments={attachments} onRemove={onRemoveAttachment} />
        <textarea
          ref={inputRef}
          rows={1}
          value={input}
          onChange={(event) => {
            const value = normalizeEscapedNewlines(event.target.value);
            onInputChange(value);
            resizeInput(event.target);
          }}
          placeholder="Message (Enter 发送，Shift+Enter 换行，可粘贴 \\n 自动转为换行)"
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
            onClick={() => attachmentInputRef.current?.click()}
            title="添加附件"
          >
            <Plus size={18} />
          </button>
          <KnowledgePicker
            documents={knowledgeDocuments}
            selectedIds={selectedKnowledgeDocumentIds}
            onToggle={onToggleKnowledgeDocument}
            onSetSelected={onSetKnowledgeDocuments}
          />
          <ApprovalControls
            highRiskTools={highRiskTools}
            settings={approvalSettings}
            mode={approvalMode}
            onModeChange={onApprovalModeChange}
            onToggleApprovedTool={onToggleApprovedTool}
          />
          <div className="composer-spacer" />
          <button className="secondary" onClick={onNewSession}>New session</button>
          <button
            className={running ? 'danger-button' : 'send-button'}
            onClick={onSubmit}
            disabled={!running && !input.trim() && attachments.length === 0 && selectedKnowledgeDocumentIds.length === 0}
          >
            {running ? <Square size={15} /> : <Send size={15} />}
            {running ? 'Stop' : 'Send'}
          </button>
        </div>
      </div>
    </section>
  );
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
  const label = mode === 'full' ? '完全访问' : mode === 'auto' ? '替我审批' : '请求批准';
  return (
    <details className="approval-menu">
      <summary>
        <ShieldCheck size={16} />
        <span>{label}</span>
        <ChevronDown size={15} />
      </summary>
      <div className="approval-popover">
        <button className={mode === 'ask' ? 'selected' : ''} type="button" onClick={() => onModeChange('ask')}>
          <span className="approval-option-icon"><ShieldCheck size={16} /></span>
          <span>
            <strong>请求批准</strong>
            <small>高危工具需要在本次请求中显式授权。</small>
          </span>
          {mode === 'ask' && <Check size={16} />}
        </button>
        <button className={mode === 'auto' ? 'selected' : ''} type="button" onClick={() => onModeChange('auto')}>
          <span className="approval-option-icon"><Bot size={16} /></span>
          <span>
            <strong>替我审批</strong>
            <small>自动放行检测到的高危工具请求。</small>
          </span>
          {mode === 'auto' && <Check size={16} />}
        </button>
        <button className={mode === 'full' ? 'selected' : ''} type="button" onClick={() => onModeChange('full')}>
          <span className="approval-option-icon"><Zap size={16} /></span>
          <span>
            <strong>完全访问权限</strong>
            <small>不受限制地允许当前控制台工具请求。</small>
          </span>
          {mode === 'full' && <Check size={16} />}
        </button>
        {mode === 'ask' && highRiskTools.length > 0 && (
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

function ChatBubble({ message, onToggleTools }: { message: ChatMessage; onToggleTools: (messageId: string) => void }) {
  const displayContent = message.role === 'user' ? stripAttachmentParseBlock(message.content) : message.content;
  const isUser = message.role === 'user';
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
        </div>
        <div className="message-card">
          {message.role === 'assistant' && (
            <>
              <div className={`assistant-progress ${message.progress ? 'active' : ''}`}>
                <strong>执行进度</strong>
                <span>{message.progress || (message.status ? `任务${message.status}` : '等待任务事件')}</span>
              </div>
              {!!message.toolCalls.length && (
                <div className={`tool-call-section ${message.toolsCollapsed ? 'collapsed' : ''}`}>
                  <button type="button" className="tool-call-title" onClick={() => onToggleTools(message.id)}>
                    <span>工具调用</span>
                    <span>{message.toolsCollapsed ? '展开' : '收起'}</span>
                  </button>
                  {!message.toolsCollapsed && (
                    <div className="tool-call-list">
                      {message.toolCalls.map((call, index) => (
                        <ToolCallLine key={`${call.stepId || call.toolId}-${index}`} call={call} />
                      ))}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
          <div className="markdown" dangerouslySetInnerHTML={{ __html: renderMarkdown(displayContent) }} />
          {message.role === 'assistant' && message.tokenUsage && (
            <TokenInline usage={message.tokenUsage} />
          )}
        </div>
        <ChatAttachmentList attachments={message.attachments} />
      </div>
    </article>
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

function ToolCallLine({ call }: { call: ToolCallView }) {
  const Icon = call.status === 'completed' ? CheckCircle : call.status === 'failed' ? XCircle : Loader2;
  const statusText = call.status === 'running' ? '正在调用' : call.status === 'completed' ? '调用成功' : '调用失败';
  const detailText = call.status === 'failed' ? (call.error || call.message) : '';
  return (
    <div className={`tool-line ${call.status}`}>
      <div className="tool-line-head">
        <Icon size={15} />
        <span className="tool-line-title">{statusText} {call.toolId || '未知工具'}</span>
        {call.outputLength != null && call.outputLength > 0 && <em className="tool-line-chip">返回 {call.outputLength} 字符</em>}
        {call.elapsedMs != null && <em className="tool-line-chip">{call.elapsedMs}ms</em>}
        {call.todoTitle && <em className="tool-line-chip">{call.todoTitle}</em>}
      </div>
      {detailText && <small className="tool-line-detail">{detailText}</small>}
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
            <TodoStatusIcon status={todo.status} />
            <span>{todo.itemOrder || '-'}.</span>
            <strong>{todo.title || todo.description || todo.id}</strong>
            <em>{todoStatusText(todo.status)}</em>
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
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      <div className="modal-shell" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
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
                    </div>
                    <p>{short(hit.content, 360)}</p>
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
          <div className="row-actions">
            <span className="pill neutral">{item.scopeType || '-'}</span>
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
                <span>复用会话ID</span>
                <input value={draft.sessionId || ''} onChange={(event) => onDraftChange({ ...draft, sessionId: event.target.value })} placeholder="留空则每次由 Runtime 创建" />
              </label>
              <label className="form-field">
                <span>Channel ID</span>
                <input value={draft.channelId || ''} onChange={(event) => onDraftChange({ ...draft, channelId: event.target.value })} placeholder="automation" />
              </label>
              <label className="form-field">
                <span>User ID</span>
                <input value={draft.userId || ''} onChange={(event) => onDraftChange({ ...draft, userId: event.target.value })} placeholder="automation" />
              </label>
            </div>
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
}: {
  detail: TaskDetail;
  activeTab: 'result' | 'messages' | 'events' | 'tokens';
  onTabChange: (tab: 'result' | 'messages' | 'events' | 'tokens') => void;
  onClose: () => void;
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
          {(['result', 'messages', 'events', 'tokens'] as Array<'result' | 'messages' | 'events' | 'tokens'>).map((tab) => (
            <button key={tab} className={activeTab === tab ? 'active' : ''} onClick={() => onTabChange(tab)}>
              {tab === 'result' ? '结果' : tab === 'messages' ? '消息' : tab === 'events' ? '日志' : 'Token'}
            </button>
          ))}
        </div>
        {detail.loading && <Empty text="正在加载任务结果..." />}
        {!detail.loading && detail.error && <div className="automation-message">{detail.error}</div>}
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
          />
        )}
      </div>
    </Panel>
  );
}

function SessionTokenPanel({
  sessionUsage,
  tasks,
  taskUsages,
}: {
  sessionUsage?: TokenUsageSummary;
  tasks: AgentTask[];
  taskUsages: Record<string, TokenUsageSummary>;
}) {
  const usage = sessionUsage || emptyTokenUsage();
  return (
    <section className="stack token-panel">
      <div className="metric-grid">
        <Metric title="LLM 调用" value={formatTokenCount(usage.callCount)} desc="当前会话累计" />
        <Metric title="Prompt Tokens" value={formatTokenCount(usage.promptTokens)} desc="输入消耗" />
        <Metric title="Completion Tokens" value={formatTokenCount(usage.completionTokens)} desc="输出消耗" />
        <Metric title="Total Tokens" value={formatTokenCount(usage.totalTokens)} desc="会话总消耗" />
      </div>
      <Panel title="每轮对话 Token">
        <TaskTokenTable tasks={tasks} taskUsages={taskUsages} />
      </Panel>
      <div className="two-col">
        <TokenBreakdownTable title="按模型统计" data={usage.byModel} />
        <TokenBreakdownTable title="按阶段统计" data={usage.byPhase} />
      </div>
    </section>
  );
}

function TaskTokenTable({
  tasks,
  taskUsages,
}: {
  tasks: AgentTask[];
  taskUsages: Record<string, TokenUsageSummary>;
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
          <th>更新时间</th>
        </tr>
      </thead>
      <tbody>
        {tasks.map((task) => {
          const usage = taskUsages[task.id] || emptyTokenUsage();
          return (
            <tr key={task.id}>
              <td>{short(task.input, 60)}</td>
              <td>{formatTokenCount(usage.callCount)}</td>
              <td>{formatTokenCount(usage.promptTokens)}</td>
              <td>{formatTokenCount(usage.completionTokens)}</td>
              <td><strong>{formatTokenCount(usage.totalTokens)}</strong></td>
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
}: {
  title: string;
  data?: Record<string, TokenUsageSummary>;
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
            </tr>
          </thead>
          <tbody>
            {rows.map(([name, usage]) => (
              <tr key={name}>
                <td>{name}</td>
                <td>{formatTokenCount(usage.callCount)}</td>
                <td>{formatTokenCount(usage.promptTokens)}</td>
                <td>{formatTokenCount(usage.completionTokens)}</td>
                <td><strong>{formatTokenCount(usage.totalTokens)}</strong></td>
              </tr>
            ))}
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
          onToggleTools={() => undefined}
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
  onMcpImportJsonChange,
  onMcpImport,
  onMcpRefresh,
  onMcpConnection,
  onSkillInstallTextChange,
  onSkillInstall,
  onSkillToggle,
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
  onMcpImportJsonChange: (value: string) => void;
  onMcpImport: () => void;
  onMcpRefresh: () => void;
  onMcpConnection: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
  onSkillInstallTextChange: (value: string) => void;
  onSkillInstall: () => void;
  onSkillToggle?: (skillId: string, enabled: boolean) => void;
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
        <Panel title="系统工具" action={<span className="muted">只读查看，不支持页面修改</span>}>
          <SystemToolManager tools={tools} />
        </Panel>
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
            onToggle={onSkillToggle}
          />
        </Panel>
      )}
    </section>
  );
}

function SystemToolManager({ tools }: { tools: ToolDefinition[] }) {
  const [query, setQuery] = useState('');
  const [selectedTool, setSelectedTool] = useState<ToolDefinition | undefined>();
  const normalizedQuery = query.trim().toLowerCase();
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
        <span className="muted">{visibleTools.length} shown</span>
      </div>
      <input className="switch-list-search" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Filter system tools" />
      <ToolList tools={visibleTools} onSelect={setSelectedTool} />
      {selectedTool && <ToolDetailModal tool={selectedTool} onClose={() => setSelectedTool(undefined)} />}
    </div>
  );
}

function ToolList({ tools, onSelect }: { tools: ToolDefinition[]; onSelect: (tool: ToolDefinition) => void }) {
  if (!tools.length) return <Empty text="暂无工具" />;
  return (
    <div className="switch-list">
      {tools.map((tool) => {
        const risk = riskClass(tool.riskLevel);
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
              </div>
              <p>{short(tool.description, 180) || '暂无描述'}</p>
            </div>
          </div>
        );
      })}
    </div>
  );
}

function ToolDetailModal({ tool, onClose }: { tool: ToolDefinition; onClose: () => void }) {
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
            </div>
          </section>
          <section className="detail-section">
            <h4>描述</h4>
            <p className="detail-text">{tool.description || '暂无描述'}</p>
          </section>
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
}: {
  servers: McpServerRegistration[];
  importJson: string;
  updating?: string;
  message?: string;
  onImportJsonChange: (value: string) => void;
  onImport: () => void;
  onRefresh: () => void;
  onConnection: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
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
      <McpList servers={servers} updating={updating} onConnection={onConnection} onSelect={setSelectedServer} />
      {selectedServer && <McpDetailModal server={selectedServer} onClose={() => setSelectedServer(undefined)} />}
    </div>
  );
}

function McpList({
  servers,
  updating,
  onConnection,
  onSelect,
}: {
  servers: McpServerRegistration[];
  updating?: string;
  onConnection?: (serverId: string, action: 'connect' | 'disconnect' | 'refresh') => void;
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
              </div>
            </div>
          );
        })}
    </div>
  );
}

function McpDetailModal({ server, onClose }: { server: McpServerRegistration; onClose: () => void }) {
  const config = server.config || {};
  const id = server.id || config.id || '-';
  const transport = config.transport || config.type || config.transportType || '-';
  const endpointOrCommand = config.endpoint || config.url || config.command || '-';
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal-shell compact-modal" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>MCP Server 详情</h3>
            <small className="muted mono">{id}</small>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><X size={16} /></button>
        </div>
        <div className="detail-modal-body">
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
  const applyModelProfile = (profileId: string, target: 'chat' | 'memory') => {
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
        }
        : { memoryModel: profile.id }),
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
  onToggle,
}: {
  skills: SkillRegistration[];
  installText: string;
  updatingSkillId?: string;
  message?: string;
  onInstallTextChange: (value: string) => void;
  onInstall: () => void;
  onToggle?: (skillId: string, enabled: boolean) => void;
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
            placeholder="粘贴 SKILL.md 内容，或粘贴 { manifest, content, resourceFiles } JSON 包"
          />
        </label>
        <div className="form-actions inline-actions">
          <button type="button" disabled={updatingSkillId === 'install' || !installText.trim()} onClick={onInstall}>
            {updatingSkillId === 'install' ? '安装中...' : '安装并加载'}
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
      <SkillList skills={visibleSkills} updatingSkillId={updatingSkillId} onToggle={onToggle} onSelect={setSelectedSkill} />
      {selectedSkill && <SkillDetailModal skill={selectedSkill} onClose={() => setSelectedSkill(undefined)} />}
    </div>
  );
}

function SkillList({
  skills,
  updatingSkillId,
  onToggle,
  onSelect,
}: {
  skills: SkillRegistration[];
  updatingSkillId?: string;
  onToggle?: (skillId: string, enabled: boolean) => void;
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
              {onToggle && (
                <div className="switch-card-actions" onClick={(event) => event.stopPropagation()}>
                  <ToggleSwitch
                    checked={enabled}
                    disabled={updatingSkillId === id}
                    label={enabled ? '禁用 Skill' : '启用 Skill'}
                    onChange={() => onToggle(id, enabled)}
                  />
                </div>
              )}
            </div>
          );
        })}
    </div>
  );
}

function SkillDetailModal({ skill, onClose }: { skill: SkillRegistration; onClose: () => void }) {
  const manifest = skill.manifest || {};
  const id = manifest.id || '-';
  const enabled = Boolean(manifest.enabled);
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal-shell compact-modal" role="dialog" aria-modal="true" onMouseDown={(event) => event.stopPropagation()}>
        <div className="modal-head">
          <div>
            <h3>Skill 详情</h3>
            <small className="muted mono">{id}</small>
          </div>
          <button type="button" className="icon-button" onClick={onClose} aria-label="关闭"><X size={16} /></button>
        </div>
        <div className="detail-modal-body">
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
