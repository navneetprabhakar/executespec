import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    // M-057 (Backlog) — dev-friendly env-var overrides so concurrent
    // worktrees on the same machine can run on non-default ports
    // without editing this file. Defaults preserved (5173 + 8080
    // backend) so the existing local-dev story is unchanged.
    //   VITE_PORT=5174 npm --prefix frontend run dev
    //   VITE_BACKEND_URL=http://localhost:8081 npm --prefix frontend run dev
    port: Number(process.env.VITE_PORT ?? 5173),
    proxy: {
      '/api': process.env.VITE_BACKEND_URL ?? 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
});
