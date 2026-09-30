import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/oauth2': { target: 'http://127.0.0.1:8080', changeOrigin: true },
      '/login': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  test: { include: ['src/**/*.test.ts'], environment: 'node' },
});
