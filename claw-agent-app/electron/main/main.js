import { app, BrowserWindow, Menu, dialog, ipcMain } from 'electron';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  DEFAULT_SERVER_URL,
  clientConfigPath,
  normalizeServerUrl,
  readClientConfig,
  writeClientConfig,
} from '../../shared/client-config.mjs';
import { isServerHealthy } from '../../shared/server-client.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
let serverProcess;
let mainWindow;
let startupConnectionError = '';
let activeServerUrl = '';
let managedServerUrl = '';

app.setName('ClawAgent');

const cliArgIndex = process.argv.indexOf('--cli');
if (cliArgIndex >= 0) {
  app.whenReady().then(async () => {
    process.env.CLAWAGENT_CONFIG_DIR = process.env.CLAWAGENT_CONFIG_DIR || app.getPath('userData');
    process.env.CLAWAGENT_DATA_DIR = process.env.CLAWAGENT_DATA_DIR || app.getPath('userData');
    const { runCli } = await import('../../cli/clawagent.mjs');
    await runCli(process.argv.slice(cliArgIndex + 1));
    app.exit(process.exitCode || 0);
  }).catch((error) => {
    const message = error.message || String(error);
    if (process.argv.slice(cliArgIndex + 1).includes('--json')) {
      console.error(JSON.stringify({ ok: false, error: message }));
    } else {
      console.error(message);
    }
    app.exit(1);
  });
} else {

async function findPort(start = 17891) {
  for (let port = start; port < start + 100; port += 1) {
    if (await isFree(port)) return port;
  }
  throw new Error('No free port found for ClawAgent server');
}

function isFree(port) {
  return new Promise((resolve) => {
    const tester = net.createServer()
      .once('error', () => resolve(false))
      .once('listening', () => tester.close(() => resolve(true)))
      .listen(port, '127.0.0.1');
  });
}

async function waitForServer(serverUrl) {
  for (let i = 0; i < 80; i += 1) {
    if (await isServerHealthy(serverUrl, 1500)) return;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error('ClawAgent server did not become healthy in time');
}

async function startServer() {
  const config = configuredClientConfig();
  if (config.connectionMode === 'remote') {
    try {
      await waitForServer(config.serverUrl);
      startupConnectionError = '';
      return config.serverUrl;
    } catch (error) {
      startupConnectionError = `远程服务暂不可用：${config.serverUrl}。已临时启动本地服务用于打开设置。`;
      console.warn(startupConnectionError, error?.message || error);
      return startLocalServer(DEFAULT_SERVER_URL);
    }
  }
  startupConnectionError = '';
  return startLocalServer(config.serverUrl);
}

async function startLocalServer(serverUrl) {
  // 已托管的本地服务仍健康时直接复用，避免每次切换都占用新的端口。
  if (serverProcess && !serverProcess.killed && managedServerUrl && await isServerHealthy(managedServerUrl, 1500)) {
    return managedServerUrl;
  }
  const configuredPort = Number(new URL(serverUrl).port || 17891);
  const port = await isFree(configuredPort)
    ? configuredPort
    : await findPort(Number(process.env.CLAW_AGENT_PORT || configuredPort));
  const jarPath = process.env.CLAW_AGENT_SERVER_JAR || defaultServerJarPath();
  const javaExecutable = process.env.CLAW_AGENT_JAVA || defaultJavaExecutable();
  const runtimePaths = appRuntimePaths();
  const child = spawn(javaExecutable, [
    '-jar',
    jarPath,
    `--server.port=${port}`,
    '--server.address=127.0.0.1',
    '--clawagent.mode=local',
    `--clawagent.persistence.sqlite.path=${runtimePaths.databasePath}`,
    `--logging.file.name=${runtimePaths.logFile}`,
  ], {
    stdio: 'inherit',
    windowsHide: true,
  });
  serverProcess = child;
  managedServerUrl = `http://127.0.0.1:${port}`;
  child.once('exit', () => {
    // 旧进程退出不能覆盖随后新启动的本地服务状态。
    if (serverProcess !== child) return;
    serverProcess = undefined;
    managedServerUrl = '';
  });
  await waitForServer(managedServerUrl);
  return managedServerUrl;
}

function configuredClientConfig() {
  if (process.env.CLAW_AGENT_SERVER_URL) {
    return {
      serverUrl: normalizeServerUrl(process.env.CLAW_AGENT_SERVER_URL),
      connectionMode: 'remote',
    };
  }
  return readClientConfig(clientConfigDir());
}

function stopManagedServer() {
  if (serverProcess && !serverProcess.killed) serverProcess.kill();
  serverProcess = undefined;
  managedServerUrl = '';
}

function clientConfigDir() {
  return process.env.CLAWAGENT_CONFIG_DIR || app.getPath('userData');
}

function defaultServerJarPath() {
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'server', 'claw-agent-server.jar');
  }
  return path.resolve(__dirname, '../../../claw-agent-server/target/claw-agent-server-1.0.0-SNAPSHOT.jar');
}

function defaultJavaExecutable() {
  const executable = process.platform === 'win32' ? 'java.exe' : 'java';
  if (app.isPackaged) {
    return path.join(process.resourcesPath, 'runtime', 'java17', 'bin', executable);
  }
  return 'java';
}

function appRuntimePaths() {
  const root = app.getPath('userData');
  const dataDir = path.join(root, 'data');
  const logDir = path.join(root, 'logs');
  fs.mkdirSync(dataDir, { recursive: true });
  fs.mkdirSync(logDir, { recursive: true });
  return {
    databasePath: path.join(dataDir, 'clawagent.db'),
    logFile: path.join(logDir, 'clawagent.log'),
  };
}

async function createWindow() {
  const serverUrl = await startServer();
  activeServerUrl = serverUrl;
  mainWindow = new BrowserWindow({
    width: 1320,
    height: 860,
    minWidth: 860,
    minHeight: 720,
    title: 'ClawAgent',
    webPreferences: {
      preload: path.resolve(__dirname, '../preload/preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  installMenu();
  await mainWindow.loadURL(`${serverUrl}/app/`);
}

function installMenu() {
  const template = [
    {
      label: '文件',
      submenu: [
        {
          label: '设置',
          accelerator: 'CmdOrCtrl+,',
          click: () => mainWindow?.webContents.send('clawagent:open-settings'),
        },
        { type: 'separator' },
        { label: '退出', role: 'quit' },
      ],
    },
    {
      label: '编辑',
      submenu: [
        { label: '撤销', role: 'undo' },
        { label: '重做', role: 'redo' },
        { type: 'separator' },
        { label: '剪切', role: 'cut' },
        { label: '复制', role: 'copy' },
        { label: '粘贴', role: 'paste' },
        { label: '全选', role: 'selectAll' },
      ],
    },
    {
      label: '视图',
      submenu: [
        { label: '重新加载', role: 'reload' },
        { label: '强制重新加载', role: 'forceReload' },
        { label: '开发者工具', role: 'toggleDevTools' },
        { type: 'separator' },
        { label: '放大', role: 'zoomIn' },
        { label: '缩小', role: 'zoomOut' },
        { label: '实际大小', role: 'resetZoom' },
        { type: 'separator' },
        { label: '全屏', role: 'togglefullscreen' },
      ],
    },
    {
      label: '窗口',
      submenu: [
        { label: '最小化', role: 'minimize' },
        { label: '关闭', role: 'close' },
      ],
    },
    {
      label: '帮助',
      submenu: [
        { label: '关于 ClawAgent', role: 'about' },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(createWindow);

ipcMain.handle('clawagent:select-directory', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: '选择项目目录',
    properties: ['openDirectory'],
  });
  return result.canceled ? null : result.filePaths[0];
});

ipcMain.handle('clawagent:get-client-config', async () => ({
  ...readClientConfig(clientConfigDir()),
  configExists: fs.existsSync(clientConfigPath(clientConfigDir())),
  activeServerUrl,
  startupConnectionError,
}));

ipcMain.handle('clawagent:set-server-url', async (_event, serverUrl, options = {}) => {
  const connectionMode = options.connectionMode === 'remote' ? 'remote' : 'local';
  const normalized = connectionMode === 'local' ? DEFAULT_SERVER_URL : normalizeServerUrl(serverUrl);
  const shouldCheck = options.check !== false;
  if (shouldCheck && connectionMode === 'remote') {
    await waitForServer(normalized);
  }
  const saved = writeClientConfig({ serverUrl: normalized, connectionMode }, clientConfigDir());
  startupConnectionError = '';
  if (mainWindow && shouldCheck) {
    // 远程健康检查成功后才停止 App 托管的本地服务，失败时仍保留当前连接。
    if (connectionMode === 'remote') stopManagedServer();
    const nextUrl = connectionMode === 'remote' ? normalized : await startLocalServer(DEFAULT_SERVER_URL);
    activeServerUrl = nextUrl;
    await mainWindow.loadURL(`${nextUrl}/app/`);
  }
  return { ...saved, configExists: true, activeServerUrl, startupConnectionError };
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  stopManagedServer();
});
}
