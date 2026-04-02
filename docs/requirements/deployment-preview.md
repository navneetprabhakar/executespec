# Deployment & Preview — Requirements

## Overview

Dual-mode preview system (Docker Compose for local, Cloud Run for dev/prod), permanent Cloud Run deployment for 11 languages, workspace management with GCS archiving, and GitHub integration for code push.

### Preview Mode Architecture

| Mode | Environment | Infrastructure | TTL | HTTPS |
|------|-------------|---------------|-----|-------|
| `docker` | Local dev | Docker Compose + optional Caddy proxy | 10 min | Caddy + Cloudflare Origin Cert |
| `cloud-run` | Dev/Prod (GCE) | Artifact Registry + Cloud Run | 10 min | Managed by Cloud Run |

All subprocess commands (`gcloud`, `docker compose`) are executed with enforced process timeouts to prevent hung builds. Virtual threads used for async preview operations are wrapped with `ContextSnapshotFactory.captureAll()` to ensure traceId/spanId propagation across thread boundaries.

Configured via `preview.mode` in `application.yml` (default: `docker`).

**Cloud Run preview flow:** Generate Dockerfile → Build image locally → Push to Artifact Registry → Deploy to Cloud Run (min 0, max 1 instance) → Health check → Return Cloud Run URL → Auto-delete after TTL.

**Docker preview flow:** Generate Dockerfile + docker-compose.yml → `docker compose up --build` → Caddy route or direct port → Health check → Return preview URL → Auto-cleanup after TTL.

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
- Status lifecycle: IDLE → STARTING → RUNNING / FAILED / STOPPED

**Project Type Detection:**

| Type | Detection | Command |
|------|-----------|---------|
| SPRING_BOOT | `pom.xml` with spring-boot | `mvn spring-boot:run` |
| GRADLE | `build.gradle` | `gradle bootRun` |
| FRONTEND | `package.json` | `npm run dev` |
| FULLSTACK | Backend + frontend detected | Both in parallel |
| PYTHON | `requirements.txt` / `pyproject.toml` | `uvicorn` or `flask` |
| GO | `go.mod` | `go run .` |
| RUBY | `Gemfile` | `rails server` |
| PHP | `composer.json` | `php -S` |
| RUST | `Cargo.toml` | `cargo run` |
| DOTNET | `*.csproj` | `dotnet run` |
| STATIC | `index.html` | `python -m http.server` |

### 2. Cloud Run Deployment (11 Languages)

**Purpose:** Deploy completed runs to Google Cloud Run.

**Requirements:**
- Generate language-specific Dockerfiles via DockerfileGenerator
- Build Docker image via ProcessBuilder
- Push to GCP Artifact Registry
- Deploy to Cloud Run (1 CPU, 512Mi, max 1 instance, unauthenticated)
- 7-day TTL with cleanup job (cron)
- Emit SSE events: DEPLOY_STARTED, DEPLOY_BUILDING, DEPLOY_PUSHING, DEPLOY_LIVE, DEPLOY_FAILED
- Support: Spring Boot, Gradle, Node.js, Python, Go, Rust, Ruby, PHP, .NET, Fullstack, Static
- API: `POST /api/runs/{id}/deploy`, `GET /api/deployments`, `DELETE /api/deployments/{id}`

### 3. Workspace Management

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
  - `listRepoFiles()` — list files via GitHub Trees API
  - `readRepoFile()` — read file content via GitHub Contents API
  - Requires the run to have an associated GitHub repo (created via GitHubRepoService)

### 4. GCS Workspace Archiving

**Requirements:**
- Archive completed workspaces to GCS after 24 hours (`archive-after-hours`)
- Store as zip: `gs://executespec-workspaces/workspaces/{runId}.zip`
- Delete local workspace after successful upload
- Download redirects to 5-minute signed GCS URL (HTTP 302)
- WorkspaceArchiveJob runs hourly
- Local workspace retention: 30 days (configurable)
- GCS bucket: `${GCS_WORKSPACE_BUCKET:executespec-workspaces}`

### 5. GitHub Integration

**Requirements:**
- Per-user GitHub PAT stored in app_user table
- Configured in Settings → GitHub Integration
- Three modes:
  - None: no GitHub interaction
  - Create new repo: auto-creates private GitHub repo, pushes code
  - Start from existing: clones specified repo, modifies, pushes back
- PAT priority: user-level PAT → system-level PAT
- Clone via HTTPS with PAT authentication
- Support shallow clone for large repos
- **New repos:** create private repo, push main branch
- **Existing repos:** create branch `executespec/{runId-short}`, push to branch, open PR via `GitHubRepoService.createPullRequest()`, store PR URL in `PipelineRun.githubPrUrl`
- Optional push after merge (controlled by run properties)
- Endpoint: `PUT /api/auth/settings/github`
- **Manual push endpoint:** `POST /api/runs/{id}/push-github` — push workspace to GitHub on demand
- **Random memorable repo names:** New repos use adjective-noun format (e.g. "swift-ember") instead of goal-derived slugs
- **Repo description:** Set from user goal text on creation
- **Collision handling:** If repo name already exists, append random 2-digit suffix (up to 3 retries before failing)
- **Description update for existing repos:** `updateRepoDescription()` updates the description on existing repos after push

### 6. Workspace Export

**Requirements:**
- Download workspace as ZIP via `GET /api/runs/{id}/download`
- If workspace is local: stream ZIP directly
- If workspace archived to GCS: redirect to signed URL
- **GitHub fallback:** If local workspace is deleted and GCS is not configured, redirect to GitHub archive ZIP (via GitHub API)
- Exclude `.git/`, `node_modules/`, `target/`, `build/` from ZIP

### 7. Reliability & Observability

**Requirements:**
- All subprocess commands (`gcloud`, `docker`) must have enforced timeouts: deploy operations 10 minutes, delete/rollback operations 5 minutes, list operations 2 minutes
- Pipe buffer deadlock prevention — stdout and stderr must be read in parallel (separate threads) for all `gcloud` commands to avoid process hangs from full OS pipe buffers
- Virtual threads must wrap runnables with `ContextSnapshotFactory.captureAll()` to ensure traceId/spanId propagation into async preview operations
- `ACTIVE_PREVIEWS` gauge must be decremented on ALL terminal states: FAILED, TIMED_OUT, and STOPPED (not only on successful stop)
- Cloud Run resource cleanup on preview failure — both the Cloud Run service and the Artifact Registry image must be deleted when a preview fails to start or times out
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
| `api/controller/PreviewController.java` | Preview REST endpoints (start, stop, logs, API docs probe) |
| `deployment/DeploymentService.java` | Cloud Run deployment |
| `deployment/DockerfileGenerator.java` | Language-specific Dockerfiles |
| `deployment/strategy/*.java` | 11 deployment strategies |
| `deployment/ArtifactRegistryClient.java` | Docker push to GCR |
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
