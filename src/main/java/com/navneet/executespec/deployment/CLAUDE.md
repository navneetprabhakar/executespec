# deployment/ — Preview & Deploy

Dual-mode preview system (Docker Compose for local, Cloud Run for dev/prod), permanent Cloud Run deployment for 11 languages, workspace management with GCS archiving, and GitHub integration.

## Key Files
- `preview/PreviewService.java` — Dual-mode preview lifecycle (docker/cloud-run). TTL: 10min (both modes). Preview auto-starts after COMPLETE via PipelineHarness. Idempotent: returns existing session if already running.
- `preview/PreviewProperties.java` — Config: mode, TTL, concurrency limits, proxy settings.
- `preview/CaddyProxyManager.java` — Caddy JSON config via admin API (Docker mode only, with Cloudflare Origin Cert).
- `preview/DevDockerfileGenerator.java` — Preview-specific Dockerfile generation. `detect()` reads `.agent-context/project.json` (planner-declared type) first, falls back to file-based heuristics. Static HTML check runs before loose JS/TS fallback. **Single-package full-stack detection:** checks for both backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc) in same `package.json` → returns FULL_STACK. NODE_BACKEND Dockerfile includes `ENV PORT=3000`.
- `preview/PreviewProjectType.java` — Enum: SPRING_BOOT, JAVA, NODE_FRONTEND, NODE_BACKEND, PYTHON, GO, RUST, RUBY, PHP, FULL_STACK, STATIC_HTML (11 types).
- `preview/ComposeFileGenerator.java` — Generates docker-compose.yml for Docker previews. Port mapping uses `PreviewProjectType.getContainerPort()`.
- `DeploymentService.java` — Full Cloud Run deployment. 11-language support, 7-day TTL.
- `CloudRunClient.java` — GCP Cloud Run API client. All subprocess calls have timeouts.
- `ArtifactRegistryClient.java` — Docker build + push to Artifact Registry. Build/push have timeouts.
- `DockerfileGenerator.java` — Generates language-specific Dockerfiles (11 strategies). Also reads `.agent-context/project.json` to select correct strategy before file-based detection.
- `GcsWorkspaceStore.java` — Archives workspaces to GCS after 24h.
- `DeploymentGitHubService.java` — Push to GitHub (per-user PAT).

## Project Type Detection Flow
```
Planner → plan JSON with "project_type": "STATIC_HTML"
  → PipelineHarness.initAgentContext() writes .agent-context/project.json
  → DevDockerfileGenerator.detect() reads project.json → STATIC_HTML
  → DockerfileGenerator reads project.json → StaticDeploymentStrategy
  → Fallback: file-based heuristic detection if project.json missing/invalid

File-based heuristics (Node.js):
  1. Check for monorepo (workspaces) → detectMonorepoType()
  2. Check for backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc)
     → Both present in same package.json → FULL_STACK (single-package full-stack)
     → Backend only → NODE_BACKEND
  3. No backend deps → NODE_FRONTEND
```

## Single-Package Full-Stack Dockerfile
For projects with both backend + frontend in one `package.json` (e.g., express + react with concurrently):
- Uses `npm run dev` if dev script exists (typically runs concurrently)
- Falls back to starting backend + vite in parallel
- Sets `ENV HOST=0.0.0.0` and `ENV PORT=3000`
- Exposes both 5173 (frontend) and 3000 (backend)

## Patterns
- Workspace: per-run isolated directory (workspace/{runId}/)
- Preview auto-starts after COMPLETE via PipelineHarness (both PROTOTYPE and PRODUCTION)
- TestPreviewCoordinator reuses existing preview via `previewService.getStatus()` check
- Preview NOT stopped after test execution (stays up for user's full 10-min TTL)
- Deployment cleanup via scheduled job (DeploymentCleanupJob)
- All `gcloud`/`docker` subprocess calls have timeouts (deploy: 10min, delete: 5min, list: 2min)
- Stdout/stderr read in parallel (CompletableFuture) to prevent pipe buffer deadlock
- Virtual threads wrapped with `ContextSnapshotFactory.captureAll().wrap()` for trace propagation
- ACTIVE_PREVIEWS gauge decremented on ALL terminal states (FAILED, TIMED_OUT, STOPPED)
- Cloud Run resources (service + AR image) cleaned up on preview failure
- `getLogs()` supports both Docker Compose and Cloud Run (via `gcloud logging read`)
- `probeApiDocs()` works for both Docker (localhost:port) and Cloud Run (service URL)

## Preview Mode Architecture

| Mode | Environment | Infrastructure | TTL | HTTPS |
|------|-------------|---------------|-----|-------|
| `docker` | Local dev | Docker Compose + optional Caddy proxy | 10 min | Caddy + Cloudflare Origin Cert |
| `cloud-run` | Dev/Prod (GCE) | Artifact Registry + Cloud Run | 10 min | Managed by Cloud Run |

Configured via `preview.mode` in `application.yml` (default: `docker`).
