NODE_FRONTEND — complexity LOW (config files only — do NOT create entry points here):
{
  "task_id": "project_setup",
  "task_layer": "INFRA",
  "title": "Create frontend project scaffold: config files only",
  "objective": "Create ONLY build/tooling config files: package.json with all dependencies (react, vite, tailwind, etc.), vite.config.js/ts, tsconfig.json (if TypeScript), tailwind.config.js, postcss.config.js. Do NOT create index.html, src/main.tsx, src/App.tsx, src/index.css, or any component/entry point files — those belong in the app_shell task. CRITICAL: The frontend API client must use RELATIVE base URLs (e.g., baseURL: '/api') — NEVER absolute URLs.",
  "boundaries": "Config files only: package.json, vite.config, tailwind.config, postcss.config, tsconfig. Do NOT create index.html, main.tsx/jsx, App.tsx/jsx, style.css, or any source files.",
  "output_format": "package.json, vite.config.js/ts, tailwind.config.js, postcss.config.js at workspace root (max 5 config files).",
  "acceptance_criteria": "package.json exists with all frontend dependencies. vite.config exists. tailwind.config exists.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}

NOTE: The app_shell task (depends_on: ["project_setup"]) creates: index.html, src/main.tsx/jsx,
src/App.tsx/jsx, src/index.css, routing setup, and global style tokens. This split keeps
project_setup at ≤5 config-only files and app_shell at ≤5 entry-point files.
