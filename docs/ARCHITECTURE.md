# ExecuteSpec — Architecture

> **Version:** 1.0.0-M1 — refreshed 2026-05-12.
> **Audience:** technical leads, staff engineers, platform team.
> **Companion docs:** [`CODEMAP.md`](CODEMAP.md) (structural index), [`document_map.md`](document_map.md) (which doc to touch when), root [`CLAUDE.md`](../CLAUDE.md) (operational snapshot).
>
> **Versioning convention (since the 2026-05-07 unification pass):** no `v2.*` / `v3.*` markers in Java paths or class names. Everything is flat under `com.navneet.executespec.*`. Still versioned (out of scope): Flyway migration filenames (immutable post-apply), prompt rollback archives, frontend bundle artefacts.

## 1. System context

ExecuteSpec is a spec-driven AI coding platform. A customer:

1. Connects one or more GitHub / Bitbucket repos to a project.
2. Submits a natural-language spec.
3. ExecuteSpec classifies, plans, codes, builds + tests, and opens a PR with a deterministic verified score.
4. Human approves at the **plan gate** (PRODUCTION); PROTOTYPE skips it.

```
Customer (web app)             ExecuteSpec backend                 LLM providers
   │                                  │                                 │
   │  POST /api/runs                  │                                 │
   ├──────────────────────────────────► RunOrchestrator                 │
   │                                  │ ─── classify, plan, code, review ─►
   │  SSE  /api/runs/{id}/events      │                                 │
   ◄──────────────────────────────────┤ ◄──── plan + tool calls ────────┤
   │                                  │                                 │
   │  POST /api/runs/{id}/approve     │      buildProject smoke         │
   ├──────────────────────────────────►                                  │
   │                                  │     git push → PR on GitHub     │
   │                                  ├────────────────────────────────►
   │                                  │     auto-preview on Cloud Run   │
   │                                  ├────────────────────────────────►
```

Single GCE VM (us-east4) runs the Spring Boot app (prod + dev share the VM). Cloud Run hosts on-demand previews (5-min TTL). Cloud SQL Postgres + Cloud Memorystore Redis are managed. Cloudflare in front of `*.executespec.ai`.

## 2. Infrastructure

| Component | Purpose |
|-----------|---------|
| GCE VM | Single Spring Boot JVM serves both `executespec.ai` (prod) and `dev.executespec.ai` (dev). Java 21, 4 vCPU / 16 GB target. |
| Cloud SQL Postgres | All persistent state — users, orgs, runs, agent_tasks, payment_audit, activity_feed (partitioned), validation_report, pull_request_record, project memory, contracts, standards, ADRs, invariants. Highest active migration: **V186**. |
| Memorystore Redis 7 | Feature flag cache + Shedlock + rate-limit windows + gate coordination + `ContextStore` worker snapshots (24h TTL) + `ProviderHealthTracker` circuit breaker + `ProgramBenchContainerStore` runId→containerId map + `StalenessService` cache. |
| Cloud Run | On-demand previews (`PreviewService` mode=`cloud-run`, 5-min TTL, managed HTTPS, dev/prod). Local dev uses Docker Compose mode behind Caddy + Cloudflare Origin Certificate. |
| Artifact Registry | Docker images for previews + permanent deployments. |
| GCS | Workspace archive on terminal (zip + upload). |
| Cloudflare | WAF + CDN + TLS for `*.executespec.ai`. |

**Local dev:** Postgres + Redis on `localhost`. Frontend served from `src/main/resources/static/` (single bundled JAR) or directly from Vite at `:5173`.

## 3. V2 pipeline

The only execution path. V1 was deleted in 2026-04-13's deprecation P1-P6.

```
┌───────────────────────────────────────────────────────────────────┐
│ RunOrchestrator                                                    │
│                                                                    │
│   ① SmartClassifierAgent (LIGHTWEIGHT)                             │
│       heuristic fast path (≤150-char tiny specs) | LLM verifier   │
│       output: PlanStrategy ∈ {SINGLE_SHOT, SMALL, MEDIUM,         │
│                               LARGE, LEGACY}                       │
│                                                                    │
│   ② strategy router                                                │
│       SINGLE_SHOT  → synthesizeSingleShot (no LLM, 1-task plan)   │
│       SMALL/MEDIUM → OutlinePlannerAgent FULL_PLAN (32k cap)      │
│       LARGE        → SKELETON_ONLY outline →                      │
│                      parallel TaskExpanderAgent (WORKHORSE) →     │
│                      CoherencePlannerAgent (LIGHTWEIGHT, patchPlan)│
│       LEGACY       → LARGE pipeline + recursive depth-2 EPIC      │
│                                                                    │
│   ③ plan gate                                                      │
│       PRODUCTION pauses at AWAITING_APPROVAL until human approves │
│       PROTOTYPE / skipApproval bypasses                            │
│                                                                    │
│   ④ SequentialExecutor (one task at a time, tier-routed)          │
│       LOW    → LIGHTWEIGHT  cap 30 iters / 4k tokens              │
│       MEDIUM → WORKHORSE    cap 50 iters / 8k tokens              │
│       HIGH   → PLANNING     cap 75 iters / 16k tokens             │
│       PATCH  → per-provider Anthropic 150 / OpenAI 100 / Gemini 120│
│       → WorkerAgent multi-turn (native tool calling via LlmRouter)│
│       → buildProject smoke (final task only, multi-task plans)    │
│       → ONE retry with build error injected on smoke fail         │
│       → resilience levers A (force-edit watchdog at 0.7×maxIter)  │
│                          B (best-guess salvage on iter-limit /    │
│                             budget-exceeded / stagnation-force)   │
│                                                                    │
│   ⑤ ReviewerAgent (PLANNING)                                       │
│       general pass + project-type-aware wiring pass               │
│       Gemini routes to WORKHORSE tier (Flash @ 1024 cache thresh) │
│                                                                    │
│   ⑥ AccuracyAssessmentService                                      │
│       LLM read-budget judge (10k char/file, 100k char ceiling)    │
│                                                                    │
│   ⑦ ValidationReportService                                        │
│       verifiedScore = clamp(0, 100, baseAccuracy × buildOK ×      │
│                              testRatio × reviewerMultiplier −     │
│                              criticalPenalty)                      │
│                                                                    │
│   ⑧ background: TestGenOrchestrator + auto-preview + GitHub PR    │
└───────────────────────────────────────────────────────────────────┘
```

### Run modes

| Mode | Purpose | Pipeline shape |
|------|---------|----------------|
| `BUILD` (default) | New feature / spec-driven work | Full pipeline above |
| `PATCH` | Failing-test-driven fix; powers the 4 autofix surfaces + SWE-bench / ProgramBench | Single `PatchAgent` loop. Skips classifier / planner / reviewer. Test-verification gate on `completeTask` (`failed:[]`, `errored:[]`, `exit_code:0` within last 5 iters). |

### Worker outcomes

| Outcome | Meaning |
|---------|---------|
| `COMPLETED` | Worker called `completeTask` cleanly. |
| `COMPLETED_BY_STAGNATION` | 12-iter non-write watchdog force-terminated; falls through to soft-completion gate with Lever B salvage. |
| `STOPPED_WITHOUT_COMPLETE` | Worker hit terminal without calling `completeTask` (rare). |
| `ITERATION_LIMIT_EXCEEDED` | Per-complexity cap hit. |
| `BUDGET_EXCEEDED` | Hard-cap dollar ceiling hit. Lever B salvage fires when 0 writes happened. |
| `ERROR` | Router or tool error. |

### Status reasons

`pipeline_run.status_reason` disambiguates PARTIAL terminals: `CLEAN_COMPLETION` / `CLEAN_WITH_INTERMEDIATE_WARNINGS` / `FINAL_BUILD_FAILED` / `TESTS_FAILED:N_OF_M` / `REVIEWER_UNRESOLVED` / `REVIEWER_FAILED` / `ALL_TASKS_FAILED` / `LEGACY_UNKNOWN`. UI surfaces this as a `StatusBadge` tooltip + `SignalBreakdown` chip.

### Four customer-facing autofix surfaces (V183)

All routed through one chokepoint `finding/FindingPatchSubmitter`. Submitter dedupes per finding key, dispatches with `runMode=PATCH` + `source="finding:<TYPE>"`, and `runPatchPipeline` auto-pushes via `scmPushService.push()` on terminal:

| Phase | Surface | Dispatch UX |
|-------|---------|-------------|
| 1 | `prreview/PrReviewAutofixService` | PR Review verdict popover → "Autofix N comments" → patch pushes to PR head branch via `branchOverride` |
| 2 | `project/techdebt/TechDebtFixService` | TechDebtPanel category rows → "Fix" per category → fresh PR per category |
| 3 | `project/pr/CiFailureAutoFixService` | GitHub `check_run` webhook auto-fires on PR CI failure (flag `ci.autofix.enabled`, default OFF; max 3 attempts/PR) |
| 4 | `codereview/CodeReviewAutofixService` | CodeQualityPanel severity chips → "N CRITICAL" / "N MAJOR" → one PR per severity |

Phase 3 stays OFF in prod until SWE-bench Lite resolved% justifies the unattended-bot path.

## 4. Data architecture

Postgres is the system of record. Redis is cache + ephemeral coordination + worker snapshots. Local VM disk is the workspace (ephemeral; archived to GCS on terminal).

### Key tables

| Table | Purpose |
|-------|---------|
| `app_user`, `org`, `project`, `project_repo`, `project_repo_link`, `project_member` (V175 cross-org), `project_share_link` (V175 public) | Identity + scope. |
| `pipeline_run`, `agent_task` | Run lifecycle. Recent additions: `run_mode` (V182), `triggered_by_finding` (V183), `last_llm_failure` (V184 widen to TEXT), `user_cost_cap_usd` (V178), `status_reason` (V107), `plan_strategy` + `plan_outline_json` + per-phase cost columns (V130), pause/resume columns (V168). |
| `validation_report` | Per-run report with `verified_score`, `accuracy_score`, `reviewer_verdict`, `tests_passed/total`, `final_build_passed`, `issues_json`. |
| `pull_request_record` | Multi-repo PR registry. V169 added `project_id` + `kind`; V170 added `ci_auto_fix_attempts`; V186 added `ci_status` columns. |
| `code_review_scan` + `code_review_issue` + `pr_review` + `pr_review_comment` (V172) | Code Review + PR Review agent persistence. |
| `tier_config`, `billing_plan`, `billing_plan_limit`, `billing_subscription`, `invoice`, `payment_audit`, `run_cost_log` | Billing. |
| `feature_flag` | DB-backed + Redis pub/sub invalidation. |
| `activity_feed` (V133, partitioned by month) | Customer-facing project activity. |
| `activity_audit` (V22, V147 widen, V151 widen) | Compliance audit chain (separate from `activity_feed`, dual-written via `ActivityEventEmitter`). |
| `org_standard` + `project_standard_override` + `standards_violation` (V136), `adr` (V137), `arch_invariant` + `arch_invariant_violation` (V138) | Standards / ADR / invariants — DB cache; customer git is source of truth. |
| `contract_registry` + `contract_version` + `contract_consumer` + `contract_drift_event` (V135) | Cross-repo contract registry. |
| `user_notification_pref` (V139) | Per-user × event-type × channel matrix. |
| `project_run_ledger` + `project_memory_snapshot` (V88) | Project memory. |
| `project_chat_message` (V93-V94) + `project_document` + `project_document_chunk` (V95 pgvector) | Chat + RAG corpus. |
| `run_repo_snapshot` (V100) | Repo SHAs captured at run start for "reset to run state". |
| `execution_run` + `execution_solution` + `execution_solution_repo` + `execution_context_handover` (V89, V112 @Version) | Execution View (multi-repo, multi-solution). |

Migration head: **V186**. Full per-migration index in [`CODEMAP.md`](CODEMAP.md) §Migrations.

### Workspace layout

```
workspace/
├── <runId>/                     # per-run working dir
│   ├── repos/                   # multi-repo runs
│   │   ├── <repo-short>/        # cloned per linked repo
│   │   └── ...
│   ├── .git/ + .gitignore + .gitattributes
│   └── (worker-written files)
├── projects/<projectId>/        # per-project workspace (codemaps + arch + docs)
│   ├── codemaps/
│   ├── architecture.md
│   ├── summary.md
│   ├── docs/                    # generated EXPLANATION + SETUP
│   └── .git/
```

Cleanroom adapters (SWE-bench / ProgramBench) bind `<runId>/` at `/workspace` in a `--rm --network none` container. `ShellTool` + `BuildProjectTool` consult `ProgramBenchContainerStore` per invocation; when a containerId is set for the run, the tool dispatches via `docker exec` instead of host shell.

## 5. Security

8-layer defense-in-depth for running untrusted user input through LLM agents:

| Layer | Component | Role |
|-------|-----------|------|
| 1 | Jakarta Bean Validation | Reject malformed input at controller boundary. |
| 2 | `InputSanitizer` | NFKC normalization + zero-width strip + base64 decode-and-scan + 26 regex patterns + obfuscation markers. |
| 3 | LLM prompt defense | User input tagged `untrusted="true"` in planner / worker prompts; tool results wrapped to prevent second-order injection from repo files. |
| 4 | `CodeAbuseScanner` | Blocks writeFile/editFile of files matching malware patterns (crypto miners, reverse shells, exfil, cloud metadata, obfuscated payloads). |
| 5 | `ShellSandboxPolicy` | Subshell blocking + 23-entry blocklist + 43 pattern blocklist + chained-command splitting + 32-prefix allowlist + chmod allowed inside workspace, blocked against `/etc /usr /var /root /boot /sbin /bin /opt /sys /proc /dev /run`. |
| 6 | `FileSandboxPolicy` | Path normalization + workspace boundary enforcement (`safeResolve()`). |
| 7 | `ProcessExecutor` | Secret env-var stripping + 5-min hard timeout + Redis-backed per-tenant rate limiting. |
| 8 | Container hardening | `.dockerignore` excludes `.env*` / `credentials.json` / `secrets.*`; cleanroom containers run `--rm --network none --read-only --cap-drop ALL` with memory/CPU limits + env-var whitelist. |

Three guardrail levels (LOW / MEDIUM / HIGH) flippable via `security.guardrail_level` feature flag tighten layers 2/4/5/6/7 in unison. Configurable headers (CSP, HSTS, X-Frame-Options) via `SecurityProperties`.

### Auth + RBAC

- JWT (access 1h, refresh 7d) + OAuth2 (Google, GitHub).
- Scoped SSE tokens (5-min TTL, `scope=sse` + `runId` claim) — `JwtUtil.issueSseToken`.
- Force-logout via feature-flag epoch JWT invalidation.
- JWT secret validated at startup in prod (`JwtSecretValidator`).
- Personal org auto-provisioned on registration (V162/V163 + `OrgService.bootstrapPersonalOrg`).
- **7 roles:** SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER / PROJECT_VIEWER (read-only, V175 cross-org).
- **`AccessGuardCoverageTest`** (ArchUnit) pins every controller method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant access guard. 10 legacy sites in `KNOWN_LEGACY_DRIFT`; new violators fail the build.

## 6. Deployment + preview

| Mode | Used by | Lifecycle |
|------|---------|-----------|
| Cloud Run (preview) | Dev + prod auto-preview on terminal | 5-min TTL, managed HTTPS, scale-to-zero. `PreviewService.handoffToPreviewEngine` distinguishes "Docker daemon not reachable" from real preview-engine bugs (concise WARN vs ERROR + stack). |
| Docker Compose (preview) | Local dev | 5-min TTL, Caddy proxy with Cloudflare Origin Certificate. |
| Cloud Run (permanent) | `POST /api/deployments` | Permanent deploys; tier-limited by `tier_config.max_deployments`. Gated by `agent.deployment.enabled=true` + `RunAccessGuard.requireRunAccess`. |

Dockerfile generation:
- **Preview:** `DevDockerfileGenerator` reads `.agent-context/project.json` (planner-declared `project_type`).
- **Permanent:** `DockerfileGenerator` strategy pattern per project type, same `.agent-context/project.json`.

Scaffold-aware: `prompts/scaffolds/*.md` + `BuildCommandResolver` + `ShellSandboxPolicy` verb families per supported project type (Spring Boot, Node backend, Node frontend, Python, Go, Rust, React Native + Expo). Planner sees the scaffold via `{{SCAFFOLD_TEMPLATE}}`.

## 7. Billing

Multi-gateway via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST, audit trail with CSV export.

**Cost-proportional run credits** — `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval (not submission). Refunded on FAILURE.

**Customer cost cap** (V178) — `RunRequest.userCostCap` (optional, $0.10–$1000). `RunOrchestrator.persistEstimate` clamps `hard_cap_usd = min(platformCap, userCostCap)`. BYOK orgs (`keySourceMode=OWN_KEYS`) skip the platform cap entirely (customer pays their own API bill); HYBRID + SYSTEM stay capped.

**Visible cost-cap progress bar** — `CostProgressBar` on every run's Credits panel (green 0-80%, amber 80-100%, red ≥100%, aria-progressbar). For ALL users, not gated.

**Tier lifecycle state machine** — LISTED / HIDDEN / SUNSETTING per `tier_config` row. SUNSETTING gateway side-effects (cancel-at-period-end on Stripe + Razorpay) gated by feature flag `billing.lifecycle.sunsetting.enabled` (default OFF).

### Pricing model (live)

| Tier | Monthly | Credits | LLM keys | Notes |
|------|---------|---------|----------|-------|
| FREE | $0 | 5 runs / 1-credit specs only | Managed | Personal org auto-provisioned |
| PRO | $20 | 20 / month | Managed | **HIDDEN** — grandfathered, new buyers cannot select |
| TEAM | $39/seat (min 2) | Unlimited (BYOK) | BYOK | Per-seat shared pool, multi-member |
| ENTERPRISE | Custom | Unlimited | BYOK / SYSTEM | Contract |

YAML-driven via `application.yml model-matrix.model-pricing[]`.

## 8. Concurrency model

| Concern | Approach |
|---------|----------|
| Run dispatch | `RunOrchestrator.pool` — bounded `ThreadPoolExecutor` with `CallerRunsPolicy` backpressure. Size = `PoolSizing.coreMultiple(1, 4, 16)`. |
| Per-task worker | Sequential within a run. Per-task tier routing. |
| Plan-gate wait | Redis-backed, 500ms polling, 2h TTL, 24h max wait timeout. |
| Multi-repo expansion | `CompletableFuture.allOf` on shared 4-thread pool, 20-min wall timeout for LARGE/LEGACY. |
| Apply-suggestion | Dedicated `applyExecutor` (4 threads, `@PreDestroy` shutdown). `spring.mvc.async.request-timeout: 900000`. |
| Code review / PR review dispatch | `coreMultiple(2/4, 8/12, cores×1/2)` per service. |
| OSIV | `spring.jpa.open-in-view: false`. Hibernate `connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`. Hikari leak threshold 90s. |
| @Async @Version conflicts | `ExecutionRunCasUpdater.casUpdate(runId, mutator)` chokepoint — refetch + apply + retry up to 3 attempts with 50ms × attempt back-off. |
| Async work | Wrap `Thread.ofVirtual().start()` in `ContextSnapshotFactory.captureAll().wrap(runnable)` so traceId + RequestAttributes survive thread boundaries. |
| Scheduled jobs | All `@Scheduled` methods carry `@SchedulerLock` (ShedLock + Redis). Verified by `AsyncAllowlistTest`. |

## 9. Observability

- **Micrometer + OTel** → Prometheus + Tempo + Grafana (local) / Cloud Trace + Cloud Monitoring (GCP, `@Profile("gcp")` exporters).
- **Custom V2 metrics** (`executespec.v2.*`) — `run.{outcome,latency}`, `planner.{latency,tasks}`, `task.{status,latency,smoketest}`, `worker.{iterations,outcome,toolcall}`, `llm.latency`, `reviewer.verdict`, `context.{save,save.bytes}`.
- **Dump endpoint** `/api/v2/test/metrics`. **Prometheus scrape** at `/actuator/prometheus`.
- **Structured logs** with traceId / spanId via Micrometer context propagation (MDC auto-populated).
- **5 SLOs** published in [`operations/slos.md`](operations/slos.md): run terminal in 15min ≥95% / verified-score correctness ≥90% / cost estimate within 30% on ≥80% / webhook in 60s ≥99% / hot-path 5xx <0.5%.

## 10. LLM routing + models

YAML is the **single source of truth** (`application.yml model-matrix.*`). `ModelRegistry` reads every model identifier / context-window / max-output-tokens / pricing rate / display label / fallback chain / agent→tier map / provider-default model.

| Tier | Anthropic | OpenAI | Gemini |
|------|-----------|--------|--------|
| PLANNING | `claude-sonnet-4-6` | `gpt-5.4` | `gemini-3-pro-preview` |
| WORKHORSE | `claude-sonnet-4-6` | `gpt-5.4-mini` | `gemini-3-pro-preview` |
| LIGHTWEIGHT | `claude-haiku-4-5-20251001` | `gpt-5.4-mini` | `gemini-3-flash-preview` |

Default provider: **Anthropic** (changed from OpenAI in 1.0.0-M1). User-selectable per project.

`LlmRouter` (V2 — native tool calling) routes through:
- Anthropic via Spring AI (explicit `cache_control` blocks).
- OpenAI via `OpenAiNativeToolClient` (bypasses Spring AI 1.1.4's `extra_body` bug; auto-cache validated at 43%).
- Gemini via `GeminiNativeToolClient` (schema sanitization + thought-signature round-trip; implicit cache prefix preserved).

`FallbackLlmRouter` (legacy V1 surface) is still used by non-tool-calling agents (`AccuracyAssessmentService`, `ConversationAgent`, `testgen/*`). Provider circuit breaker via `ProviderHealthTracker` (Redis-backed, 5 failures → open 60s).

Reviewer routes Gemini to WORKHORSE tier (`gemini-3-flash-preview`) since `e9d0b6e8` — Pro at 4096-token implicit-cache threshold was hitting 0% cache; Flash at 1024 hits real cache. Anthropic + OpenAI reviewers stay PLANNING.

## 11. Project workbench

| Capability | Where it lives |
|------------|----------------|
| Per-project workspace at `workspace/projects/<projectId>/` | `project/workspace/ProjectWorkspaceService` with atomic writes + Redis-backed lock. Disk ephemeral on Cloud Run / GCE; DB blobs authoritative. |
| 3-step architecture pipeline | `codeintel/`: per-repo `CodeMapGenerator` → `RepoArchitectureGenerator` (PLANNING) → `ArchitectureDocGenerator` (PLANNING). Idempotent by SHA. Versioned. Lazy-refreshed via `StalenessService`. |
| Generate Docs (EXPLANATION + SETUP) | `codeintel/ProjectDocGeneratorService` (WORKHORSE). On-demand via `POST /api/projects/{id}/docs/{TYPE}/generate`. |
| Architecture diff | `codeintel/ArchitectureDiffService` — LCS line diff + GitHub `compareCommits` API for per-repo source-file changes between two arch versions. |
| RAG | `project/rag/*` — Tika `DocumentExtractor` + 500/50-token `ChunkingService` + OpenAI `text-embedding-3-small` + pgvector `ChunkStore`. `RetrievalService.retrieve(projectId, query, k, cap)` wired into planner (`{{REFERENCE_DOCS}}`) + chat. |
| Project memory | `memory/`: `RunLedgerAgent` (LIGHTWEIGHT) appends per-run digest; `MemoryCompactionService` rewrites versioned `project_memory_snapshot` every 10 runs; `ProjectMemoryProvider.buildPlannerContext` feeds `{{PROJECT_MEMORY}}`. |
| Project chat | `memory/ProjectChatService`: compaction (`ProjectChatCompactor` LIGHTWEIGHT) + reset + per-bubble copy + branch override on launch. Async-dispatched apply-suggestion through `applyExecutor`. |
| Tech debt | `project/techdebt/TechDebtService` rolls up `validation_report.issues_json` into category / severity / file hotspots / dimension trend. `TechDebtFixService` dispatches per-category fix as a patch-mode run. |
| Workspace health | `project/workspace/WorkspaceHealthService` — per-repo SHA drift + webhook + open-PR rollup. Origin SHA cached in Redis 10 min. |
| Multi-repo push | `integration/scm/ScmPushService.pushMultiRepo` — per-subdir push + per-repo PR + cross-link via `GitHubRepoService.updatePullRequestBody`. |
| GitHub PR refresh | `PullRequestSnapshot.headSha` powers CI check-run polling alongside merge state (V186). |
| Per-branch arch refresh | `POST /api/projects/{id}/analyse?branch=<name>` threads caller PAT for private-repo fetches. |

## 12. `.executespec/` folder + customer git surface

Every persistent artefact ExecuteSpec generates can land in customer git under `.executespec/`. Schema V131–V139 introduced the folder write contract; V134 added the per-project `config_storage` opt-out for confidential projects.

| Stripe | Path | Owner package |
|--------|------|---------------|
| Codemaps | `.executespec/codemaps/<repo>.json` | `codeintel/` via `folder/` |
| Architecture | `.executespec/architecture.md` + `.executespec/repos/<repo>/architecture.md` | `codeintel/` via `folder/` |
| Contracts | `.executespec/contracts/<contract>.{yaml,json,proto,graphql}` (producer) + `.executespec/contracts/consumed/<contract>.json` (consumer) | `contracts/` |
| ADRs | `.executespec/arch/adrs/<NNNN>-<slug>.md` | `adr/` (DB-cached frontmatter) |
| Invariants | `.executespec/arch/invariants.yaml` | `arch/invariants/` (DB-cached check results) |
| Standards | (resolved server-side; org_standard + project_standard_override) | `standards/` |
| Bootstrap README | `.executespec/README.md` (auto-generated) | `folder/FolderReadmeGenerator` |

Write chokepoint: `folder/ExecutespecFolderWriter#writeFiles`. Caller allowlist pinned by `arch/ExecutespecFolderWriterCallerAllowlistTest` (4 approved callers). Per-(project, repo) `prModeEnum()` selects PR (default — opens setup branch + PR for review) or DIRECT_PUSH (opt-in for trusted high-frequency writes like codemap regen). 4-state status machine on `project_repo`: `MISSING → BOOTSTRAP_PENDING → PR_PENDING → READY`.

## 13. PR Review + Code Review agents (V172)

| Agent | Tier | Trigger | Persistence |
|-------|------|---------|-------------|
| `PrReviewAgent` | PLANNING | `POST /api/pr-reviews` (PRO+) | `pr_review` + `pr_review_comment` |
| `CodeReviewAgent` | PLANNING | `POST /api/code-reviews` (PRO+) | `code_review_scan` + `code_review_issue` + `report_markdown` |

PR Review is single-call (one PR diff → verdict + line-anchored comments). Code Review is two-stage: Stage 1 `SemgrepRunner` runs `semgrep --config p/ci` (graceful degradation when semgrep missing); Stage 2 LLM triages KEEP / REWRITE / DROP per finding + adds architectural / security issues. `CodeReviewMarkdownRenderer` builds a single markdown report (severity-grouped, file-anchored, suggested-fix blocks).

Both async with SSE on `pr-review-{id}` / `code-review-{scanId}`. Per-tier monthly quotas: PRO=10 PR reviews / 4 code reviews; TEAM/ENTERPRISE unlimited.

## 14. External project sharing (V175)

Two surfaces:

- **Cross-org `ProjectMember` invites** — paid-tier admins invite by email; on accept the invitee gets a `is_external=true` member row + the new `PROJECT_VIEWER` role (read-only).
- **Public read-only share links** — `project_share_link` table with `Surface` enum (`PROJECT_OVERVIEW` / `RUN_DETAIL` / `CODE_REVIEW` / `PR_REVIEW` / `VALIDATION_REPORT` / `ARCHITECTURE_DOC`). Token in URL is the bearer. Tier-gated by `tier_config.share_link_active_cap` (FREE=3, PRO=25, paid=unlimited).

`ProjectAccessGuard` consults `project_member` after the org-match check, opening cross-org collaborators. 8 new endpoints under `/api/projects/{id}/invites`, `/api/projects/{id}/share-links`, `/api/invites/...`, `/api/shared/{token}/...` (last group whitelisted in `SecurityConfig`).

## 15. Bench-mode adapters

Two SUPER_ADMIN-gated adapters submit cleanroom benchmarks through the V2 PATCH pipeline.

| Adapter | Endpoint | Benchmark |
|---------|----------|-----------|
| `swebench/` | `POST /api/admin/swebench/run` | SWE-bench Lite (real GitHub issues with FAIL_TO_PASS pytest specs) |
| `programbench/` | `POST /api/admin/programbench/run` | Meta ProgramBench (rebuild-a-binary cleanroom, 201 tasks) |

Both run with `--rm --network none -v <hostDir>:/workspace`. `ProgramBenchContainerStore` (Redis) maps runId → containerId for the shell + build tool dispatch.

PATCH pipeline structural fixes (from the 2026-05-08 SWE-bench eval):

1. **Layer 1 — failing-test injection.** `SwebenchTaskService` runs FAIL_TO_PASS pytest in container at `onRunStarted` and prepends verbatim output to `userRequest` as ground truth.
2. **Layer 2 — `RunTestsTool`** (new tool, patch-mode only). Routes via container store same as ShellTool. Parses pytest envelope `{summary, passed, failed:[{test_id, message}], errored, raw_tail}`.
3. **Layer 3 — `completeTask` gate.** `WorkerTaskRequest.requireTestVerification` flag; `PatchAgent` sets it true. `WorkerAgent` loop tracks `lastTestRunIter` and refuses `completeTask` unless test envelope shows `failed:[]`, `errored:[]`, `exit_code:0` within `TEST_VERIFICATION_RECENCY_ITERS=5`.

Empirical results from 2026-05-08 (2 matrices, ~$45 spend): medium 67% (10/15), hard 60% (9/15). `pylint-dev__astroid-1268` resolved (first time ExecuteSpec scored on a public SWE benchmark).

## 16. Frontend architecture

React 18 + Vite 5 + Tailwind 3.4 + TypeScript 5.3. Two coexisting chromes share Zustand stores:

| Chrome | Path prefix | Purpose |
|--------|-------------|---------|
| **V2 sidebar shell** (`AppLayout`) | `/runs`, `/admin/*`, `/audit`, `/invoices`, `/accounting`, `/exec`, `/org/*`, `/projects/:id/dashboard`, `/projects/:id/executions/*`, `/welcome`, `/deployments`, `/legacy/billing` | Long-form workbench surfaces (admin, billing, project dashboards). |
| **V3 top-bar shell** (`Shell`) | `/projects/:slug`, `/projects/:slug/runs/:runShort`, `/projects/empty`, `/insights`, `/billing`, `/pricing`, `/settings`, `/integrations`, `/team`, `/trust`, `/library`, `/migrations`, `/exec-demo`, `/home`, `/claim` | Short-form conversation + landing surfaces. |

Both share `useAuthStore` / `useUiStore` (theme) / `useRunStore` / `useProjectStore`. V3 adds `useV3ProjectStore`. UI tokens unified via `frontend/src/tokens.css` + `tailwind.config.ts` (see [`design/ui-tokens.md`](design/ui-tokens.md)). Theme toggle (Sun/Moon) in V2 Header.

Single API client at `frontend/src/lib/api.ts`. Backend `@*Mapping` ↔ frontend `api.ts` URL alignment enforced by `arch/ApiContractTest` (ArchUnit + bytecode scan).

Lint gates via `frontend/.prettierrc.json` + `.eslintrc.cjs`; CI runs `npm run format:check` + `npm run lint`. Documented deviations in [`design/code-style.md`](design/code-style.md).

A11y: `frontend/.pa11yci.json` (public surfaces); `frontend/src/design-system/contrast.{ts,test.ts}` — dormant vitest-shape WCAG regression test pinning every (token, surface) ratio.

## 17. Operations + telemetry

- **Local stack** — `observability/docker-compose.yml` (Grafana :3001 admin/admin · Prometheus :9090 · Tempo :3200). Dashboards: ExecuteSpec Overview + JVM/HTTP.
- **Operator-only Telegram bridge** — chat_id 6475810501 + bot 8489535450. Push heartbeat every 600s + on every status-count change. Pull `getUpdates` every 120s → `/tmp/tg-inbox.log`. Used as out-of-band channel for long-running matrix tracking; not a customer-facing integration.
- **5 SLOs** in [`operations/slos.md`](operations/slos.md).

## 18. Build + CI

| Tool | Purpose |
|------|---------|
| Maven | Backend build. `mvn verify` runs `spotless:check` + tests. |
| spotless-maven-plugin 2.43.0 + googleJavaFormat 1.22.0 | Java format gate (GOOGLE profile, reflowLongStrings). Local fix: `mvn spotless:apply`. |
| Prettier 3.3 + ESLint 8.57 + @typescript-eslint 7.18 + react + react-hooks plugins | Frontend format + lint. |
| `.github/workflows/lint.yml` | Runs `mvn spotless:check` + `npm run format:check` + `npm run lint` on every PR to `dev` / `main`. ~2 min. |
| `.github/workflows/deploy.yml` | Runs `mvn verify` + frontend build + GCE deploy + health check on push to `dev` / `main`. |

Test layout:
- Unit tests live alongside source.
- ArchUnit tests under `src/test/java/com/navneet/executespec/arch/` — `AccessGuardCoverageTest`, `ApiContractTest`, `ExecutespecFolderWriterCallerAllowlistTest`, `AsyncAllowlistTest`, `FailoverOrderEnumAlignmentTest`.
- Integration-only tests tagged `@integration` (run via `mvn test -Pintegration`).
- E2E + chaos excluded from the unit suite via surefire excludes.

## 19. Key design decisions

| Decision | Rationale |
|----------|-----------|
| Sequential over parallel within a run | Determinism + simpler resume + lower coordination cost. Parallel would compound the LLM's coherence problem. |
| Native tool calling over XML | Provider-native is strict-schema'd, cheaper to parse, and resists tool-call drift. XML parser path (V1) was the source of many regressions. |
| Ground truth at run end via `buildProject` smoke | Per-task compile would amortise badly across multi-task plans + add minutes per task. Final-task smoke + reviewer's second build is enough signal. |
| Upfront context (workspace tree + planner-declared dependencies) over search-first | LLMs are bad at search-first under iteration budgets. Pre-loading what they need works better. |
| Provider-agnostic at the agent layer | Quirks (OpenAI `extra_body`, Gemini thought-signatures, Anthropic cache blocks) live in `*NativeToolClient` wrappers; agents never see them. |
| Redis-persisted worker context every 5 iterations | Sweet spot — ≤1 task's LLM work lost on crash, 0 on graceful deploy. Per-tool-call would 5-10× the Redis write rate without recovery gain. |
| YAML model matrix as single source of truth | One `ModelRegistry` read covers identifier / context-window / pricing / display-label / fallback. Frontend reads via `PricingRow` DTO. No hardcoded model strings in app code. |
| Two coexisting frontend chromes (V2 sidebar + V3 top-bar) | Strangler pattern. V3 is the long-term home for conversational surfaces; V2 owns the workbench. Both share stores so users don't lose state crossing chromes. |
| `.executespec/` in customer git as persistence surface | Customers own their data; arch / contracts / standards / ADRs / invariants survive ExecuteSpec churn. DB cache for query speed; customer git is the source of truth. |
| FindingPatchSubmitter chokepoint for the 4 autofix surfaces | One dedupe path, one cost-budget path, one push path. Adding a 5th surface tomorrow is a one-class change. |

## 20. Known issues + mitigations

| Issue | Mitigation |
|-------|------------|
| `ModelResolver.selectAdaptiveProvider` bypasses strict-provider mode | Tracked as Bug #1c. Fix deferred — needs either a strict-mode guard inside ModelResolver or opt-out flag per org. |
| Planner LLM model is sourced from run model, not tier | Tracked. `OutlinePlannerAgent` uses `req.model()` (run-level haiku) instead of resolving its own tier model. Swapping YAML `anthropic: planning:` has no effect today. |
| `JAVADOC.md` is stale at v2.4.0 | Current state lives in `CLAUDE.md` + per-package `CLAUDE.md` + this doc. JAVADOC.md is on the rewrite queue but not blocking. |
| 10 legacy access-guard sites in `KNOWN_LEGACY_DRIFT` | Allowlisted with rationale. `AccessGuardCoverageTest` fails the build on new violators. |
| JpaRepository N+1 risks on multi-collection entities | `@EntityGraph` applied per-finder. `ExecutionRunRepository.findListByProjectId` joins `solutions` only; `solutions.repoMappings` loads lazily per-solution under OSIV during DTO mapping (avoiding MultipleBagFetchException). |
| Hikari connection-leak risk on long async writes | `connection.handling_mode: DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` returns the connection at tx commit; `spring.jpa.open-in-view: false`; leak threshold raised to 90s. |

## 21. Reference index

- **Pipeline entry:** `integration/RunOrchestrator.java`
- **Classify → plan:** `agent/SmartClassifierAgent.java`, `agent/OutlinePlannerAgent.java`, `agent/TaskExpanderAgent.java`, `agent/CoherencePlannerAgent.java`, `agent/PlanStrategyCircuitBreaker.java`
- **Execute:** `harness/SequentialExecutor.java`, `agent/WorkerAgent.java`, `agent/PatchAgent.java`
- **Review:** `reviewer/ReviewerAgent.java`, `reviewer/AccuracyAssessmentService.java`
- **Scoring:** `report/ValidationReportService.java`, `scoring/ReviewScoringService.java`
- **Resume:** `recovery/StartupRunResumer.java`
- **Autofix surfaces:** `finding/FindingPatchSubmitter.java`, `prreview/PrReviewAutofixService.java`, `project/techdebt/TechDebtFixService.java`, `project/pr/CiFailureAutoFixService.java`, `codereview/CodeReviewAutofixService.java`
- **Models + cost:** `router/ModelRegistry.java`, `cost/RunCostTracker.java`
- **Multi-repo + PR:** `integration/scm/ScmPushService.java`, `repo/GitHubRepoService.java`, `project/pr/PullRequestRecordService.java`
- **Project workbench:** `codeintel/ProjectArchitectureService.java`, `memory/ProjectMemoryProvider.java`, `project/rag/RetrievalService.java`
- **Sharing:** `sharing/ProjectShareLinkService.java`, `sharing/ProjectMemberService.java`
- **Bench adapters:** `swebench/SwebenchTaskService.java`, `programbench/ProgramBenchTaskService.java`
- **Customer git surface:** `folder/ExecutespecFolderWriter.java`, `folder/BootstrapService.java`, `contracts/`, `standards/`, `adr/`, `arch/invariants/`
- **Security:** `auth/security/SecurityConfig.java`, `security/InputSanitizer.java`, `security/CodeAbuseScanner.java`, `tool/ShellSandboxPolicy.java`
- **Auth:** `auth/security/JwtUtil.java`, `auth/service/EntitlementResolver.java`, `auth/service/TierEnforcementService.java`, `auth/service/UserDataExportService.java`
- **Billing:** `billing/service/BillingService.java`, `billing/gateway/PaymentGatewayRouter.java`, `billing/lifecycle/TierLifecycleService.java`
- **Observability:** `observability/Metrics.java`, `config/ObservabilityConfig.java`

For the per-package companion, see [`CODEMAP.md`](CODEMAP.md).
