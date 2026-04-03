# Deployment & Preview — Requirements

> Last updated: 2026-04-03

## Overview

Dual-mode preview system (Docker Compose for local, Cloud Run for dev/prod), permanent Cloud Run deployment for 11 languages, workspace management with GCS archiving, and GitHub integration for code push.

### Preview Mode Architecture

| Mode | Environment | Infrastructure | TTL | HTTPS |
|------|-------------|---------------|-----|-------|
| `docker` | Local dev | Docker Compose + Caddy proxy | 5 min | Caddy + Cloudflare Origin Cert |
| `cloud-run` | Dev/Prod (GCE) | Artifact Registry + Cloud Run | 5 min | Managed by Cloud Run |

Preview TTL is unified at 5 minutes for both modes.

All subprocess commands (`gcloud`, `docker compose`) are executed with enforced process timeouts to prevent hung builds: deploy operations 10 minutes, delete/rollback operations 5 minutes, list operations 2 minutes. Virtual threads used for async preview operations are wrapped with `ContextSnapshotFactory.captureAll()` to ensure traceId/spanId propagation across thread boundaries.

Configured via `preview.mode` in `application.yml` (default: `docker`).

### Concurrency Limits

| Tier | Max Concurrent Previews |
|------|------------------------|
| FREE | 1 |
| PRO | 3 |
| ENTERPRISE | 10 |

### Cloud Run Preview Flow

Generate Dockerfile -> Build image locally -> Push to Artifact Registry -> Deploy to Cloud Run (min 0, max 1 instance) -> Health check -> Return Cloud Run URL -> Auto-delete after TTL.

`cloudRunServiceName` is stored upfront in the initial preview session (predictable `es-preview-{runIdShort}`) before any build or push begins. This prevents the orphan cleanup job from deleting services that are still mid-deploy.

### Docker Preview Flow

Generate Dockerfile + docker-compose.yml -> `docker compose up --build` -> Caddy route (Cloudflare Origin Certificate for TLS) or direct port -> Health check -> Return preview URL -> Auto-cleanup after TTL.

---

## Features

### 1. Local Preview (ProcessBuilder-Based)

**Purpose:** Start a local dev server from a completed run's workspace without Docker.

**Requirements:**
- Auto-detect project type from workspace contents
- Start appropriate dev server on a free local port
- Support parallel backend + frontend for fullstack projects
- Maintain circular buffer log output (configurable lines)
- API: `POST /api/runs/{id}/preview/start`, `DELETE /api/runs/{id}/preview/stop`, `GET /api/runs/{id}/preview`
- Startup timeout: 120 seconds (configurable)
- Status lifecycle: IDLE -> STARTING -> RUNNING / FAILED / STOPPED

### 2. Project Type Detection

Project type is determined in two stages:

1. **Planner-declared (preferred):** The PlannerAgent declares `project_type` in the plan JSON. This is written to `.agent-context/project.json` inside the workspace. Both `DevDockerfileGenerator` (preview) and `DockerfileGenerator` (deploy) read this file first.
2. **File-based heuristics (fallback):** If `.agent-context/project.json` is missing or unreadable, detection falls back to inspecting workspace files.

**Supported Project Types:**

| Type | Detection (heuristic fallback) | Command |
|------|-------------------------------|---------|
| SPRING_BOOT | `pom.xml` with spring-boot | `mvn spring-boot:run` |
| JAVA | `pom.xml` or `build.gradle` without spring-boot | `mvn exec:java` / `gradle run` |
| NODE_FRONTEND | `package.json` with frontend deps (react/vue/vite/etc) only | `npm run dev` |
| NODE_BACKEND | `package.json` with backend deps (express/fastify/etc) only | `npm start` |
| FULL_STACK | `package.json` with both backend AND frontend deps | `npm run dev` (concurrent) |
| PYTHON | `requirements.txt` / `pyproject.toml` | `uvicorn` or `flask` |
| GO | `go.mod` | `go run .` |
| RUST | `Cargo.toml` | `cargo run` |
| RUBY | `Gemfile` | `rails server` |
| PHP | `composer.json` | `php -S` |
| STATIC_HTML | `index.html` without backend/frontend deps | `python -m http.server` |

**Detection precedence:** `hasStaticHtml()` is checked before `hasJsOrTsFiles()` to prevent projects with only `script.js` from being misdetected as NODE.

**Single-package full-stack detection:** `DevDockerfileGenerator.detect()` checks for both backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc) in the same `package.json`. If both present, the type is `FULL_STACK` (not `NODE_BACKEND`). The generated Dockerfile uses `npm run dev` to run both concurrently, exposing ports 5173 and 3000.

**NODE_BACKEND port handling:** The generated Dockerfile includes `ENV PORT=3000`. Generated apps use `process.env.PORT || 3001` -- without the env var, apps fall back to 3001 while Docker exposes 3000, causing health check failures.

### 3. Cloud Run Deployment (11 Languages)

**Purpose:** Deploy completed runs to Google Cloud Run.

**Requirements:**
- Generate language-specific Dockerfiles via DockerfileGenerator (reads `.agent-context/project.json` before file heuristics)
- Build Docker image via ProcessBuilder
- Push to GCP Artifact Registry
- Deploy to Cloud Run (1 CPU, 512Mi, max 1 instance, unauthenticated)
- 7-day TTL with cleanup job (cron)
- Emit SSE events: DEPLOY_STARTED, DEPLOY_BUILDING, DEPLOY_PUSHING, DEPLOY_LIVE, DEPLOY_FAILED
- Support: Spring Boot, Java, Node.js (frontend/backend/fullstack), Python, Go, Rust, Ruby, PHP, Static HTML
- API: `POST /api/runs/{id}/deploy`, `GET /api/deployments`, `DELETE /api/deployments/{id}`

### 4. Workspace Management

**Requirements:**
- Per-run isolated workspace: `workspace/{runId}/`
- WorkspaceInitService:
  - ITERATION mode: copy workspace from source run
  - EXISTING mode: clone from GitHub repo
  - Default: fresh git init with user.email/user.name configured
- File access sandboxed via FileSystemTool.safeResolve()
- File listing, reading, downloading via REST endpoints
- ZIP download: `GET /api/runs/{id}/download`
- **GitHub-backed file serving fallback:** When local workspace is deleted (and GCS not configured), WorkspaceFileService falls back to GitHub:
  - `listRepoFiles()` -- list files via GitHub Trees API
  - `readRepoFile()` -- read file content via GitHub Contents API
  - Requires the run to have an associated GitHub repo (created via GitHubRepoService)

### 5. GCS Workspace Archiving

**Requirements:**
- Archive completed workspaces to GCS after 24 hours (`archive-after-hours`)
- Store as zip: `gs://executespec-workspaces/workspaces/{runId}.zip`
- Delete local workspace after successful upload
- Download redirects to 5-minute signed GCS URL (HTTP 302)
- WorkspaceArchiveJob runs hourly
- Local workspace retention: 30 days (configurable)
- GCS bucket: `${GCS_WORKSPACE_BUCKET:executespec-workspaces}`

### 6. GitHub Integration

**Requirements:**
- Per-user GitHub PAT stored in app_user table
- Configured in Settings -> GitHub Integration
- Three modes:
  - None: no GitHub interaction
  - Create new repo: auto-creates private GitHub repo, pushes code
  - Start from existing: clones specified repo, modifies, pushes back
- PAT priority: user-level PAT -> system-level PAT
- Clone via HTTPS with PAT authentication
- Support shallow clone for large repos
- **New repos:** create private repo, push main branch
- **Existing repos:** create branch `executespec/{runId-short}`, push to branch, open PR via `GitHubRepoService.createPullRequest()`, store PR URL in `PipelineRun.githubPrUrl`
- Optional push after merge (controlled by run properties)
- Endpoint: `PUT /api/auth/settings/github`
- **Manual push endpoint:** `POST /api/runs/{id}/push-github` -- push workspace to GitHub on demand
- **Random memorable repo names:** New repos use adjective-noun format (e.g. "swift-ember") instead of goal-derived slugs
- **Repo description:** Set from user goal text on creation
- **Collision handling:** If repo name already exists, append random 2-digit suffix (up to 3 retries before failing)
- **Description update for existing repos:** `updateRepoDescription()` updates the description on existing repos after push

### 7. Workspace Export

**Requirements:**
- Download workspace as ZIP via `GET /api/runs/{id}/download`
- If workspace is local: stream ZIP directly
- If workspace archived to GCS: redirect to signed URL
- **GitHub fallback:** If local workspace is deleted and GCS is not configured, redirect to GitHub archive ZIP (via GitHub API)
- Exclude `.git/`, `node_modules/`, `target/`, `build/` from ZIP

### 8. Reliability & Observability

**Requirements:**
- All subprocess commands (`gcloud`, `docker`) must have enforced timeouts: deploy operations 10 minutes, delete/rollback operations 5 minutes, list operations 2 minutes
- Pipe buffer deadlock prevention -- stdout and stderr must be read in parallel (separate threads) for all `gcloud` commands to avoid process hangs from full OS pipe buffers
- Virtual threads must wrap runnables with `ContextSnapshotFactory.captureAll()` to ensure traceId/spanId propagation into async preview operations
- `ACTIVE_PREVIEWS` gauge must be decremented on ALL terminal states: FAILED, TIMED_OUT, and STOPPED (not only on successful stop)
- Cloud Run resource cleanup on preview failure -- both the Cloud Run service and the Artifact Registry image must be deleted when a preview fails to start or times out
- Cloud Run log fetching via `gcloud logging read` for the `GET /api/runs/{id}/preview/logs` endpoint, scoped to the preview's Cloud Run service name
- API docs probing must work for both Docker mode (localhost:port) and Cloud Run mode (service URL), using the same detection logic
- HTTP health check client must enforce a connect timeout of 10 seconds and a request timeout of 15 seconds to avoid blocking on unresponsive previews
- Cloud Run orphan cleanup race prevention: `cloudRunServiceName` must be stored upfront in the initial session (predictable `es-preview-{runIdShort}`) so orphan cleanup jobs don't delete services that are still building/pushing

### Container Image Security

Both preview (`DevDockerfileGenerator`) and deploy (`DockerfileGenerator`) images include `.dockerignore` files that exclude sensitive files:

```
.env, .env.*, .env.local, .env.production
credentials.json, service-account.json
key.json, keyfile.json
secrets.yml, secrets.yaml
```

This prevents secrets written by agents (for generated app configuration) from being baked into container images. Preview Dockerfiles also inject a non-root `USER` directive before the final `CMD`/`ENTRYPOINT`.

See also: [Security Guardrails](security-guardrails.md) for the full container hardening requirements.

---

## Key Files

| File | Purpose |
|------|---------|
| `deployment/preview/PreviewService.java` | Dual-mode preview lifecycle (docker + cloud-run) |
| `deployment/preview/PreviewProperties.java` | Preview config (mode, TTL, concurrency) |
| `deployment/preview/DevDockerfileGenerator.java` | Preview Dockerfile generation (reads `.agent-context/project.json`) |
| `deployment/preview/CaddyProxyManager.java` | Caddy reverse proxy (Docker mode only) |
| `api/controller/PreviewController.java` | Preview REST endpoints (start, stop, logs, API docs probe) |
| `deployment/DeploymentService.java` | Cloud Run deployment |
| `deployment/DockerfileGenerator.java` | Deploy Dockerfile generation (strategy pattern, reads `project.json`) |
| `deployment/strategy/*.java` | 11 deployment strategies |
| `deployment/ArtifactRegistryClient.java` | Docker push to GCR (10-min timeout) |
| `deployment/CloudRunClient.java` | Cloud Run API |
| `deployment/WorkspaceExportService.java` | ZIP download |
| `deployment/GcsWorkspaceStore.java` | GCS upload/download |
| `deployment/WorkspaceArchiveJob.java` | Hourly archive job |
| `deployment/WorkspaceFileService.java` | File listing/reading |
| `harness/WorkspaceInitService.java` | Workspace initialization |
| `repo/GitHubRepoService.java` | GitHub repo create/push, random names, description updates, collision handling, `getDefaultBranch()`, `createPullRequest()` |
| `tool/GitTool.java` | Workspace git ops, `pushToBranch()` for existing-repo branch creation + push |

## Database Migrations

| Version | Description |
|---------|-------------|
| V8 | Per-user GitHub settings (github_pat, github_owner) |
| V12 | GCS workspace archive columns |
| V17 | Auto-merge flag (pipeline_run.auto_merge) |
| V29 | Per-run preview start count limit |
| V52 | GitHub PR URL column on pipeline_run |
