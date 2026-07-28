import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const DEFAULT_SERVER_URL = 'http://127.0.0.1:17891';
export const LOCAL_CONNECTION_MODE = 'local';
export const REMOTE_CONNECTION_MODE = 'remote';

export function normalizeServerUrl(value) {
  const raw = String(value || '').trim();
  if (!raw) return DEFAULT_SERVER_URL;
  const withProtocol = /^https?:\/\//i.test(raw) ? raw : `http://${raw}`;
  try {
    const url = new URL(withProtocol);
    url.pathname = '';
    url.search = '';
    url.hash = '';
    return url.toString().replace(/\/$/, '');
  } catch {
    throw new Error(`无效服务器地址：${value}`);
  }
}

export function defaultClientConfigDir() {
  if (process.env.CLAWAGENT_CONFIG_DIR) return process.env.CLAWAGENT_CONFIG_DIR;
  if (process.platform === 'win32' && process.env.APPDATA) {
    return path.join(process.env.APPDATA, 'ClawAgent');
  }
  return path.join(os.homedir(), '.clawagent');
}

export function clientConfigPath(configDir = defaultClientConfigDir()) {
  return path.join(configDir, 'client-config.json');
}

export function readClientConfig(configDir = defaultClientConfigDir()) {
  const file = clientConfigPath(configDir);
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const serverUrl = normalizeServerUrl(parsed.serverUrl || DEFAULT_SERVER_URL);
    // 旧配置没有 connectionMode 时兼容 edition；不能再根据地址推断模式。
    const connectionMode = parsed.connectionMode === REMOTE_CONNECTION_MODE || parsed.edition === REMOTE_CONNECTION_MODE
      ? REMOTE_CONNECTION_MODE
      : LOCAL_CONNECTION_MODE;
    return {
      serverUrl,
      connectionMode,
      edition: connectionMode,
      configPath: file,
      configExists: true,
    };
  } catch {
    return {
      serverUrl: DEFAULT_SERVER_URL,
      connectionMode: LOCAL_CONNECTION_MODE,
      edition: LOCAL_CONNECTION_MODE,
      configPath: file,
      configExists: false,
    };
  }
}

export function writeClientConfig(next, configDir = defaultClientConfigDir()) {
  const file = clientConfigPath(configDir);
  const connectionMode = next?.connectionMode === REMOTE_CONNECTION_MODE
    ? REMOTE_CONNECTION_MODE
    : LOCAL_CONNECTION_MODE;
  // local 地址由桌面端管理，remote 可以是任意已启动的服务地址，包括本机端口。
  const serverUrl = connectionMode === LOCAL_CONNECTION_MODE
    ? DEFAULT_SERVER_URL
    : normalizeServerUrl(next?.serverUrl || DEFAULT_SERVER_URL);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const saved = { serverUrl, connectionMode, edition: connectionMode };
  fs.writeFileSync(file, `${JSON.stringify(saved, null, 2)}\n`, 'utf8');
  return { ...saved, configPath: file, configExists: true };
}
