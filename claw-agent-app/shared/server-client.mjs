import { API_PATHS } from './api-paths.mjs';

export async function requestJson(serverUrl, apiPath, init = {}) {
  const response = await fetch(`${serverUrl}${apiPath}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}: ${await response.text()}`);
  }
  const text = await response.text();
  return text.trim() ? JSON.parse(text) : null;
}

export async function isServerHealthy(serverUrl, timeoutMs = 1200) {
  try {
    const response = await fetch(`${serverUrl}${API_PATHS.health}`, { signal: AbortSignal.timeout(timeoutMs) });
    return response.ok;
  } catch {
    return false;
  }
}
