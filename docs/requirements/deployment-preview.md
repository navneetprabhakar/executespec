# Deployment & Preview — Requirements

## Overview

Local process-based preview for development testing, Cloud Run deployment for 11 languages, workspace management with GCS archiving, and GitHub integration for code push.

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
- Automatic branch creation and merge to main
- Optional push after merge (controlled by run properties)
- Endpoint: `PUT /api/auth/settings/github`

### 6. Workspace Export

**Requirements:**
- Download workspace as ZIP via `GET /api/runs/{id}/download`
- If workspace is local: stream ZIP directly
- If workspace archived to GCS: redirect to signed URL
- Exclude `.git/`, `node_modules/`, `target/`, `build/` from ZIP

---

## Key Files

| File | Purpose |
|------|---------|
| `deployment/LocalPreviewService.java` | Process-based preview |
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
| `repo/GitHubRepoService.java` | GitHub repo create/push |

## Database Migrations

| Version | Description |
|---------|-------------|
| V8 | Per-user GitHub settings (github_pat, github_owner) |
| V12 | GCS workspace archive columns |
