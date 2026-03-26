# deployment/ — Preview & Deploy

Cloud Run previews, full deployments, workspace management, and GitHub integration.

## Key Files
- `PreviewService.java` — Cloud Run preview lifecycle (start/stop). 10-min TTL with countdown.
- `DeploymentService.java` — Full Cloud Run deployment. 11-language support, 7-day TTL.
- `CloudRunClient.java` — GCP Cloud Run API client.
- `DockerfileGenerator.java` — Generates Dockerfiles per detected language/framework.
- `GcsWorkspaceStore.java` — Archives workspaces to GCS after 24h.
- `DeploymentGitHubService.java` — Push to GitHub (per-user PAT).
- `preview/PreviewService.java` — Preview-specific service with DevDockerfileGenerator.
- `PreviewAgent.java` — Auto-preview triggered after workers complete.

## Patterns
- Workspace: per-run isolated directory (workspace/{runId}/)
- Preview auto-triggered before review for test execution
- Deployment cleanup via scheduled job (DeploymentCleanupJob)
