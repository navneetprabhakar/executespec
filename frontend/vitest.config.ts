import {defineConfig} from 'vitest/config';
import path from 'path';

/**
 * RL-2026-05-041 (audit #17) — frontend safety net. Kept separate from vite.config.ts so the
 * production build (`tsc && vite build`) never depends on vitest types. `npm test` (= `vitest run`)
 * picks up every `*.test.ts(x)` under src/ — starting with the previously-dormant WCAG-contrast
 * regression test at src/design-system/contrast.test.ts.
 */
export default defineConfig({
  resolve: {
    alias: {'@': path.resolve(__dirname, './src')},
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    passWithNoTests: false,
  },
});
