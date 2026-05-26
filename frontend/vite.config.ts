import {defineConfig} from 'vite';
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
    // RL-2026-05-041 (audit #17) — split the ~802 KB single bundle into cacheable vendor chunks so
    // an app-code change no longer invalidates the whole JS payload on the client. react-vendor is
    // the most stable (rarely changes); the catch-all vendor chunk holds the rest of node_modules.
    // Route-level React.lazy code-splitting is the follow-up layer on top of this.
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (id.includes('node_modules')) {
            // Only the leaf React RUNTIME (react / react-dom / scheduler) goes in react-vendor — it
            // imports nothing else, so `vendor` → `react-vendor` is a strict one-way edge (no cycle).
            // react-router et al. depend on both, so they stay in `vendor` (avoids the circular-chunk
            // warning a broader react-vendor predicate produced).
            if (/[\\/](react|react-dom|scheduler)[\\/]/.test(id)) {
              return 'react-vendor';
            }
            return 'vendor';
          }
          return undefined;
        },
      },
    },
  },
});
