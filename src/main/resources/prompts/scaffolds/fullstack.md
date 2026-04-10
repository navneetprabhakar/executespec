FULL_STACK — MUST split into TWO parallel tier-0 tasks (never a single project_setup):

A single scaffold task with 8+ files (pom.xml + application.yml + Application.java + CorsConfig +
package.json + vite.config.js + tailwind.config.js + index.html) WILL truncate and drop
critical files. Always use the two-task split below.

{
  "task_id": "backend_setup",
  "task_layer": "BACKEND",
  "title": "Create backend project scaffold",
  "objective": "Write the backend scaffold. Spring Boot — create IN THIS ORDER: 1) pom.xml with ALL dependencies. 2) The @SpringBootApplication main class — MANDATORY entry point. 3) application.yml with server port, datasource config (host 'db', user 'preview', database 'preview'). 4) CORS @Configuration class allowing all origins (allowedOriginPatterns(\"*\")). Node.js/Express — create package.json and server entry point (bind to 0.0.0.0). Python/Django — create ONLY: requirements.txt, manage.py, <project>/settings.py, <project>/urls.py, <project>/wsgi.py (5 files max). Do NOT create Django app directories (__init__.py, apps.py, admin.py, models.py, migrations/) — those go in the first domain task.",
  "boundaries": "Backend scaffold files only. Do not create frontend files. For Django: do NOT create app boilerplate — max 5-6 files.",
  "output_format": "pom.xml + Application.java + application.yml + CORS config (Spring Boot), OR package.json + server.js (Node), OR requirements.txt + manage.py + settings.py + urls.py + wsgi.py (Django).",
  "acceptance_criteria": "Build manifest exists. Main entry point class/file exists. Server config exists.",
  "depends_on": [],
  "complexity": "MEDIUM",
  "assigned_model": "{{WORKER_MODEL}}"
},
{
  "task_id": "frontend_setup",
  "task_layer": "INFRA",
  "title": "Create frontend project scaffold: config files only",
  "objective": "Write ONLY frontend build/tooling config files. For compiled backends (Spring Boot, Go, Rust): ALL frontend files MUST go under frontend/ directory (e.g., frontend/package.json, frontend/vite.config.js). For Node.js backends: files can be at root. Config files: package.json with all frontend dependencies (react, vite, tailwind, etc.), vite.config.js with /api proxy to backend port, tailwind.config.js, postcss.config.js, tsconfig.json (if TypeScript). Do NOT create index.html, src/main.tsx, src/App.tsx/vue, src/style.css, or any component files — those belong in the app_shell task. CRITICAL: frontend API calls must use RELATIVE base URLs (e.g., baseURL: '/api') — NEVER absolute URLs.",
  "boundaries": "Config files only: package.json, vite.config, tailwind.config, postcss.config, tsconfig. Do NOT create index.html, main.tsx/jsx, App.tsx/jsx/vue, style.css, or any source files. For Spring Boot/Go/Rust backends: all files under frontend/ directory.",
  "output_format": "package.json, vite.config.js, tailwind.config.js (max 5 config files). Under frontend/ for compiled backends.",
  "acceptance_criteria": "Frontend package.json exists with all dependencies. Vite config exists with /api proxy. For compiled backends: all files under frontend/.",
  "depends_on": [],
  "complexity": "LOW",
  "assigned_model": "{{WORKER_MODEL}}"
}

ROUTING RULE: ALL other tasks must include BOTH "backend_setup" AND "frontend_setup" in
their depends_on list (or whichever scaffold is relevant to their layer).
Backend tasks depend on backend_setup. Frontend tasks depend on frontend_setup.
Tasks that bridge both layers depend on both.
