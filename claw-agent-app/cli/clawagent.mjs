#!/usr/bin/env node
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_SERVER_URL,
  isDefaultLocalServer,
  normalizeServerUrl,
  readClientConfig,
  writeClientConfig,
} from '../shared/client-config.mjs';
import { API_PATHS } from '../shared/api-paths.mjs';
import { isServerHealthy, requestJson } from '../shared/server-client.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const appRoot = path.resolve(__dirname, '..');

function usage() {
  console.log(`ClawAgent CLI

用法:
  clawagent chat <消息> [--workspace <目录>] [--model <模型>] [--permission <模式>]
  clawagent run <任务> [--workspace <目录>] [--model <模型>] [--permission <模式>]
  clawagent session list [--json]
  clawagent logs tail [--limit 80] [--json]
  clawagent server status [--json]
  clawagent config path [--json]
  clawagent config server [服务器地址] [--no-check]
  clawagent config server --reset

选项:
  --server <url>       临时指定 Java API 服务地址
  --no-check           保存服务器地址时不做连通性检查
  --reset              重置服务器地址为 local 默认地址
  --json               输出机器可读 JSON

默认 local 地址: ${DEFAULT_SERVER_URL}`);
}

function parseArgs(argv) {
  const options = { json: false };
  const rest = [];
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--json') options.json = true;
    else if (arg === '--no-check') options.noCheck = true;
    else if (arg === '--reset') options.reset = true;
    else if (arg === '--server') options.server = argv[++i];
    else if (arg === '--workspace') options.workspace = argv[++i];
    else if (arg === '--model') options.model = argv[++i];
    else if (arg === '--permission') options.permission = argv[++i];
    else if (arg === '--limit') options.limit = Number(argv[++i] || 80);
    else rest.push(arg);
  }
  return { options, rest };
}

function resolveServerUrl(options) {
  if (options.server) return normalizeServerUrl(options.server);
  if (process.env.CLAW_AGENT_SERVER_URL) return normalizeServerUrl(process.env.CLAW_AGENT_SERVER_URL);
  return readClientConfig().serverUrl;
}

function existingPath(candidates) {
  return candidates.find((candidate) => candidate && fs.existsSync(candidate));
}

function runtimePaths() {
  const root = process.env.CLAWAGENT_DATA_DIR || path.join(os.homedir(), '.clawagent');
  const dataDir = path.join(root, 'data');
  const logDir = path.join(root, 'logs');
  fs.mkdirSync(dataDir, { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  return {
    databasePath: path.join(dataDir, 'clawagent.db'),
    logFile: path.join(logDir, 'clawagent.log'),
  };
}

function resolveJavaExecutable() {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';
  return process.env.CLAW_AGENT_JAVA || existingPath([
    process.resourcesPath ? path.join(process.resourcesPath, 'runtime', 'java17', 'bin', exe) : '',
    path.resolve(__dirname, '../runtime/java17/bin', exe),
    path.resolve(__dirname, '../../runtime/java17/bin', exe),
  ]) || exe;
}

function resolveServerJar() {
  return process.env.CLAW_AGENT_SERVER_JAR || existingPath([
    process.resourcesPath ? path.join(process.resourcesPath, 'server', 'claw-agent-server.jar') : '',
    path.resolve(appRoot, '../claw-agent-server/target/claw-agent-server-0.1.0-SNAPSHOT.jar'),
    path.resolve(__dirname, '../server/claw-agent-server.jar'),
    path.resolve(__dirname, '../../server/claw-agent-server.jar'),
  ]);
}

function isPortFree(port) {
  return new Promise((resolve) => {
    const tester = net.createServer()
      .once('error', () => resolve(false))
      .once('listening', () => tester.close(() => resolve(true)))
      .listen(port, '127.0.0.1');
  });
}

async function findFreePort(start = 17891) {
  for (let port = start; port < start + 100; port += 1) {
    if (await isPortFree(port)) return port;
  }
  throw new Error('找不到可用的 ClawAgent 本地服务端口。');
}

async function ensureServer(serverUrl) {
  if (await isServerHealthy(serverUrl)) return serverUrl;
  if (!isDefaultLocalServer(serverUrl)) {
    throw new Error(`企业服务不可用：${serverUrl}`);
  }
  const jar = resolveServerJar();
  if (!jar) {
    throw new Error('找不到 claw-agent-server.jar，请先运行 npm run package-server 或安装桌面版。');
  }
  const configuredPort = Number(new URL(serverUrl).port || 17891);
  const port = await isPortFree(configuredPort) ? configuredPort : await findFreePort(configuredPort + 1);
  const actualServerUrl = `http://127.0.0.1:${port}`;
  const paths = runtimePaths();
  const child = spawn(resolveJavaExecutable(), [
    '-jar',
    jar,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    '--clawagent.mode=local',
    `--clawagent.persistence.sqlite.path=${paths.databasePath}`,
    `--logging.file.name=${paths.logFile}`,
  ], {
    detached: true,
    stdio: 'ignore',
    windowsHide: true,
  });
  child.unref();
  for (let i = 0; i < 80; i += 1) {
    if (await isServerHealthy(actualServerUrl)) return actualServerUrl;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('本地 ClawAgent 服务启动超时。');
}

async function openWorkspace(serverUrl, workspacePath) {
  if (!workspacePath) return null;
  return requestJson(serverUrl, API_PATHS.appOpenWorkspace, {
    method: 'POST',
    body: JSON.stringify({ path: workspacePath }),
  });
}

async function createSession(serverUrl, workspace, title) {
  return requestJson(serverUrl, API_PATHS.sessions, {
    method: 'POST',
    body: JSON.stringify({
      title,
      channelId: 'cli',
      userId: 'local',
      workspaceId: workspace?.id || '',
      metadata: {
        workspaceId: workspace?.id || '',
        workspaceName: workspace?.name || '',
        workspaceRoot: workspace?.root || '',
      },
    }),
  });
}

async function streamTask(serverUrl, input, session, workspace, options) {
  const response = await fetch(`${serverUrl}${API_PATHS.tasksStream}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      input,
      sessionId: session.id,
      channelId: 'cli',
      userId: 'local',
      metadata: {
        workspaceId: workspace?.id || '',
        workspaceName: workspace?.name || '',
        workspaceRoot: workspace?.root || '',
        modelId: options.model || '',
        permissionMode: options.permission || '',
      },
    }),
  });
  if (!response.ok || !response.body) {
    throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
  }
  const decoder = new TextDecoder();
  let buffer = '';
  const reader = response.body.getReader();
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split('\n\n');
    buffer = events.pop() || '';
    for (const eventBlock of events) {
      const event = eventBlock.split('\n').find((line) => line.startsWith('event:'))?.slice(6).trim() || 'message';
      const dataLine = eventBlock.split('\n').find((line) => line.startsWith('data:'));
      if (!dataLine) continue;
      const data = JSON.parse(dataLine.slice(5).trim());
      if (options.json) {
        process.stdout.write(`${JSON.stringify({ event, data })}\n`);
      } else if (event === 'llm.delta' && data.text) {
        process.stdout.write(String(data.text));
      } else if (event !== 'llm.delta' && data.message) {
        process.stderr.write(`\n[${event}] ${data.message}\n`);
      }
    }
  }
  if (!options.json) process.stdout.write('\n');
}

export async function runCli(argv = process.argv.slice(2)) {
  const { options, rest } = parseArgs(argv);
  const [command, subcommand, ...tail] = rest;
  if (!command || command === '-h' || command === '--help') {
    usage();
    return;
  }

  if (command === 'config' && subcommand === 'server') {
    if (options.reset) {
      const saved = writeClientConfig({ serverUrl: DEFAULT_SERVER_URL });
      console.log(options.json ? JSON.stringify(saved) : `服务器地址已重置：${saved.serverUrl}（local）`);
      return;
    }
    if (!tail[0]) {
      const current = readClientConfig();
      console.log(options.json ? JSON.stringify(current) : `${current.serverUrl}（${current.edition}）`);
      return;
    }
    const normalized = normalizeServerUrl(tail[0]);
    if (!options.noCheck && !isDefaultLocalServer(normalized) && !(await isServerHealthy(normalized))) {
      throw new Error(`企业服务不可用：${normalized}。如果只是预配置地址，请加 --no-check。`);
    }
    const saved = writeClientConfig({ serverUrl: normalized });
    console.log(options.json ? JSON.stringify(saved) : `服务器地址已保存：${saved.serverUrl}`);
    return;
  }

  if (command === 'config' && subcommand === 'path') {
    const current = readClientConfig();
    console.log(options.json ? JSON.stringify({ configPath: current.configPath }) : current.configPath);
    return;
  }

  const serverUrl = await ensureServer(resolveServerUrl(options));

  if ((command === 'server' && subcommand === 'status') || command === 'status') {
    const runtime = await requestJson(serverUrl, API_PATHS.appRuntime);
    const edition = isDefaultLocalServer(serverUrl) ? 'local' : 'remote';
    console.log(options.json ? JSON.stringify({ ...runtime, serverUrl, edition }) : `ClawAgent 服务正常：${serverUrl}（${edition}）`);
    return;
  }

  if (command === 'session' && subcommand === 'list') {
    const sessions = await requestJson(serverUrl, API_PATHS.sessionsList(50));
    if (options.json) console.log(JSON.stringify(sessions));
    else sessions.forEach((item) => console.log(`${item.id}\t${item.title || '未命名'}\t${item.updatedAt || ''}`));
    return;
  }

  if (command === 'logs' && subcommand === 'tail') {
    const lines = await requestJson(serverUrl, API_PATHS.logsQuery(options.limit || 80));
    if (options.json) console.log(JSON.stringify(lines));
    else lines.forEach((line) => console.log(`${line.time} ${line.level} ${line.logger} ${line.message}`));
    return;
  }

  if (command === 'chat' || command === 'run') {
    const text = [subcommand, ...tail].filter(Boolean).join(' ').trim();
    if (!text) throw new Error('请输入消息内容。');
    const workspace = await openWorkspace(serverUrl, options.workspace);
    const session = await createSession(serverUrl, workspace, text.slice(0, 40));
    await streamTask(serverUrl, text, session, workspace, options);
    return;
  }

  usage();
  process.exitCode = 1;
}

if (process.argv[1] && path.resolve(process.argv[1]) === __filename) {
  runCli().catch((error) => {
    const message = error.message || String(error);
    if (process.argv.includes('--json')) {
      console.error(JSON.stringify({ ok: false, error: message }));
    } else {
      console.error(message);
    }
    process.exitCode = 1;
  });
}
