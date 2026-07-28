import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('clawAgentApp', {
  platform: process.platform,
  desktop: true,
  onOpenSettings: (callback) => {
    ipcRenderer.on('clawagent:open-settings', callback);
  },
  selectDirectory: () => ipcRenderer.invoke('clawagent:select-directory'),
  getClientConfig: () => ipcRenderer.invoke('clawagent:get-client-config'),
  setServerUrl: (serverUrl, options) => ipcRenderer.invoke('clawagent:set-server-url', serverUrl, options),
});
