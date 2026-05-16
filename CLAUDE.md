# Project: ExecuteSpec

> This file is read by Claude Code at session start. Keep it accurate.

**Version:** `1.0.0-M1` ([`Version`](Version)). Migration head: **V199**. Default branch: `main`. Dev environment: `dev.executespec.ai`.

Spec-driven AI coding platform. Customer submits a spec in natural language → V2 pipeline plans, codes across one or more repos, builds + tests, opens a pull request with a deterministic verified score. Human approves at the plan gate; everything after is automatic.

## Architecture (5-minute orientation)

- **Backend:** Java 21, Spring Boot 3.5, Spring AI 1.1.6, PostgreSQL (Cloud SQL) + Redis. Single Spring Boot JAR; frontend served from `static/`.
- **Frontend:** React 18, Vite 5, Tailwind CSS 4, TypeScript 6. Two coexisting chromes share Zustand stores: V2 sidebar shell (under `frontend/src/components/layout/`) and V3 top-bar shell (under `frontend/src/v3/`).
- **Infra:** GCP us-east4. Single GCE VM serves prod + dev; Cloud Run serves on-demand previews. Cloudflare in front of `*.executespec.ai`.
- **Codebase shape:** 72 top-level packages under `com.navneet.executespec.*` (flat; the historical `v2.*` / `v3.*` prefixes were retired in 2026-05-07's unification pass). 98 REST controllers, 78 entities, 186 Flyway migrations, ~88 prompt files. Frontend ~75K LOC.

### V2 pipeline (the only execution path)

```
RunOrchestrator
  ├─ SmartClassifierAgent (LIGHTWEIGHT)            classify spec: SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY
  ├─ OutlinePlannerAgent (PLANNING)                emit Plan via submitPlan tool
  │   └─ for LARGE/LEGACY: SKELETON_ONLY → parallel TaskExpanderAgent (WORKHORSE) → CoherencePlannerAgent (LIGHTWEIGHT)
  ├─ plan gate (PRODUCTION pauses at AWAITING_APPROVAL; PROTOTYPE bypasses)
  ├─ SequentialExecutor                            per-task tier-routed WorkerAgent + per-task buildProject smoke
  ├─ ReviewerAgent (PLANNING)                      general + wiring passes (project-type aware) + PostEditFormatter
  ├─ ValidationReport persistence + verifiedScore
  └─ auto-preview kickoff + test generation in background
```

Run modes (`pipeline_run.run_mode`): **`BUILD`** (default — the diagram above) and **`PATCH`** (single thin-agent loop against a failing-test spec; skips classifier / planner / reviewer; used by the four autofix surfaces below and the SWE-bench / ProgramBench adapters).

Tools (`tool/`): `readFile`, `writeFile`, `editFile`, `listFiles`, `searchFiles`, `shell`, `gitStatus`, `gitDiff`, `gitCommit`, `buildProject`, `runTests` (patch-mode only), plus `completeTask` for native termination. `ToolRegistry.bindForWorker(ctx)` filters `gitCommit` from worker authority (auto-push happens at run-end via `ScmPushService.pushMultiRepo`).

LLM router (`agent/LlmRouter.java`): native tool calling end-to-end across all three providers. Anthropic via Spring AI; OpenAI via `OpenAiNativeToolClient`; Gemini via `GeminiNativeToolClient` (handles schema sanitization + thought-signature round-trip). Every call flows through `RunCostTracker` (cache-aware pricing from `application.yml model-matrix.model-pricing`). Legacy `FallbackLlmRouter` survives for non-tool-calling agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`).

Workspaces: `<agent.workspace>/<runId>/` (local disk on the VM). Multi-repo runs clone each linked repo into `<workspace>/<runId>/repos/<name>/`. Worker writes flow through tool dispatchers; cleanroom container mounts (SWE-bench, ProgramBench) bind the workspace dir at `/workspace` so host-side tools and container-side tools see the same files.

Workers (`agent/WorkerAgent.java`) — sequential, multi-turn. Per-complexity caps (set in `SequentialExecutor.tierConfigFor`): LOW=30 iters / 4k tokens, MEDIUM=50 / 8k, HIGH=75 / 16k. Per-provider PATCH-mode caps: Anthropic 150, OpenAI 100, Gemini 120. Outcomes: `COMPLETED | COMPLETED_BY_STAGNATION | STOPPED_WITHOUT_COMPLETE | ITERATION_LIMIT_EXCEEDED | BUDGET_EXCEEDED | ERROR`. Two resilience levers fire at end-of-budget: **Lever A** (force-edit watchdog injects "commit to ANY edit now" UserMessage at 0.7×maxIter when `totalWriteCalls=0`) and **Lever B** (best-guess salvage — one final LLM call on iter-limit / budget-exceeded / stagnation-force when no writes have happened). Conversation snapshots persisted every 5 iters to Redis at `v2:agent-ctx:{runId}:{taskId}` (24h TTL).

Scoring (`scoring/`, `report/`): `verifiedScore` is **multiplicative** since 264cca58 — `clamp(0,100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`. `accuracyScore` (LLM-judged) survives as a legacy fallback for pre-V106 rows; UI surfaces only `verifiedScore` since 6f6feeff. PARTIAL status survives but each one carries a `status_reason` (CLEAN_COMPLETION / CLEAN_WITH_INTERMEDIATE_WARNINGS / FINAL_BUILD_FAILED / TESTS_FAILED:N_OF_M / REVIEWER_UNRESOLVED / REVIEWER_FAILED / ALL_TASKS_FAILED / LEGACY_UNKNOWN) so the UI can disambiguate; `StatusBadge` shows it as a tooltip. **Two trust gates** floor specific factors at `reviewerFactor` when the reviewer says CLEAN/FIXED_ISSUES: (1) `endpointFactor` (since 2026-05-10, PR #95) prevents the AccuracyAssessment LLM's read-budget-limited endpoint count from dragging down a healthy wiring pass; (2) `testFactor` (since 2026-05-16) blocks the same class of false-negative for `testCoverageLevel=PARTIAL` when the spec asked for minimal tests and they actually shipped. Gate fires only when `testsTotal==0 && endpointCoveragePercent>=95 && reviewer in {CLEAN, FIXED_ISSUES}`.

### Four customer-facing autofix surfaces (V183 `triggered_by_finding`)

All routed through one chokepoint `finding/FindingPatchSubmitter`. Submitter dedupes per finding key, dispatches with `runMode=PATCH` + `source="finding:<TYPE>"`, and `runPatchPipeline` auto-pushes via `scmPushService.push()` on terminal:

| Phase | Surface                               | UX                                                                                                                 |
| ----- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 1     | `prreview/PrReviewAutofixService`     | PR Review verdict popover → "Autofix N comments" → pushes to PR head branch via `branchOverride`                   |
| 2     | `project/techdebt/TechDebtFixService` | TechDebtPanel category rows → "Fix" per category → fresh PR per category                                           |
| 3     | `project/pr/CiFailureAutoFixService`  | GitHub `check_run` webhook auto-fires on PR CI failure (flag `ci.autofix.enabled`, default OFF; max 3 attempts/PR) |
| 4     | `codereview/CodeReviewAutofixService` | CodeQualityPanel severity chips → "N CRITICAL" / "N MAJOR" → one PR per severity                                   |

Phase 3's webhook stays OFF until SWE-bench Lite resolved% justifies the unattended-bot path.

### Tier ladder (live on `dev` + `main`)

**FREE** (5 runs/month, 1-credit specs only) → **PRO** (HIDDEN — grandfathered, new buyers can't pick) → **TEAM** ($39/seat/month BYOK, min 2 seats) → **ENTERPRISE** (custom, unlimited). The V143 `product_id` consumer/B2B surface split was prototyped then rolled back by V179 + V185 — all accounts on a single `executespec` surface; `TierProductSurfaceMapper` is dormant.

Each run costs 1–N credits proportional to estimated LLM cost: `credits = max(1, ceil(bufferedEstimate / $5.0))`. Charged at plan approval (not submission); refunded on FAILURE. Customer can voluntarily lower the ceiling via `RunRequest.userCostCap` (clamped against the platform cap). BYOK orgs (`keySourceMode=OWN_KEYS`) skip the platform cap entirely; HYBRID + SYSTEM stay capped.

### Auth + RBAC

JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub). Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) for event streams. Roles: SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER / PROJECT_VIEWER (read-only, cross-org `ProjectMember` from V175). External cross-org invites also gated by **V198** per-org flag `org.external_sharing_enabled` (default false; flip via `PUT /api/org/me/settings/external-sharing`; ORG_ADMIN-of-own-org or SUPER_ADMIN only). Force-logout via feature-flag epoch-based JWT invalidation. Solo users get a personal org auto-provisioned on registration (V162/V163; `org.is_personal=true`).

`AccessGuardCoverageTest` (`arch/`) pins every controller method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant access guard. Ten legacy sites remain in `KNOWN_LEGACY_DRIFT`; new violators fail the build.

### Security

8-layer defense-in-depth: request validation → input sanitization (`InputSanitizer` — NFKC + zero-width strip + base64 decode+scan) → LLM prompt defense → code abuse scanning (`CodeAbuseScanner` — crypto, shells, PowerShell, DNS exfil, cloud metadata, obfuscation) → shell sandbox (`ShellSandboxPolicy`) → file sandbox → process isolation → container hardening. Configurable headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`. Three guardrail levels (LOW / MEDIUM / HIGH) flip via `security.guardrail_level` flag.

Cleanroom containers (SWE-bench / ProgramBench) run with `--rm --network none -v <hostDir>:/workspace`. `ShellTool` + `BuildProjectTool` consult `ProgramBenchContainerStore` per invocation; when a containerId is set for the run, the tool dispatches via `docker exec` instead of host shell.

### Project workbench (`project/` + companions)

Per-project workspace at `workspace/projects/<projectId>/` (codemaps/, architecture.md, summary.md, docs/, .git) owned by `ProjectWorkspaceService` with atomic writes + Redis-backed lock. Disk is ephemeral on Cloud Run / GCE; DB blobs are authoritative.

3-step architecture pipeline (`codeintel/`): per-repo `CodeMapGenerator` (idempotent by SHA) → `RepoArchitectureGenerator` (per-repo HLD/LLD, PLANNING tier) → `ArchitectureDocGenerator` (cross-repo synthesis, PLANNING tier). `StalenessService` keeps the dashboard "STALE" badge honest via Redis-backed cache. `POST /api/projects/{id}/repos/{repoId}/architecture` and `GET /api/projects/{id}/architecture/diff?from=&to=` (LCS + GitHub compare API for source-file changes).

Always-on RAG: Tika `DocumentExtractor` + 500/50-token `ChunkingService` + Spring AI OpenAI `EmbeddingService` (text-embedding-3-small) + JDBC `ChunkStore` over pgvector. `ProjectDocumentService` + `/api/projects/{id}/documents` handle upload + async index (UPLOADED → EXTRACTING → EMBEDDING → READY/FAILED). `RetrievalService.retrieve(projectId, query, k, cap)` wired into the planner (`{{REFERENCE_DOCS}}`) and chat.

Project memory (`memory/`): every completed project-scoped run appends a digest to `project_run_ledger` via `RunLedgerAgent`; `MemoryCompactionService` rewrites a versioned `project_memory_snapshot` every 10 runs. `ProjectMemoryProvider.buildPlannerContext(projectId)` feeds `{{PROJECT_MEMORY}}` into the planner prompt.

Project chat (`memory/ProjectChatService`): compaction (summarise older N messages, keep last 6 verbatim) + reset + per-bubble copy + branch override on launch. Async-dispatched apply-suggestion through dedicated `applyExecutor` (4 threads) so the 25-iter worker loop doesn't burn a Tomcat handler thread.

Multi-repo PR creation: `ScmPushService.pushMultiRepo` handles per-subdir push + per-repo PR + cross-link via `GitHubRepoService.updatePullRequestBody`. PRs persisted to `pull_request_record` (V169 added `project_id` + `kind`; V170 added `ci_auto_fix_attempts`; V186 added `ci_status` columns). Webhook at `/webhooks/github` flips status (HMAC SHA-256 verified). `PullRequestSnapshot` carries `headSha` so PR refresh polls CI check-runs alongside merge state.

Tech debt (`project/techdebt/`): rolls up `validation_report.issues_json` into category / severity / file hotspots / dimension trend. Surfaced on the project Overview tab. The "Fix" button per category dispatches a patch-mode run via `TechDebtFixService` (autofix Phase 2).

### Models, routing, cost

YAML is the **single source of truth** for every model identifier / context-window / max-output-tokens / pricing rate / display label / fallback chain / free-tier lock / agent→tier map / provider-default model. `ModelRegistry` reads `application.yml model-matrix.*`. `ModelContextWindowMap` was deleted; `ModelController.MODEL_LABELS` reads `display-label` from YAML; frontend `PricingTable` reads `chainTier` via new DTO fields on `PricingRow`.

Agent tier mapping (`application.yml`):

| Tier        | Anthropic                   | OpenAI         | Gemini                   | Agents                                                                                                                                                                           |
| ----------- | --------------------------- | -------------- | ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PLANNING    | `claude-sonnet-4-6`         | `gpt-5.4`      | `gemini-3-pro-preview`   | OutlinePlannerAgent, ReviewerAgent, RepoArchitectureGenerator, ArchitectureDocGenerator, ProjectChatCompiler, PrReviewAgent, CodeReviewAgent                                     |
| WORKHORSE   | `claude-sonnet-4-6`         | `gpt-5.4-mini` | `gemini-3-flash-preview` | WorkerAgent, TaskExpanderAgent, TestPlannerAgent, TestWorkerAgent, ApiTestSuiteAgent, ProjectDocGeneratorService, ValidationReportGenerator, PromptOptimiserAgent                |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` | SmartClassifierAgent, CoherencePlannerAgent, ContextAgent, RunLedgerAgent, RunTitleSummarizer, ProjectChatCompactor, ProjectBriefService, ConversationAgent, TestReportGenerator |

Default provider: Anthropic (changed from OpenAI in `1.0.0-M1`). User-selectable per project. Per-provider implicit-cache enabled — Gemini Flash threshold 1024 tokens, Pro 4096; Anthropic explicit cache_control blocks. `RunCostTracker` records `cache_read_input_tokens` on every call for all 3 providers.

Reviewer routes Gemini to WORKHORSE tier (`gemini-3-flash-preview`) since `e9d0b6e8` (Pro at 4096-token implicit-cache threshold was hitting 0% cache; Flash at 1024 hits real cache). Anthropic + OpenAI reviewers stay PLANNING. **2026-05-16:** Gemini WORKHORSE itself moved Pro → Flash for the same reason — the WorkerAgent's per-turn system prompt + tool schemas drift across calls, missing every implicit-cache hit on Pro (verified empirically: 108k input tokens for a single LOW-complexity task, `cache_read_input_tokens=0`). Flash's 1024 threshold actually catches. PLANNING tier still uses Pro so the outline / coherence-planner / arch-doc keep deep-reasoning quality.

Provider circuit breaker via `ProviderHealthTracker` (Redis-backed, 5 failures → open 60s) on the legacy router; the V2 `LlmRouter` is direct-dispatch with per-provider failover via the YAML `failover-order:` chain.

### Observability

Micrometer + OTel → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP). `executespec.v2.*` custom metrics for `run.{outcome,latency}`, `planner.{latency,tasks}`, `task.{status,latency,smoketest}`, `worker.{iterations,outcome,toolcall}`, `llm.latency`, `reviewer.verdict`, `context.{save,save.bytes}`. Dump endpoint `/api/v2/test/metrics`. Actuator at `/actuator/prometheus`.

5 published SLOs in [`docs/operations/slos.md`](docs/operations/slos.md): run terminal in 15min ≥95% / verified-score correctness ≥90% / cost estimate within 30% on ≥80% / webhook in 60s ≥99% / hot-path 5xx <0.5%.

### Operator-only Telegram bridge

`chat_id=6475810501` + bot `8489535450`. Push: heartbeat every 600s + on every status-count change. Pull: `getUpdates` polled every 120s, writes to `/tmp/tg-inbox.log`. Used as out-of-band channel for long-running matrix tracking; not a customer-facing integration.

## Code Conventions

- **Base package:** `com.navneet.executespec`
- **Layering:** controller → service → agent → tool → `LlmRouter`. Legacy `FallbackLlmRouter` survives for non-tool-calling agents only.
- **DTOs:** Java records where possible. Lombok `@Data` / `@Builder` for JPA entities.
- **DI:** Constructor injection via Lombok `@RequiredArgsConstructor`. **No** `@Autowired` on fields.
- **LLM prompts:** external files in `src/main/resources/prompts/*.md` (and rollback archives `.txt`). Assembled via `PromptAssembler.assemble(path, vars)` — resolves `{{include:fragment}}` markers from `prompts/fragments/` and `{{VAR}}` substitutions. Never use `Resource.getContentAsString()` directly in agents.
- **Tool calling:** provider-native end-to-end. Strict JSON schemas on every entry in `tool/`. No XML `<tool_call>` parsing in V2.
- **Naming:** `*Agent.java` for agents, `*Service.java` for services, `*Controller.java` for REST, `*Spec.java` for security specifications.
- **DB migrations:** Flyway `V{n}__description.sql` in `src/main/resources/db/migration/` (head **V199**; immutable post-apply). When you add one, cross-link it from the matching package's `CLAUDE.md` migration log + the entity it touches.

### Patterns: MUST follow

- LLM calls go through `LlmRouter` (or `FallbackLlmRouter` for legacy non-tool agents). Never call providers directly.
- Run ownership fields (`createdByUserId`, `orgId`, `projectId`) stamped BEFORE async pipeline thread starts (race-condition fix).
- Gate-loss fallback: `approve()` → if gate missing → auto-fail with message.
- Sliding-window LLM context: first 2 + last 18 messages.
- Token-cost control: `readFile` 8k chars, `shell` 6k chars, `diff` 5k chars truncation limits.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21` before any Maven command on macOS dev.
- Payment-webhook signatures verified before processing (Razorpay HMAC, Stripe `Stripe-Signature`).
- Feature flags via `FeatureFlagService.isEnabled()` — never hardcode toggles.
- Structured logging with traceId / spanId via Micrometer context propagation (MDC auto-populated).
- Async work: wrap `Thread.ofVirtual().start()` runnables in `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + RequestAttributes survive thread boundaries.

### Patterns: MUST NOT use

- Field injection (`@Autowired` on fields).
- Raw String for LLM prompts (use prompt files).
- Direct HTTP calls to LLM providers.
- Swallowed exceptions (log + rethrow or handle explicitly).
- Setting run ownership fields AFTER `submitRun()` returns.

## Documentation hygiene (MANDATORY before every commit)

Standing rule from 2026-04-25: every commit must keep the project's documentation surface in lockstep with the code. **Before staging files for any commit, walk this checklist:**

1. **Root CLAUDE.md** if the change affects architecture, API contracts, file locations, agent tiers, status of in-flight work.
2. **Per-package `CLAUDE.md`** (e.g. `agent/CLAUDE.md`, `state/CLAUDE.md`) when entities, repository methods, agents, or migrations change in that package.
3. **`docs/CODEMAP.md`** when new top-level packages, entities, agents, or DB migrations are added.
4. **`docs/document_map.md`** when a doc is added or removed under `docs/`.
5. **`docs/plans/pending.md`** when an in-flight item changes status or a new pending plan is added.
6. **`README.md`** if a user-visible feature, environment variable, or build step changed. **`docs/design/*`** when tokens / theming / a11y commitments change.
7. **Migration trail** — every new `Vxxx__*.sql` must be cross-linked from the touching package's `CLAUDE.md` + the entity row.

For trivial typo fixes you may write `skip doc-refresh: trivial typo` in the commit message. Otherwise doc-refresh artefacts go in the same commit as the code, not a separate follow-up.

## Build & test

```bash
# IMPORTANT: macOS dev sets JAVA_HOME explicitly
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# Backend compile
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q

# Backend full verify (includes spotless format gate)
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q

# Frontend
npm --prefix frontend run build
npm --prefix frontend run format:check
npm --prefix frontend run lint

# Run locally
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run

# Local observability stack
cd observability && docker compose up -d
# Grafana http://localhost:3001 (admin/admin), Prometheus :9090, Tempo :3200

# V2 eval matrix
scripts/v2-eval.sh   # LOW/MEDIUM/HIGH × anthropic/openai/gemini, outputs scripts/v2-eval-results/
```

## Required environment variables

```bash
# Required — LLM
OPENAI_API_KEY=
ANTHROPIC_API_KEY=

# Optional — LLM
GOOGLE_API_KEY=                  # Gemini API key

# Optional — Integrations
GITHUB_PAT=
SLACK_CLIENT_ID= / SLACK_CLIENT_SECRET= / SLACK_SIGNING_SECRET=
DASHBOARD_URL=

# Optional — OAuth2 login
GOOGLE_OAUTH_CLIENT_ID= / GOOGLE_OAUTH_CLIENT_SECRET=
GITHUB_OAUTH_CLIENT_ID= / GITHUB_OAUTH_CLIENT_SECRET=

# Optional — Billing
RAZORPAY_KEY_ID= / RAZORPAY_KEY_SECRET=
STRIPE_SECRET_KEY= / STRIPE_WEBHOOK_SECRET= / STRIPE_PUBLISHABLE_KEY=
STRIPE_TEAM_MONTHLY_PRICE_ID= / STRIPE_ENTERPRISE_PRICE_ID=
BILLING_DEFAULT_GATEWAY=stripe   # prod default (dev defaults to razorpay)
BILLING_STRIPE_ENABLED=true

# Optional — Infrastructure
REDIS_HOST=                      # default localhost

# Optional — Observability
OTEL_SAMPLING_PROBABILITY=1.0
OTEL_METRICS_ENABLED=false
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318/v1/traces
```

## API contracts

### Run lifecycle

| Method | Path                                  | Description                                                                                                                                                       |
| ------ | ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/runs`                           | Submit run (202). Body includes `aiProvider`, `executionMode`, `runMode`, `tddMode`, optional `userCostCap`, optional `figmaUrl`, optional `selectedRepoIds[]`.   |
| GET    | `/api/runs`                           | List runs (paginated, filtered by visibility + tier).                                                                                                             |
| GET    | `/api/runs/{id}`                      | Run detail (incl. `githubPrUrl`, `verifiedScore`, `statusReason`, `planStrategy`, per-phase costs).                                                               |
| POST   | `/api/runs/{id}/approve`              | Plan gate approval; body `{approved, creditsConfirmed?}`. Charges credits.                                                                                        |
| POST   | `/api/runs/{id}/pause`                | PAUSED (V168, flag `v2.pause.enabled`, default OFF).                                                                                                              |
| POST   | `/api/runs/{id}/resume`               | Same-JVM only; workspace must exist.                                                                                                                              |
| DELETE | `/api/runs/{id}`                      | Cancel — `RunOrchestrator.cancel(runId)`. Signals plan gate, interrupts pipeline future, closes opened PRs.                                                       |
| POST   | `/api/runs/{id}/archive`              | Archive.                                                                                                                                                          |
| POST   | `/api/runs/{id}/tasks/{taskId}/retry` | Retry one failed task. Requires `requireBuildVerification=true` on retry attempt. Does NOT auto-push — use the `Update PR` button on `InlineRunDetail` if needed. |
| GET    | `/api/runs/{id}/events`               | SSE event stream (scoped token).                                                                                                                                  |
| GET    | `/api/runs/{id}/sse-token`            | Get scoped SSE token (5-min TTL).                                                                                                                                 |
| POST   | `/api/runs/{id}/run-tests`            | Manually trigger test execution (Beta).                                                                                                                           |
| GET    | `/api/runs/{id}/prs`                  | List PRs opened by this run with status.                                                                                                                          |
| GET    | `/api/runs/{id}/cost`                 | Live token + cost snapshot.                                                                                                                                       |
| POST   | `/api/runs/{id}/sync`                 | Reset workspace to current origin HEAD.                                                                                                                           |
| POST   | `/api/runs/{id}/sync/original`        | Reset workspace to the SHAs captured at run start (from `run_repo_snapshot`).                                                                                     |
| POST   | `/api/repos/{repoId}/webhook`         | Install pull_request webhook with caller's PAT.                                                                                                                   |

### Validation report + scoring

| Method | Path                          | Description                     |
| ------ | ----------------------------- | ------------------------------- |
| GET    | `/api/runs/{id}/report`       | Get validation report.          |
| GET    | `/api/runs/{id}/report/pdf`   | Download report as PDF.         |
| POST   | `/api/runs/{id}/report/share` | Generate share link.            |
| GET    | `/api/reports/shared/{token}` | Public shared report (no auth). |

### Preview + deploy

| Method | Path                           | Description                                                                            |
| ------ | ------------------------------ | -------------------------------------------------------------------------------------- |
| POST   | `/api/runs/{id}/preview/start` | Start preview (Cloud Run or Docker, 5-min TTL).                                        |
| DELETE | `/api/runs/{id}/preview/stop`  | Stop preview.                                                                          |
| POST   | `/api/deployments`             | Deploy to Cloud Run (permanent).                                                       |
| POST   | `/api/runs/{id}/push-github`   | Push to GitHub (single-repo; multi-repo branches into `ScmPushService.pushMultiRepo`). |

### Project workspace

| Method | Path                                                                         | Description                                                        |
| ------ | ---------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| GET    | `/api/projects/{id}/dashboard`                                               | Aggregated metrics, recent runs/PRs, arch-doc status.              |
| GET    | `/api/projects/{id}/memory` + `/memory/ledger`                               | Project memory snapshot + full ledger.                             |
| GET    | `/api/projects/{id}/scores`                                                  | Score-trend feed.                                                  |
| GET    | `/api/projects/{id}/workspace-health`                                        | Per-repo SHA drift + webhook + open-PR rollup.                     |
| GET    | `/api/projects/{id}/architecture` + `/versions` + `/diff?from=&to=`          | Latest arch / all versions / source-file changes between versions. |
| POST   | `/api/projects/{id}/architecture/generate`                                   | Regenerate codemaps + arch doc.                                    |
| GET    | `/api/projects/{id}/repos/{repoId}/architecture`                             | Per-repo HLD/LLD.                                                  |
| POST   | `/api/projects/{id}/docs/{EXPLANATION\|SETUP}/generate` + `GET .../download` | Generated Docs.                                                    |
| GET    | `/api/projects/{id}/tech-debt?since=<days>`                                  | Issues-by-category / severity / file hotspots.                     |
| POST   | `/api/projects/{id}/analyse?branch=<name>`                                   | Per-branch arch refresh using caller's PAT.                        |
| GET    | `/api/projects/{id}/estimate?requirement=<text>`                             | Heuristic p25/p75 task + cost estimate from project history.       |
| POST   | `/api/projects/{id}/prs/refresh`                                             | Poll GitHub + post-merge cascade + CI check-runs.                  |
| GET    | `/api/projects/{id}/templates` + POST                                        | Spec templates (project + org-scoped).                             |
| POST   | `/webhooks/github`                                                           | PR + check_run webhook (HMAC SHA-256).                             |

### PR Review + Code Review (V172)

| Method | Path                                                                                             | Description                                                                        |
| ------ | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| POST   | `/api/pr-reviews`                                                                                | Submit PR review (PRO+); body `{projectId, repoFullName, prNumber}`. 202 + QUEUED. |
| GET    | `/api/pr-reviews/{id}` + `/api/projects/{id}/pr-reviews` + by-PR query                           | Read endpoints.                                                                    |
| POST   | `/api/code-reviews`                                                                              | Submit whole-repo scan (PRO+).                                                     |
| GET    | `/api/code-reviews/{id}` + `/report` (markdown) + `/api/projects/{id}/code-reviews/latest?repo=` | Read endpoints.                                                                    |

### External project sharing (V175 + V198)

| Method | Path                                                                                  | Description                                                                                                                                                                                                                                |
| ------ | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POST   | `/api/projects/{id}/invites` + GET + DELETE                                           | Cross-org `ProjectMember` invite by email. **V198 (2026-05-16):** create now requires tier PRO+ AND ORG_ADMIN-of-own-org (or SUPER_ADMIN) AND `org.external_sharing_enabled=true`. PROJECT_ADMIN alone is no longer enough. |
| GET    | `/api/invites/preview?token=` + POST `/api/invites/accept?token=`                     | Accept invite (email-match required).                                                                                                                                                                                                       |
| POST   | `/api/projects/{id}/share-links` + GET + DELETE                                       | Public read-only share links (tier-gated). Unchanged by V198 — gates the cross-org `ProjectMember` path, not the public-link path.                                                                                                          |
| GET    | `/api/shared/{token}/{meta,run,validation-report,code-review,pr-review,architecture}` | Public read endpoints.                                                                                                                                                                                                                      |
| PUT    | `/api/org/me/settings/external-sharing`                                               | **V198** — toggle the per-org flag. Body `{enabled: bool}`. ORG_ADMIN-of-own-org or SUPER_ADMIN only. Emits `ORG_EXTERNAL_SHARING_CHANGED` audit row.                                                                                       |

### Auth + org

| Method | Path                                                                        | Description                             |
| ------ | --------------------------------------------------------------------------- | --------------------------------------- |
| POST   | `/api/auth/register` / `/login` / `/refresh` / `/logout`                    | JWT auth.                               |
| POST   | `/api/auth/forgot-password` / `/reset-password` / GET `/verify-email`       | Account recovery.                       |
| GET    | `/api/auth/oauth2/init?surface=<x>` / `/authorize` / `/callback/{provider}` | OAuth2 (Google + GitHub).               |
| GET    | `/api/auth/me/permissions`                                                  | Tier-based permissions.                 |
| GET    | `/api/auth/me/data-export`                                                  | GDPR ZIP (manifest + payload + README). |
| POST   | `/api/org` / `/api/org/invite`                                              | Org + invitations.                      |

### Billing

| Method | Path                                              | Description                                              |
| ------ | ------------------------------------------------- | -------------------------------------------------------- |
| GET    | `/api/billing/plans`                              | Yields YAML-driven pricing rows.                         |
| POST   | `/api/billing/subscribe`                          | Routed to Razorpay or Stripe via `PaymentGatewayRouter`. |
| POST   | `/api/billing/topup/purchase`                     | Buy run top-up credits.                                  |
| GET    | `/api/billing/payment-history`                    | Org-scoped payment history.                              |
| POST   | `/api/webhooks/razorpay` + `/api/webhooks/stripe` | Webhook events (HMAC verified, no auth).                 |

### Admin

| Method | Path                                                      | Description                                            |
| ------ | --------------------------------------------------------- | ------------------------------------------------------ |
| GET    | `/api/admin/payment-audit` + `/export`                    | Payment audit log + CSV (SUPER_ADMIN).                 |
| GET    | `/api/admin/webhook-events`                               | Webhook event log.                                     |
| GET    | `/api/admin/tiers` + PUT `/{tier}`                        | Tier lifecycle (LISTED/HIDDEN/SUNSETTING).             |
| POST   | `/api/admin/executions/reconcile`                         | Mark orphaned non-terminal ExecutionRuns FAILED.       |
| POST   | `/api/admin/swebench/run` + `/api/admin/programbench/run` | Bench-mode submission.                                 |
| POST   | `/api/admin/projects/{id}/migrate-codemaps`               | Migrate legacy codemaps into `.executespec/codemaps/`. |

### Enterprise readiness — `.executespec/` folder, contracts, standards, ADRs, activity

| Method              | Path                                                                                           | Description                                                          |
| ------------------- | ---------------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| GET                 | `/api/projects/{id}/activity`                                                                  | Project activity feed (partitioned-by-month).                        |
| POST                | `/api/projects/{id}/export`                                                                    | ZIP bundle of every `.executespec/` artefact + DB-only counterparts. |
| POST                | `/api/projects/{id}/check-drift` + per-repo + per-contract                                     | Cross-repo contract drift detection.                                 |
| POST                | `/api/projects/{id}/contracts/{contractId}/{refresh-from-source,dismiss-drift}`                | Drift workflow.                                                      |
| GET / POST / DELETE | `/api/projects/{id}/contracts/{contractId}/consumers/{...}`                                    | Consumer pinning.                                                    |
| GET + POST          | `/api/projects/{id}/repos/{repoId}/contract-coverage` + `/rescan`                              | Coverage rollup.                                                     |
| POST                | `/api/projects/{id}/standards/violations/{id}/override` + `/standards/{id}/exception` + DELETE | Standards override + standing exception (≥30-char rationale).        |
| GET                 | `/api/projects/{id}/standards/report?orgId=&sinceDays=`                                        | Standards compliance report.                                         |

### Notifications + flags

| Method | Path                                                                          | Description                       |
| ------ | ----------------------------------------------------------------------------- | --------------------------------- |
| GET    | `/api/notifications` + `/unread-count` + POST `/{id}/read` + POST `/read-all` | In-app notifications.             |
| GET    | `/api/models/available`                                                       | Model matrix (YAML-derived).      |
| GET    | `/api/feature-flags` + POST `/{key}`                                          | Feature flag admin (SUPER_ADMIN). |

## Key file locations

| Purpose                         | Path                                                                                                                                                                                                 |
| ------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Run orchestrator                | [`integration/RunOrchestrator.java`](src/main/java/com/navneet/executespec/integration/RunOrchestrator.java)                                                                                         |
| Sequential executor             | [`harness/SequentialExecutor.java`](src/main/java/com/navneet/executespec/harness/SequentialExecutor.java)                                                                                           |
| Worker agent                    | [`agent/WorkerAgent.java`](src/main/java/com/navneet/executespec/agent/WorkerAgent.java)                                                                                                             |
| Outline planner                 | [`agent/OutlinePlannerAgent.java`](src/main/java/com/navneet/executespec/agent/OutlinePlannerAgent.java)                                                                                             |
| Smart classifier                | [`agent/SmartClassifierAgent.java`](src/main/java/com/navneet/executespec/agent/SmartClassifierAgent.java)                                                                                           |
| Reviewer                        | [`reviewer/ReviewerAgent.java`](src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java)                                                                                                   |
| Patch-mode agent                | [`agent/PatchAgent.java`](src/main/java/com/navneet/executespec/agent/PatchAgent.java)                                                                                                               |
| LLM router (V2)                 | [`agent/LlmRouter.java`](src/main/java/com/navneet/executespec/agent/LlmRouter.java) + `OpenAiNativeToolClient` + `GeminiNativeToolClient`                                                           |
| Legacy router (non-tool agents) | [`agent/FallbackLlmRouter.java`](src/main/java/com/navneet/executespec/agent/FallbackLlmRouter.java)                                                                                                 |
| Tool registry                   | [`tool/ToolRegistry.java`](src/main/java/com/navneet/executespec/tool/ToolRegistry.java) + `tool/*` (11 tools + `CompleteTaskTool`)                                                                  |
| Context store                   | [`context/ContextStore.java`](src/main/java/com/navneet/executespec/context/ContextStore.java)                                                                                                       |
| Cost tracker                    | [`cost/RunCostTracker.java`](src/main/java/com/navneet/executespec/cost/RunCostTracker.java)                                                                                                         |
| State store                     | [`state/StateStore.java`](src/main/java/com/navneet/executespec/state/StateStore.java)                                                                                                               |
| Startup resumer                 | [`recovery/StartupRunResumer.java`](src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java)                                                                                           |
| Finding patch submitter         | [`finding/FindingPatchSubmitter.java`](src/main/java/com/navneet/executespec/finding/FindingPatchSubmitter.java)                                                                                     |
| Model registry                  | [`router/ModelRegistry.java`](src/main/java/com/navneet/executespec/router/ModelRegistry.java)                                                                                                       |
| Validation report               | [`report/ValidationReportService.java`](src/main/java/com/navneet/executespec/report/ValidationReportService.java) + `ValidationReportGenerator`                                                     |
| Project architecture            | [`codeintel/ProjectArchitectureService.java`](src/main/java/com/navneet/executespec/codeintel/ProjectArchitectureService.java)                                                                       |
| Project memory                  | [`memory/RunLedgerAgent.java`](src/main/java/com/navneet/executespec/memory/RunLedgerAgent.java) + `MemoryCompactionService` + `ProjectMemoryProvider`                                               |
| GitHub integration              | [`repo/GitHubRepoService.java`](src/main/java/com/navneet/executespec/repo/GitHubRepoService.java) + `BitbucketRepoService`                                                                          |
| Multi-repo push                 | [`integration/scm/ScmPushService.java`](src/main/java/com/navneet/executespec/integration/scm/ScmPushService.java)                                                                                   |
| SWE-bench adapter               | [`swebench/`](src/main/java/com/navneet/executespec/swebench/)                                                                                                                                       |
| ProgramBench adapter            | [`programbench/`](src/main/java/com/navneet/executespec/programbench/)                                                                                                                               |
| Security config                 | [`auth/security/SecurityConfig.java`](src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java)                                                                                       |
| Billing service                 | [`billing/service/BillingService.java`](src/main/java/com/navneet/executespec/billing/service/BillingService.java) + `gateway/PaymentGatewayRouter`                                                  |
| Feature flags                   | [`config/featureflag/FeatureFlagService.java`](src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagService.java)                                                                     |
| Rate limiting                   | [`ratelimit/RateLimitFilter.java`](src/main/java/com/navneet/executespec/ratelimit/RateLimitFilter.java)                                                                                             |
| Sharing                         | [`sharing/`](src/main/java/com/navneet/executespec/sharing/)                                                                                                                                         |
| PR Review agent                 | [`prreview/`](src/main/java/com/navneet/executespec/prreview/)                                                                                                                                       |
| Code Review agent               | [`codereview/`](src/main/java/com/navneet/executespec/codereview/)                                                                                                                                   |
| Activity feed                   | [`activity/`](src/main/java/com/navneet/executespec/activity/)                                                                                                                                       |
| Folder writer (`.executespec/`) | [`folder/ExecutespecFolderWriter.java`](src/main/java/com/navneet/executespec/folder/ExecutespecFolderWriter.java)                                                                                   |
| Standards / ADR / Invariants    | [`standards/`](src/main/java/com/navneet/executespec/standards/), [`adr/`](src/main/java/com/navneet/executespec/adr/), [`arch/invariants/`](src/main/java/com/navneet/executespec/arch/invariants/) |
| Cross-repo contracts            | [`contracts/`](src/main/java/com/navneet/executespec/contracts/)                                                                                                                                     |
| Notification preferences        | [`notification/pref/`](src/main/java/com/navneet/executespec/notification/pref/)                                                                                                                     |
| GDPR data export                | [`auth/service/UserDataExportService.java`](src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java)                                                                           |
| Prompt assembler                | [`agent/PromptAssembler.java`](src/main/java/com/navneet/executespec/agent/PromptAssembler.java) + `prompts/fragments/*.md` + `prompts/scaffolds/*.md`                                               |
| LLM prompts                     | `src/main/resources/prompts/*.md`                                                                                                                                                                    |
| App config                      | `src/main/resources/application.yml`                                                                                                                                                                 |
| DB migrations                   | `src/main/resources/db/migration/V*.sql` (head V199)                                                                                                                                                 |
| Frontend entry                  | `frontend/src/main.tsx`                                                                                                                                                                              |
| Frontend API client             | `frontend/src/lib/api.ts`                                                                                                                                                                            |
| Observability stack             | `observability/docker-compose.yml`                                                                                                                                                                   |
| V2 eval framework               | `scripts/v2-eval.sh` + `scripts/v2-eval-specs/{low,medium,high}.txt`                                                                                                                                 |

## Current state

### Working

- V2 BUILD-mode pipeline end-to-end: classify → outline → sequential per-task worker → reviewer → COMPLETE → auto-preview (5-min TTL) → background validation (report + test gen + test exec). Hierarchical planning (LARGE/LEGACY).
- V2 PATCH-mode pipeline: SWE-bench Lite + ProgramBench bench-mode + 4 customer-facing autofix surfaces (PR Review / TechDebt / CI failure webhook / Code Review severity buckets).
- Per-run codemap + 3-step project architecture pipeline + Generate Docs.
- Project memory + project chat with compaction.
- Always-on RAG over user docs via pgvector + Tika + OpenAI embeddings.
- External project sharing: cross-org `ProjectMember` invites + public read-only share links (V175). Per-org feature flag `org.external_sharing_enabled` (V198, default false; ORG_ADMIN/SUPER_ADMIN to toggle) gates the cross-org invite surface; share-links are independent.
- PR Review + Code Review agents with markdown report download (V172).
- Multi-repo runs: per-repo workspace + per-repo codemap + per-repo PR + cross-link.
- Tier lifecycle state machine + SUPER_ADMIN tier console.
- Cost-proportional run credits + voluntary `userCostCap` + BYOK cost-cap exemption + visible `CostProgressBar`.
- Multi-gateway billing (Razorpay + Stripe) with run top-up credits.
- Dual-mode previews: Cloud Run (dev/prod, 5-min TTL, managed HTTPS) or Docker Compose (local, Caddy + Cloudflare Origin Certificate).
- JWT auth + OAuth2 (Google, GitHub) with RBAC, org management, project scoping.
- 8-layer security + 3 guardrail levels.
- Containerized test execution (Docker `--read-only --cap-drop ALL` + memory/CPU limits + env-var whitelist).
- In-app notifications (run completion + quota warning + gate-opened events).
- Pause / resume on V2 runs (interrupt + resume-from-disk; flag `v2.pause.enabled`).
- GDPR data-export endpoint.
- `AccessGuardCoverageTest` ArchUnit pin + known-legacy ledger (10 sites remaining).
- Local Grafana dashboards + GCP Cloud Trace + Cloud Monitoring exporters (`@Profile("gcp")`).
- Model evaluation framework (`eval/`).

### In progress / not-yet-merged

- **`feature/full-project-review-2026-05-11`** — current branch; doc-refresh pass on top of the 2026-05-11 full-project review.
- **`feature/programbench-benchmark`** — V2/V3 unification pass (88+18 file moves, 25 class renames) + ProgramBench adapter + step-2 wire-in into V2 worker stack. Validated locally across 3 providers (OpenAI / Anthropic / Gemini) on a Python single-shot spec; pipeline healthy. PR open against dev.
- **`feature/swebench-adapter`** (PR #88, NOT merged) — SWE-bench adapter + patch-mode pipeline + 4 autofix surfaces + 14 reliability/cost commits (empty-patch kill-chain via Lever A+B, per-provider cost gap fixes, iter-cap calibration, $4→$8 default platform cap, BYOK exemption). Empirical: medium matrix 67% / hard matrix 60% across 3 providers.
- **`feature/hierarchical-planner`** + **`feature/enterprise-readiness-eval`** — earlier in-flight tracks; both functional on their branches; not merged to `dev`/`main`. Operational truth in `docs/plans/pending.md` (consolidated) and per-package `CLAUDE.md` files.
- Mid-task checkpoint replay + per-provider mid-LLM-call abort + cross-JVM workspace shipping via GCS — all scoped + bounded; today's pause is "interrupt + resume-from-disk" semantics. Tracked in [`docs/plans/pending.md`](docs/plans/pending.md) §1.3 Gap 2.
- V2 batch execution — scoped in [`docs/plans/pending.md`](docs/plans/pending.md) §3; not started.

### Known tech debt

- **Bug #1c (router strict-provider bypass)** — `ModelResolver.selectAdaptiveProvider` silently switches providers based on historical success rate even when `-Dexecutespec.strict-provider=true` is set. Fix deferred — needs either a strict-mode guard inside ModelResolver or opt-out flag per org.
- **Planner model sourced from run model, not tier** — `OutlinePlannerAgent` constructs the LLM request using `req.model()` which is the RUN-level `defaultModelFor(provider)` (→ haiku for anthropic), not `ModelResolver.resolve("OutlinePlannerAgent", provider).getModel()`. Swapping `anthropic: planning:` in YAML has no effect on the planner today. Fix: have the planner resolve its own tier model.
- **10 legacy guard sites** in `AccessGuardCoverageTest.KNOWN_LEGACY_DRIFT` — 3 repo-scoped in `ProjectRepoController`, 2 SUPER_ADMIN in `SuperAdminController` + 1 in `ExecutespecMigrationController`, 1 V2-canary in `RunController#poll`, 3 unrelated. Migration order spelled out in the GA-readiness P4.1-followup.
- **JAVADOC.md is stale** — last comprehensively refreshed at v2.4.0 (2026-04-14); subsequent ranges live in CLAUDE.md / ARCHITECTURE.md / per-package CLAUDE.md. Rewriting JAVADOC.md is on the queue but not blocking.
