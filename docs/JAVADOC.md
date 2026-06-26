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
| `harness` | Sequential task executor, task-tier routing, durable + live SSE goal/continuation decision visibility with configurable limits, default-off goal-stop enforcement, and opt-in selected-candidate continuation reruns. |
| `execution` | Legacy Launches plus goal evidence, doneness, continuation decision, evaluator, candidate-backed advisor, and continuation-policy seams. |
| `agent` | Planner/classifier/router/client support and legacy non-tool LLM paths. |
| `agentsession` | Durable CLI/VS Code agent-session protocol: sessions, turns, intent/action catalog, gate cards. |
| `router` | Model registry, resolver, provider enum, model override services, aggregator mappers, candidate collection, capability aggregation, and the inert quality-first model/provider ranking policy. |
| `tool` | Worker tools, sandbox policy, file tools, build/test tools, background jobs. |
| `reviewer` | Reviewer agent and accuracy assessment helpers. |
| `report` | Validation report and verified-score computation. |
| `cost` | Run cost tracking, pricing lookup, and durable per-call LLM usage attribution (`run_step_usage`). |
| `auth` | Auth, org, project, RBAC, SSO, PAT, provider preference, GDPR export. |
| `billing` | Plans, subscriptions, gateways, invoices, top-ups, quota refunds. |
| `codeintel` | Codemap, architecture generation, staleness, tiered codemap selection. |
| `memory` | Project memory, project chat, chat tools. |
| `benchmark` | SWE-bench execution harness plus common benchmark tracking. |
| `terminalbench` | Operator-only Terminal-Bench remote-worker adapter. |

## Important Controllers

| Controller | Main routes |
| --- | --- |
| `RunController` | `/api/runs`, approval, retry, cancel, archive, sync, push, cost (live `v2` cost exposes `schemaVersion`/`source`/cache/reasoning/`calls`). `localExecution=true` is PAT-only and gated by `worker.local-hands.enabled`. |
| `ModelController` | `/api/models/available`. |
| `OrgController` | `/api/org`, `/api/org/me/model-matrix` (defaults, overrides, selectable models, `modelLabels`), org settings. |
| `AuthController` | `/api/auth/*`, user settings, PATs, provider-key validation, `/me/provider-preference`. |
| `AgentSessionController` | `/api/agent/*` — sessions, recorded + streaming turns, action catalog, intent interpret, context resolve, gate cards (CLI/VS Code). |
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
| `TaskWaveScheduler` | Pure dependency and bounded file-conflict wave planner for future safe parallel execution. |
| `GoalDonenessEvaluator` / `GoalContinuationPolicy` / `GoalContinuationAdvisor` | Goal-state, candidate-backed continue/escalate/stop advice, and pure policy seams. |
| `WorkerAgent` | Native tool-calling worker. |
| `LlmRouter` | Provider dispatch, retry, failover, cost tracking. |
| `ModelRegistry` / `ModelResolver` | YAML model matrix and tier lookup. |
| `ModelProviderCandidateService` | Converts YAML model metadata, Redis local performance, explicit availability, provider preference order, and external benchmark/validation signals into explainable model/provider candidates. |
| `RunCostTracker` | Token/cost accounting and pricing lookup. |
| `RunStepUsageRecorder` | Durable per-call LLM usage write to `run_step_usage` (role/step/retry attribution; fail-soft, `REQUIRES_NEW`). |
| `ValidationReportService` | Additive verifiedScore, trust caps, verification ledger, and report assembly. |
| `VerifiedScoreBackfillService` | SUPER_ADMIN one-shot recomputation of historical report scores. |
| `AgentSessionService` | CLI/VS Code session/turn persistence, access checks, gate cards, repo→project resolution. |
| `AiProviderPreferenceService` | Cross-surface `app_user.preferred_ai_provider` read/resolve/update. |
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

Migration head in this worktree: `V250`.

Recent relevant migrations:

- `V236`: benchmark tracking tables.
- `V237`: DeepSeek and Fireworks key lanes on `org` and `app_user`.
- `V238`: benchmark token-tracking columns.
- `V239`: default-on the evidence-safe worker quality gates.
- `V240`: `agent_session` / `agent_turn` / `agent_action_event` / `agent_usage_ledger` — CLI/VS Code agent-session protocol, separate from `pipeline_run`.
- `V241`: seed `worker.local-hands.enabled` (default OFF).
- `V242`: `app_user.preferred_ai_provider` — cross-surface provider preference.
- `V243`: `run_step_usage` — durable per-call LLM usage (tokens/cache/reasoning/cost/call-index + role/step/retry attribution).
- `V244`: `worker.local-hands.enabled` default ON — CLI/VS Code local execution no longer fail-closes at submit when using PAT-authenticated local mode.
- `V245`: `managed_ai_usage_period`, `managed_ai_usage_event`, and `managed_ai_state_notification` — ES AI credit ledger, raw-COGS caps, step-down/step-up routing state, and notification dedupe.
- `V246`: `agent_session.run_id` — durable run-scoped web agent sessions.
- `V247`: `agent_action_event.action_id` — first-class action/result correlation id for agent clients.
- `V248`: FREE tier sentinel fix — force `tier_config.monthly_run_limit` and `billing_plan.run_limit` back to 5 when stale rows still carry any other value, especially `-1` unlimited.
- `V249`: seed `execution.goal-decision.enforcement.enabled` default OFF — executor task-boundary aborts after negative goal decisions.
- `V250`: `agent_task.goal_doneness_json` and `agent_task.continuation_decision_json` — durable task-level goal state and next-action advice for run detail clients.

## Deprecated / Transitional Areas

- `FallbackLlmRouter` remains for legacy non-tool agents only.
- `push-github` endpoint remains for compatibility; prefer SCM-neutral push paths.
- Several plan docs describe partially shipped flag-gated work; use `docs/document_map.md`, feature docs, and a link scan before deleting or relying on them.
