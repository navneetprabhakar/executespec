# `deployment/` — Preview + deploy

> Last reviewed: 2026-05-12 (1.0.0-M1).

Triple-mode preview system (Docker Compose for local, VM for dev, Cloud Run for prod), permanent Cloud Run deployment for 9 languages, workspace management with GCS archiving, and GitHub integration.

## Key files

- `preview/PreviewService.java` — Triple-mode preview lifecycle (docker / vm / cloud-run). 5-min TTL (prod default). Preview auto-starts after terminal via `RunOrchestrator` (was `PipelineHarness` in V1; deleted 2026-04-13). Idempotent: returns existing session if already running. `cleanupExpired()` runs every 60s to tear down expired previews from in-memory `active` map. **`handoffToPreviewEngine`** distinguishes "Docker daemon not reachable" from real preview-engine bugs (concise WARN vs ERROR + stack).
- `preview/PreviewProperties.java` — Config: mode, TTL, concurrency limits, proxy settings.
- `preview/CaddyProxyManager.java` — Caddy JSON config via admin API (Docker mode only, with Cloudflare Origin Cert).
- `preview/DevDockerfileGenerator.java` — Preview-specific Dockerfile generation. `detect()` reads `.agent-context/project.json` (planner-declared type) first via `DeclaredPreviewProjectTypeResolver`, then falls back to file-based heuristics. Static HTML check runs before loose JS/TS fallback. **Single-package full-stack detection:** checks for both backend deps (express/fastify/etc) AND frontend deps (react/vue/vite/etc) in same `package.json` → returns FULL_STACK. NODE_BACKEND Dockerfile includes `ENV PORT=3000`.
- `preview/DeclaredPreviewProjectTypeResolver.java` — Package-local planner-declared project-type normalization/validation helper. Maps planner aliases (`java-spring-boot`, `node-js-frontend`, `cli`, `static`) to `PreviewProjectType`, refuses inconsistent declarations, and upgrades planner Spring/Node backend declarations to FULL_STACK when separate frontend/backend directories exist.
- `preview/PreviewDependencySignals.java` — Package-local dependency/name vocabulary helper used by preview detection. Owns backend dependency names, broad frontend dependency names, intentionally narrow monorepo frontend-signal names, and frontend/backend directory-name classification.
- `preview/PreviewFileSignals.java` — Package-local root-file signal helper. Owns small-file literal pattern checks plus root-only static HTML, JS/TS, and Java file detection with fail-closed missing/unreadable path handling.
- `preview/PreviewDockerfileSnippets.java` — Package-local Dockerfile string snippets shared by preview generators: Cloud Run non-root user blocks, Vite allowed-hosts patch, Vite proxy rewrite patch, and single-package Vite dev-script patch.
- `preview/PreviewPythonSignals.java` — Package-local Python preview signal helper. Owns requirements/pyproject dependency checks, root Python file scanning, entrypoint priority, Cloud Run shell-form command resolution, and Docker exec-form command/env resolution for Django, FastAPI, Flask, and generic Python apps.
- `preview/PreviewSpringBootDockerfiles.java` — Package-local Spring Boot preview Dockerfile helper. Owns root-vs-`backend/` Maven/Gradle Dockerfile selection and Cloud Run Spring Boot command assembly.
- `preview/PreviewNodeSignals.java` — Package-local Node preview signal helper. Owns `package.json` parsing, Node entrypoint fallback order, subdirectory entrypoint fallback order, and backend port detection from `PORT || NNNN` / `listen(NNNN)` patterns.
- `preview/PreviewModeDetector.java` — Package-local preview-mode classifier. Owns WEB vs CLI terminal vs MCP inspector vs mobile Expo mode routing from project type plus dependency/source signals; `DevDockerfileGenerator.detectMode()` delegates here.
- `preview/PreviewStandaloneDockerfiles.java` — Package-local standalone-template helper. Owns fixed Go, Rust, Java, static HTML, React Native/Expo, CLI Go, and CLI Rust Dockerfile bodies for local Docker and Cloud Run variants.
- `preview/PreviewProjectType.java` — Enum: SPRING_BOOT, JAVA, NODE_FRONTEND, NODE_BACKEND, PYTHON, GO, RUST, FULL_STACK, STATIC_HTML (9 types).
- `preview/ComposeFileGenerator.java` — Generates docker-compose.yml for Docker previews. Port mapping uses `PreviewProjectType.getContainerPort()`. Blocks cloud metadata endpoints via `extra_hosts` (metadata.google.internal + 169.254.169.254 → 127.0.0.1). App containers hardened with `no-new-privileges`, `cap_drop: ALL`.
- `preview/vm/PreviewDeploymentService.java` — VM-mode preview orchestration. SSH/SCP to preview-gw VM, invokes `/opt/preview-engine/bin/deploy-preview.sh`, parses `PREVIEW_JSON:{...}` output. 5-min process timeout.
- `preview/vm/PreviewVmProperties.java` — VM config: gateway IP, SSH key, TTL (minutes), VM list with load balancing.
- `preview/vm/PreviewGarbageCollector.java` — DB-backed GC for VM previews. Runs every 10 min, tears down previews past `expiresAt`. Safety net for app restarts (in-memory map lost).
- `preview/vm/PreviewRegistry.java` — JPA entity for `preview_registry` table. Tracks status, VM assignment, port, TTL, deploy duration.
- `preview/vm/PreviewVmController.java` — REST: teardown, extend TTL, list active, stats, per-VM stats.
- `DeploymentService.java` — Full Cloud Run deployment. 9-language support. Tier-based deployment limits enforced via `tier_config.max_deployments` (FREE=1, PRO=3, TEAM/ENTERPRISE=-1).
- `CloudRunClient.java` — GCP Cloud Run API client. All subprocess calls have timeouts.
- `ArtifactRegistryClient.java` — Docker build + push to Artifact Registry. Build/push have timeouts.
- `DockerfileGenerator.java` — Generates language-specific Dockerfiles (9 strategies). Also reads `.agent-context/project.json` to select correct strategy before file-based detection.
- `GcsWorkspaceStore.java` — Archives workspaces to GCS after 24h.
- `DeploymentGitHubService.java` — Push to GitHub for deploy flow (per-user PAT). Note: pipeline push flow uses `ScmPushService.pushMultiRepo` (auto-push at run-end) instead — `gitCommit` is denied for workers via `ToolRegistry.bindForWorker(ctx)`.

### `preview/verify/` — verifyBoot (RL-2026-05-066, preview Phase 1)

The boot-verification spine — the agent authors a run-recipe, the platform boots + health-checks the app, and a deterministic verdict feeds the verifiedScore BUILD slot (mirrors the `smoketest/` test-execution seam). **Flag-gated OFF + fail-open + inert until preview Phase 0** wires the boot engine; production is byte-identical.

- `BootHealthEvaluator.java` — pure, deterministic classifier (the `smoketest/TestOutputParser` analogue). `BootSignals` (build/start/health/timeout) → `Verdict` (BUILD_FAILED < DID_NOT_START < TIMED_OUT < UNHEALTHY < HEALTHY) + `BootVerification`. No I/O; standalone-tested.
- `RunRecipe.java` — the agent-authored `.agent-context/run-recipe.json` contract (`port` / `healthPath` / `startTimeoutSec`), normalised + hard-capped (`MAX_START_TIMEOUT_SEC=120`) by the compact constructor.
- `PreviewBootEngine.java` — the build-and-boot seam **preview Phase 0 fills** (buildpack adapter + isolated deny-by-default-egress network + dedicated least-privilege SA + `CodeAbuseScanner` pre-build + `--network none` cleanroom). No impl yet → fail-open.
- `PreviewVerifier.java` — `@Component` runner (the `WorkspaceTestRunner` analogue). Reads the recipe → (capability-gated on `preview.buildpack.enabled` + an `ObjectProvider<PreviewBootEngine>` being present) → `engine.boot` → `BootHealthEvaluator.evaluate`. **Fail-open:** no recipe / no engine / flag off / any exception → `Optional.empty()` → the BUILD slot keeps its compile-only signal.

Worker surface: `tool/VerifyBootTool` (`verifyBoot`, `@Component implements Tool`, gated `worker.verify-boot.enabled`, V227) + the flag-gated `SequentialExecutor` prompt directive. Scoring: `ReportInput.bootVerification` → `ValidationReportService` BUILD slot (`bootSlotScore`); populated in `RunOrchestrator.generateValidationReport` via a final `PreviewVerifier.verify`. Both flags seeded OFF by **V227**.

## Project Type Detection Flow

```
Planner → plan JSON with "project_type": "STATIC_HTML"
  → RunOrchestrator writes .agent-context/project.json
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
- Preview auto-starts after terminal via `RunOrchestrator` (PROTOTYPE + PRODUCTION). PATCH-mode runs (autofix + bench) skip auto-preview.
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

| Mode        | Environment | Infrastructure                            | TTL    | HTTPS                          |
| ----------- | ----------- | ----------------------------------------- | ------ | ------------------------------ |
| `docker`    | Local dev   | Docker Compose + optional Caddy proxy     | 5 min  | Caddy + Cloudflare Origin Cert |
| `vm`        | Dev (GCE)   | SSH/SCP to preview-gw VM + Docker + Caddy | 15 min | Caddy on preview-gw            |
| `cloud-run` | Prod (GCE)  | Artifact Registry + Cloud Run             | 5 min  | Managed by Cloud Run           |

Configured via `preview.mode` in `application.yml` (default: `docker`, dev: `vm`).

## VM Preview Architecture

Separate repo: [`navneetprabhakar/preview-engine`](https://github.com/navneetprabhakar/preview-engine) — see its CLAUDE.md for script details.

```
App VM (executespec)                    Preview GW VM (preview-gw)
┌────────────────────────────┐          ┌──────────────────────────────────┐
│ PreviewService              │          │ /opt/preview-engine/bin/         │
│   → buildAndStartVm()      │──SSH/SCP─▶│   deploy-preview.sh            │
│   → PreviewDeploymentService│          │   teardown-preview.sh           │
│   → PreviewGarbageCollector │          │                                 │
│                             │          │ Caddy (port 443)                │
│ preview_registry (DB)       │          │   *.preview.executespec.ai      │
│   → source of truth for GC  │          │                                 │
└────────────────────────────┘          │ Docker: preview-{id} on :10000+ │
                                        └──────────────────────────────────┘
```

**VM preview flow:**

1. `PreviewService.buildAndStartVm()` generates Dockerfile + docker-compose.yml in workspace
2. `PreviewDeploymentService.deploy()` SCPs workspace to preview-gw, invokes `deploy-preview.sh` via SSH
3. Script builds Docker image, starts container on random port (10000-60000), configures Caddy, health checks
4. Script outputs `PREVIEW_JSON:{...}` — parsed by `PreviewDeploymentService`
5. `PreviewService` updates session, emits `PREVIEW_READY` SSE event

**VM preview GC (3 layers):**

1. **App-side** (`PreviewService.cleanupExpired()`): every 60s, checks in-memory `active` map against `expiresAt`
2. **DB-backed** (`PreviewGarbageCollector`): every 10 min, queries `preview_registry` for expired entries — catches orphans after app restart
3. **VM cron** (`cleanup-expired.sh`): every 5 min, checks directory age on preview-gw — last-resort safety net

**VM config:** `preview.vm.*` in `application.yml` — gateway IP, SSH key, TTL, VM list with `maxConcurrent` for load balancing.
