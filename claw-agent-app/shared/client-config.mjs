import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

export const DEFAULT_SERVER_URL = 'http://127.0.0.1:17891';

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
    return {
      serverUrl,
      edition: serverUrl === DEFAULT_SERVER_URL ? 'local' : 'remote',
      configPath: file,
      configExists: true,
    };
  } catch {
    return {
      serverUrl: DEFAULT_SERVER_URL,
      edition: 'local',
      configPath: file,
      configExists: false,
    };
  }
}

export function writeClientConfig(next, configDir = defaultClientConfigDir()) {
  const file = clientConfigPath(configDir);
  const serverUrl = normalizeServerUrl(next?.serverUrl || DEFAULT_SERVER_URL);
  const edition = serverUrl === DEFAULT_SERVER_URL ? 'local' : 'remote';
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const saved = { serverUrl, edition };
  fs.writeFileSync(file, `${JSON.stringify(saved, null, 2)}\n`, 'utf8');
  return { ...saved, configPath: file, configExists: true };
}

export function isDefaultLocalServer(serverUrl) {
  return normalizeServerUrl(serverUrl) === DEFAULT_SERVER_URL;
}
