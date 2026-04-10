Use the scaffold template below that matches the detected project type.
Skip this section entirely if pom.xml or package.json already exists in the workspace.

SPRING_BOOT (Java + Spring Boot) — complexity MEDIUM:
Use base package from pom.xml groupId. Create IN ORDER: 1) pom.xml with ALL dependencies
(spring-boot-starter-web, spring-boot-starter-data-jpa, postgresql/h2, lombok, etc.).
2) @SpringBootApplication main class. 3) application.yml (server port, datasource host='db',
user='preview', database='preview'). 4) @Configuration CORS class (allowedOriginPatterns("*")).

JAVA (no Spring Boot) — complexity LOW:
Create pom.xml + Main.java entry point. Declare base package in groupId.

NODE_FRONTEND — complexity LOW:
Create config files ONLY: package.json, vite.config.js/ts, tailwind.config.js, tsconfig.json.
Do NOT create index.html, main.tsx, App.tsx — those go in app_shell task.

NODE_BACKEND — complexity LOW:
Create package.json with all server dependencies. Declare ESM or CJS module system.

PYTHON (FastAPI/Flask) — complexity LOW:
Create requirements.txt + main.py/app.py with app instance, CORS configured, server bound to 0.0.0.0.

PYTHON (Django) — complexity MEDIUM:
Create ONLY 5 files: requirements.txt, manage.py, <project>/settings.py (ALLOWED_HOSTS=['*'],
DB host='db' user='preview' database='preview'), <project>/urls.py, <project>/wsgi.py.
Do NOT create app directories (__init__.py, models.py, etc.) — those go in first domain task.

GO — complexity LOW:
Create go.mod (module path + go version) + cmd/main.go bound to 0.0.0.0.

RUST — complexity LOW:
Create Cargo.toml (crate name + deps) + src/main.rs bound to 0.0.0.0.

FULL_STACK — TWO parallel tier-0 tasks (backend_setup + frontend_setup):
Never a single project_setup — 8+ files in one task WILL truncate.
backend_setup: backend scaffold only (Spring Boot / Node / Python / Go / Rust per above). MEDIUM.
frontend_setup: config files only (package.json, vite.config.js + /api proxy, tailwind.config.js).
  For compiled backends (Spring Boot/Go/Rust): ALL frontend files under frontend/ directory. LOW.
All other tasks must depend on both backend_setup and frontend_setup (or the relevant one).

STATIC_HTML — complexity LOW:
Single page: one task creates complete index.html + style.css + script.js.
4+ sections: split by section using shared_files + sequential depends_on (see static-html scaffold).
