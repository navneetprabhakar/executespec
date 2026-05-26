import { build } from 'esbuild';

// VS Code extensions load as CommonJS; bundling lets us pull the ESM @executespec/core
// in as a single dist/extension.js and keeps `vscode` (host-provided) external.
await build({
  entryPoints: ['src/extension.ts'],
  bundle: true,
  platform: 'node',
  format: 'cjs',
  target: 'node18',
  external: ['vscode'],
  outfile: 'dist/extension.js',
  sourcemap: true,
  logLevel: 'info',
});
