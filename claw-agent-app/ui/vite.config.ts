import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  root: __dirname,
  base: '/app/',
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    port: 5180,
    proxy: {
      '/api': 'http://127.0.0.1:17891',
    },
    fs: {
      allow: [path.resolve(__dirname, '..')],
    },
  },
  build: {
    outDir: '../../claw-agent-server/src/main/resources/static/app',
    emptyOutDir: true,
  },
});
