# deployment/ — Preview & Deploy

Dual-mode preview system (Docker Compose for local, Cloud Run for dev/prod), permanent Cloud Run deployment for 9 languages, workspace management with GCS archiving, and GitHub integration.

## Key Files
- `preview/PreviewService.java` — Dual-mode preview lifecycle (docker/cloud-run). TTL: 5min (both modes). Preview auto-starts after COMPLETE via PipelineHarness. Idempotent: returns existing session if already running.
- `preview/PreviewProperties.java` — Config: mode, TTL, concurrency limits, proxy settings.
- `preview/CaddyProxyManager.java` — Caddy JSON config via admin API (Docker mode only, with Cloudflare Origin Cert).
- `preview/DevDockerfileGenerator.java` — Preview-specific Dockerfile generation. `detect()` reads `.agent-context/project.json` (planner-declared type) first, falls back to file-based heuristics. Static HTML check runs before loose JS/TS fallback. **Single-package full-stack detection:** checks for both backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc) in same `package.json` → returns FULL_STACK. NODE_BACKEND Dockerfile includes `ENV PORT=3000`.
- `preview/PreviewProjectType.java` — Enum: SPRING_BOOT, JAVA, NODE_FRONTEND, NODE_BACKEND, PYTHON, GO, RUST, FULL_STACK, STATIC_HTML (9 types).
- `preview/ComposeFileGenerator.java` — Generates docker-compose.yml for Docker previews. Port mapping uses `PreviewProjectType.getContainerPort()`. Blocks cloud metadata endpoints via `extra_hosts` (metadata.google.internal + 169.254.169.254 → 127.0.0.1). App containers hardened with `no-new-privileges`, `cap_drop: ALL`.
- `DeploymentService.java` — Full Cloud Run deployment. 9-language support, 7-day TTL. Tier-based deployment limits enforced via `TierConfig.maxDeployments` (FREE=0, PRO=5, ENTERPRISE=-1).
- `CloudRunClient.java` — GCP Cloud Run API client. All subprocess calls have timeouts.
- `ArtifactRegistryClient.java` — Docker build + push to Artifact Registry. Build/push have timeouts.
- `DockerfileGenerator.java` — Generates language-specific Dockerfiles (9 strategies). Also reads `.agent-context/project.json` to select correct strategy before file-based detection.
- `GcsWorkspaceStore.java` — Archives workspaces to GCS after 24h.
- `DeploymentGitHubService.java` — Push to GitHub for deploy flow (per-user PAT). Note: pipeline push flow uses `PipelineHarness.pushToGitHubIfConfigured()` → `GitTool.pushToBranch()` instead.

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
- Preview NOT stopped after test execution (stays up for user's full 5-min TTL)
- Deployment cleanup via scheduled job (DeploymentCleanupJob)
- All `gcloud`/`docker` subprocess calls have timeouts (deploy: 10min, delete: 5min, list: 2min)
- Stdout/stderr read in parallel (CompletableFuture) to prevent pipe buffer deadlock
- Virtual threads wrapped with `ContextSnapshotFactory.captureAll().wrap()` for trace propagation
- ACTIVE_PREVIEWS gauge decremented on ALL terminal states (FAILED, TIMED_OUT, STOPPED)
- Cloud Run resources (service + AR image) cleaned up on preview failure
- `getLogs()` supports both Docker Compose and Cloud Run (via `gcloud logging read`)
- `probeApiDocs()` works for both Docker (localhost:port) and Cloud Run (service URL)
- Cloud Run orphan cleanup race fix: `cloudRunServiceName` stored upfront in initial session (`es-preview-{runIdShort}`), preventing orphan cleanup job from deleting services during build/push phase

## Security
- **Env var whitelist:** `ComposeExecutor` clears inherited env vars and only passes: PATH, HOME, USER, LANG, TZ, DOCKER_HOST, DOCKER_CONFIG, JAVA_HOME, HTTP_PROXY, HTTPS_PROXY, NO_PROXY. Prevents leaking JWT_SECRET, API keys, etc.
- **Metadata blocking:** App containers get `extra_hosts` to redirect cloud metadata endpoints (169.254.169.254, metadata.google.internal) to 127.0.0.1.
- **Container hardening:** App containers run with `no-new-privileges:true`, `cap_drop: ALL`. DB containers are exempt (need chown/chmod).
- **Concurrent limit:** Preview start is synchronized to prevent TOCTOU race on the concurrent limit check.
- **Test execution:** Runs inside Docker container (`maven:3.9-eclipse-temurin-21`) with `--read-only`, `--memory=4g`, `--cpus=2`, `--cap-drop ALL`, `--security-opt no-new-privileges:true`. Workspace mounted read-only.

## Preview Mode Architecture

| Mode | Environment | Infrastructure | TTL | HTTPS |
|------|-------------|---------------|-----|-------|
| `docker` | Local dev | Docker Compose + optional Caddy proxy | 5 min | Caddy + Cloudflare Origin Cert |
| `cloud-run` | Dev/Prod (GCE) | Artifact Registry + Cloud Run | 5 min | Managed by Cloud Run |

Configured via `preview.mode` in `application.yml` (default: `docker`).
