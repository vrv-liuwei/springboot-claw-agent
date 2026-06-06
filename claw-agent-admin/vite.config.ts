import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: '/admin/',
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': 'http://localhost:17891',
    },
  },
  build: {
    outDir: '../claw-agent-server/src/main/resources/static/admin',
    emptyOutDir: true,
  },
});
