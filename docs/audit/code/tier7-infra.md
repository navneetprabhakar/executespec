# Audit: Tier 7 infra — `repo/` + `notification/` + `sse/` + `observability/` + `config/` + `util/`

Status: **DONE** · Updated 2026-05-29 · 49 files / ~6,533 lines · substantive files read, rest scanned

## What it does
- **`repo/`** (5) — `GitHubRepoService(1450)` + `BitbucketRepoService(313)` (clone/PR/webhook-install via the caller's PAT), `GitHubAppController`, `MonorepoDetector`.
- **`sse/`** (3) — `SseBroadcaster(402)` + `RunEvent(389)` (the run event stream + 24h Redis replay).
- **`notification/`** (10) — in-app + email run-completion + `pref/` preferences.
- **`config/`** (23) — `FeatureFlagService`, `AgentProperties`, `SecretManagerConfigLoader`, `PromptVersioningService`, `AgentModelConfig`, metrics config, SPA fallback.
- **`observability/`** (1) — `Metrics` (Micrometer custom metrics). · **`util/`** (3) — `HmacUtil` etc.

## Findings — uniformly clean infra.
- 🟢 **SCM clients use `ProcessBuilder(argv)` for git** (`git clone`/`git init`) — no shell injection. The clone authUrl embeds the caller's PAT (`https://<pat>@github.com/…`) — standard for git, argv-passed (not shell), short-lived on the VM; PAT could appear in process args/error logs (minor, local-only).
- 🟢 `util/HmacUtil` (HMAC-SHA256 hex) is the shared inbound-webhook-signature primitive used by the verified webhook adapters; `config/FeatureFlagService.isEnabled` is the Redis-cached flag gate every feature toggle flows through (Redis pub/sub invalidation + 60s reload). `config/SecretManagerConfigLoader` pulls prod secrets from GCP Secret Manager. SSE uses scoped 5-min tokens ([auth.md](auth.md)).
- 📝 **`GitHubAppController` webhook signature verification is an unimplemented skeleton** (its own doc: "NOT in this skeleton: webhook signature verification (HMAC), App-token minting"). The installation-based GitHub App path is incomplete + ORG_ADMIN-gated; the production PR webhook at `/webhooks/github` IS HMAC-verified ([project.md](project.md)), so this is a dormant skeleton, not a live gap. Finish or remove before activating the App path.
- 🟢 `notification/` (in-app + email run-completion + per-user prefs) and `observability/Metrics` (the `executespec.v2.*` custom metric series) are standard, clean.

## Cross-refs
GitHub/Bitbucket consumed by → [project.md](project.md) (repo link/sync), [integration.md](integration.md) (`ScmPushService`), [deployment.md](deployment.md). Verified PR webhook → [project.md](project.md) (`GitHubWebhookController`). Feature flags drive every gated feature → all tiers. SSE scoped tokens → [auth.md](auth.md) (`JwtUtil.generateSseToken`). Secret loading → [crypto.md](crypto.md) (GCP Secret Manager key source).
