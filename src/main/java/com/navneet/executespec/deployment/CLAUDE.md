# deployment/ — Preview & Deploy

Dual-mode preview system (Docker Compose for local, Cloud Run for dev/prod), permanent Cloud Run deployment for 11 languages, workspace management with GCS archiving, and GitHub integration.

## Key Files
- `preview/PreviewService.java` — Dual-mode preview lifecycle (docker/cloud-run). TTL: 5min (Cloud Run), 10min (Docker).
- `preview/PreviewProperties.java` — Config: mode, TTL, concurrency limits, proxy settings.
- `preview/CaddyProxyManager.java` — Caddy JSON config via admin API (Docker mode only, with Cloudflare Origin Cert).
- `preview/DevDockerfileGenerator.java` — Preview-specific Dockerfile generation. `generateForCloudRun()` avoids ttyd.
- `DeploymentService.java` — Full Cloud Run deployment. 11-language support, 7-day TTL.
- `CloudRunClient.java` — GCP Cloud Run API client. All subprocess calls have timeouts.
- `ArtifactRegistryClient.java` — Docker build + push to Artifact Registry. Build/push have timeouts.
- `DockerfileGenerator.java` — Generates language-specific Dockerfiles (11 strategies).
- `GcsWorkspaceStore.java` — Archives workspaces to GCS after 24h.
- `DeploymentGitHubService.java` — Push to GitHub (per-user PAT).
- `PreviewAgent.java` — Auto-preview triggered after workers complete (for test execution).

## Patterns
- Workspace: per-run isolated directory (workspace/{runId}/)
- Preview auto-triggered before review for test execution
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
| `cloud-run` | Dev/Prod (GCE) | Artifact Registry + Cloud Run | 5 min | Managed by Cloud Run |

Configured via `preview.mode` in `application.yml` (default: `docker`).
