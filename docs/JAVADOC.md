# Backend API Reference

> Compact current reference for the Java backend. This replaces the old generated-style `JAVADOC.md` body with a maintained package/API map.

For exhaustive class discovery use:

- [`CODEMAP.md`](CODEMAP.md)
- package-level `CLAUDE.md` files
- `rg --files src/main/java`

## Core Packages

| Package | Purpose |
| --- | --- |
| `api` | Run controllers, model controller, global advice, admin surfaces. |
| `integration` | Run orchestration, lifecycle, persistence writer, SCM push integration. |
| `engine` | Worker agent and code-generation engine seam. |
| `harness` | Sequential task executor and task-tier routing. |
| `agent` | Planner/classifier/router/client support and legacy non-tool LLM paths. |
| `router` | Model registry, resolver, provider enum, model override services, aggregator mappers. |
| `tool` | Worker tools, sandbox policy, file tools, build/test tools, background jobs. |
| `reviewer` | Reviewer agent and accuracy assessment helpers. |
| `report` | Validation report and verified-score computation. |
| `auth` | Auth, org, project, RBAC, SSO, PAT, GDPR export. |
| `billing` | Plans, subscriptions, gateways, invoices, top-ups, quota refunds. |
| `codeintel` | Codemap, architecture generation, staleness, tiered codemap selection. |
| `memory` | Project memory, project chat, chat tools. |
| `benchmark` | SWE-bench execution harness plus common benchmark tracking. |
| `terminalbench` | Operator-only Terminal-Bench remote-worker adapter. |

## Important Controllers

| Controller | Main routes |
| --- | --- |
| `RunController` | `/api/runs`, approval, retry, cancel, archive, sync, push, cost. |
| `ModelController` | `/api/models/available`. |
| `OrgController` | `/api/org`, `/api/org/me/model-matrix`, org settings. |
| `AuthController` | `/api/auth/*`, user settings, PATs, provider-key validation. |
| `ProjectController` and project companions | `/api/projects/*`, repos, docs, architecture, memory, tech debt. |
| `PrReviewController` | `/api/pr-reviews`. |
| `CodeReviewController` | `/api/code-reviews`. |
| `BenchmarkTrackingController` | `/api/v2/test/benchmark` in local/dev/benchmark profiles. |
| `TerminalBenchController` | `/api/v2/test/terminal-bench` in local/dev profiles. |

## Important Services

| Service | Responsibility |
| --- | --- |
| `RunOrchestrator` | Pipeline coordinator. |
| `RunLifecycleService` | Shared terminal/lifecycle operations. |
| `RunPersistenceWriter` | Status/event persistence guard. |
| `SequentialExecutor` | Per-task execution loop. |
| `WorkerAgent` | Native tool-calling worker. |
| `LlmRouter` | Provider dispatch, retry, failover, cost tracking. |
| `ModelRegistry` / `ModelResolver` | YAML model matrix and tier lookup. |
| `RunCostTracker` | Token/cost accounting and pricing lookup. |
| `ValidationReportService` | Additive verifiedScore, trust caps, verification ledger, and report assembly. |
| `VerifiedScoreBackfillService` | SUPER_ADMIN one-shot recomputation of historical report scores. |
| `ProjectAccessGuard` / `RunAccessGuard` | Tenant access checks. |
| `BillingService` / `RunTopupService` | Subscription/top-up operations. |

## Model Providers

Direct providers:

- Anthropic
- OpenAI
- Gemini
- DeepSeek

Aggregators:

- OpenRouter
- Fireworks

Every tier-routed agent should resolve through `ModelResolver` and call through `LlmRouter`.

## Migrations

Migration head in this worktree: `V237`.

Recent relevant migrations:

- `V236`: benchmark tracking tables.
- `V237`: DeepSeek and Fireworks key lanes on `org` and `app_user`.

## Deprecated / Transitional Areas

- `FallbackLlmRouter` remains for legacy non-tool agents only.
- `push-github` endpoint remains for compatibility; prefer SCM-neutral push paths.
- Several plan docs describe partially shipped flag-gated work; use [`plan-disposition-2026-06-10.md`](plan-disposition-2026-06-10.md) before deleting or relying on them.
