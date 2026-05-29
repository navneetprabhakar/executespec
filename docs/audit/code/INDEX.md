# Code Audit — Index & Checkpoint Tracker

> Line-by-line read of the ExecuteSpec codebase, no-prior-bias. Source only.
> Started 2026-05-29. This file is the **resume anchor** — on any context reset, read this
> first to see what's done and what's next.

## Method

- Read every file in a package **in full** (Read tool, whole file), not excerpts.
- After each package (or logical group), write/append its checkpoint doc here in `docs/audit/code/<pkg>.md`.
- Update the status table below the moment a package flips state.
- Findings are bucketed per checkpoint: **Correctness/bugs · Stale-docs/drift · Accuracy/Efficiency/Cost (vs Codex/Claude Code) · Security · Tech-debt**.
- Severity tags in checkpoints: 🔴 bug/risk · 🟡 smell/debt · 🔵 accuracy/efficiency/cost lever · 📝 doc drift.
- **Coverage rule (learned 2026-05-29):** a package is DONE only when `grep -E '^- \[ \] src/main/java/com/navneet/executespec/<pkg>/' MANIFEST.md` is **empty** — zero unread under the *recursive* prefix, incl. subpackages (`entity/`, `model/`, `event/`, `repository/`). The initial top-level `wc *.java` glob missed subpackages (caught in `harness/` + `agent/model/`); mark `[x]` by **exact path** as read, never by broad prefix.

## Status legend

`TODO` not started · `PARTIAL` some files read · `DONE` every file read + checkpoint written.

## Scale

Backend: ~160,475 lines / 72 packages. Frontend: ~35,686 lines / 8 dirs. Total ≈ 196K lines.
This is a multi-turn effort; the per-package docs are the durable artifact.

**Per-file coverage ledger: [MANIFEST.md](MANIFEST.md)** — every one of the 2,062 readable files is `[x]` read / `[~]` partial / `[ ]` unread (+ 26 binary/lock excluded-with-reason). Verify anytime: `grep -c '\[ \]' MANIFEST.md`. End-state target = 0 unread.

---

## Tier 1 — Core codegen (the accuracy/efficiency path)

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `engine` | 12 | 1,696 | DONE | [engine.md](engine.md) | full-history worker loop (no compaction); engine seam unused in prod |
| `harness` | 15 | 4,175 | DONE | [harness.md](harness.md) | strong 3-layer plan gate + vendored-guard + design injection + post-merge events; V1 worktree/parallel residue |
| `agent` | 42 | 8,586 | DONE | [agent.md](agent.md) | no V2 failover; compaction machinery unwired; dual cost-estimators + dual classifiers; V2 BYOK no Redis |
| `tool` | 34 | 6,211 | DONE | [tool.md](tool.md) | strong V2 tools; 🔴 CodeAbuseScanner not on V2 write path; host-shell sandbox is a speed-bump; V1 worktree/lock legacy resident |
| `router` | 17 | 1,944 | DONE | [router.md](router.md) | resolution-time failover exists but V2 LlmRouter never feeds health/perf → breaker inert |
| `context` | 7 | 948 | DONE | [context.md](context.md) | worker snapshot + project-doc maintainer; package-info schema drift |
| `cost` | 5 | 1,286 | DONE | [cost.md](cost.md) | cache-aware pricing via Spring-AI reflection (fragile); k-NN matches coarsely |

## Tier 2 — Scoring / review / report (accuracy measurement)

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `reviewer` | 12 | 2,547 | DONE | [reviewer.md](reviewer.md) | strong anti-hallucination layers; 🔵 timed-out pass keeps burning tokens (worker has no interrupt check + reviewer omits wallClockSeconds) |
| `scoring` | 5 | 783 | DONE | [scoring.md](scoring.md) | honest-null dimensions; 🟡 **RunRubricRow/RunRubricVerifier = dead Phase-A** (V197 table never written); latent unsandboxed shell in verifyShell |
| `report` | 14 | 3,031 | DONE | [report.md](report.md) | verifiedScore home; 🟡 gaming-guard re-injects LLM endpoint false-low; 🟡 **rubricScore dead-write** (no FE ref); 🔵 usage-email cost at stale Haiku rates |
| `assessment` | 2 | 292 | DONE | [assessment.md](assessment.md) | 🟡 LLM path calls Anthropic directly — bypasses LlmRouter+RunCostTracker (cost-invisible) + field injection |
| `validator` | 27 | 2,000 | DONE | [validator.md](validator.md) | 🟡 **entire package DEAD** (0 prod callers) — superseded by smoketest/; still shells ~5× per boot; revival = per-file fast gate |
| `smoketest` | 6 | 820 | DONE | [smoketest.md](smoketest.md) | secure real-test exec (the pattern verifyShell should copy); 🔵 multi-repo gets no real tests; unknown stacks → free-green BUILD |

## Tier 3 — Orchestration / state

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `integration` | 42 | 10,411 | DONE | [integration.md](integration.md) | RunOrchestrator 5,300-LOC god class (cancel/pause token-leak, LARGE pool-starvation, synthesise→score); clean SCM push; 🟡 inbound webhook-auth inconsistent (Jira/PagerDuty fail-open) + null-attribution webhook runs + plaintext OAuth tokens |
| `state` | 37 | 4,676 | DONE | [state.md](state.md) | StateStore mutation chokepoint; 🟡 **dual V1/V2 run-lifecycle** (divergent pause/terminate); PipelineRun ~80-col god-entity; V1 coordination (Inbox/MergeConflict/FileLock) vestigial; admin analytics do findAll() scans; atomic token/status updates (good) |
| `execution` | 38 | 4,978 | DONE | [execution.md](execution.md) | project "Launch" lane wrapping V2 (unified-dispatch default + legacy per-Solution); 🔵 4h `Thread.sleep` poll-per-launch (event-driven listener already exists); 🟡 `@Version` CAS band-aids everywhere; 🟢 smart retry-gating + source-of-truth cost recompute; legacy path has scaffolded-not-finished features |
| `recovery` | 1 | 350 | DONE | [recovery.md](recovery.md) | StartupRunResumer — boot-time V2 crash recovery (salvage-if-done + replay, cap 3); 🟢 well-built; part of a 5-listener boot fleet |
| `saga` | 4 | 437 | DONE | [saga.md](saga.md) | solid transactional outbox (atomic claim, backoff); 🟡 most effect types are log-only stubs / unwired (COMPENSATE_CLOUD_RUN silently no-ops); 🟡 orphaned-PROCESSING entries never recover (no reaper) |
| `run` | 2 | 215 | DONE | [run.md](run.md) | RunSyncService (reset to branch/original SHA) + RunSkipPushFlag (Redis one-shot); clean |

## Tier 4 — Project intelligence

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `project` | 42 | 5,600 | **DONE** | [project.md](project.md) | exemplary tenant hygiene + incident-hardened; dashboard run/launch cost still heap-scans 2k rows |
| `codeintel` | 26 | 4,101 | **DONE** | [codeintel.md](codeintel.md) | cleanest pkg; cache-before-LLM everywhere; 1 cost-bypass (RunCodeMapService) |
| `memory` | 13 | 2,395 | **DONE** | [memory.md](memory.md) | solid resilience; chat uses raw org-match not the guard; prompt-via-ClassPathResource drift |
| `contracts` | 32 | 3,387 | **DONE** | [contracts.md](contracts.md) | strong build, gate wired flag-OFF; auto-detection NOT wired (only /rescan populates) |
| `standards` | 26 | 2,573 | **DONE** | [standards.md](standards.md) | strong build, wired flag-OFF; compliance report leaks cross-org standards via orgId param |
| `adr` | 5 | 553 | **DONE** | [adr.md](adr.md) | clean; ADR injection wired into planner; getAdr tool unwired |
| `arch` | 6 | 425 | **DONE** | [arch.md](arch.md) | runtime invariants (NOT ArchUnit pins); InvariantChecker built but has ZERO callers |

## Tier 5 — Platform

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `auth` | 78 | 11,960 | **DONE** | [auth.md](auth.md) | 🔴 public `/api/auth/generate` bcrypt oracle; SSRF in cred-validate; solid JWT/PAT/quota/RBAC core |
| `crypto` | 4 | 394 | **DONE** | [crypto.md](crypto.md) | AES-256-GCM JPA converter for DB creds; ⚠ silent plaintext passthrough if no key (default) |
| `billing` | 84 | 9,134 | **DONE** | [billing.md](billing.md) | well-built + secure (fail-closed webhooks, IDOR-guarded, GST invoicing); 3rd parallel quota system |
| `entitlements` | 2 | 209 | **DONE** | [entitlements.md](entitlements.md) | clean resolver; 3 parallel quota systems (v2 monthly / v3 lifetime / billing credits) coexist |
| `credit` | 1 | 141 | **DONE** | [entitlements.md](entitlements.md) | v3 lifetime free-credit arbiter; capped grant/refund |
| `pricing` | 3 | 72 | **DONE** | [entitlements.md](entitlements.md) | model_pricing = runtime cache seeded from YAML (not a dual source) |
| `security` | 7 | 1,141 | **DONE** | [security.md](security.md) | strong evasion-resistant scanners, but tightening is OFF at default LOW guardrail |
| `tenant` | 4 | 199 | **DONE** | [tenant.md](tenant.md) | 🔴 TenantInterceptor is write-stamp only — NO ORM read isolation; rests entirely on app guards |
| `ratelimit` | 3 | 482 | **DONE** | [ratelimit.md](ratelimit.md) | production-grade; fail-open at 50%; guardrail tightening OFF at default LOW |
| `sharing` | 9 | 1,408 | **DONE** | [sharing.md](sharing.md) | best isolation in codebase — two-sided IDOR guards on public-link path (mint + serve) |

## Tier 6 — Bench / eval / testgen

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `swebench` | 6 | 1,230 | **DONE** | [bench-eval-testgen.md](bench-eval-testgen.md) | SUPERSEDED oracle adapter (~800 LOC to delete); sandbox plumbing reused by benchmark/ |
| `programbench` | 5 | 904 | **DONE** | [bench-eval-testgen.md](bench-eval-testgen.md) | cleanroom `--network none` containers; real-pipeline blind |
| `benchmark` | 3 | 945 | **DONE** | [bench-eval-testgen.md](bench-eval-testgen.md) | real pipeline blind; miss=under-scoping multi-file (the accuracy lever) |
| `eval` | 20 | 4,487 | **DONE** | [bench-eval-testgen.md](bench-eval-testgen.md) | offline model-eval (routed/cost-tracked); SUPER_ADMIN-gated |
| `testgen` | 29 | 3,664 | **DONE** | [bench-eval-testgen.md](bench-eval-testgen.md) | parallel test pipeline; hardened-container execution; run-access-guarded |

## Tier 7 — Integrations / API / infra + long tail

| Package | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `deployment` | 79 | 17,053 | **DONE** | [deployment.md](deployment.md) | strongest sandboxing in system (env-strip, metadata-block, cap-drop); 3rd AES-GCM impl; 3.5K-LOC Dockerfile god-file |
| `api` | 47 | 5,570 | **DONE** | [api.md](api.md) | `RunController` is the access-guard model; 🟡 run mutations org-scoped not owner-scoped |
| `repo` | 5 | 2,037 | **DONE** | [tier7-infra.md](tier7-infra.md) | GitHub/Bitbucket via `ProcessBuilder(argv)` (no shell); PAT in authUrl (local-only) |
| `notification` | 10 | 937 | **DONE** | [tier7-infra.md](tier7-infra.md) | in-app + email run-completion + per-user prefs; clean |
| `sse` | 3 | 870 | **DONE** | [tier7-infra.md](tier7-infra.md) | run event stream + 24h Redis replay; scoped 5-min tokens |
| `observability` | 1 | 224 | **DONE** | [tier7-infra.md](tier7-infra.md) | Micrometer `executespec.v2.*` custom metrics; clean |
| `config` | 23 | 2,327 | **DONE** | [tier7-infra.md](tier7-infra.md) | FeatureFlagService (Redis-cached gate), SecretManagerConfigLoader; clean |
| `prreview` | 12 | 1,909 | **DONE** | [review-autofix.md](review-autofix.md) | paid PR-review agent + comment inbox; 🟡 autofix uses `assertCanRead` (PROJECT_VIEWER can push) |
| `codereview` | 10 | 2,378 | **DONE** | [review-autofix.md](review-autofix.md) | two-stage repo scan; 🟡 **SemgrepRunner ThreadLocal bug drops ALL static findings** (masked til semgrep ships) |
| `finding` | 3 | 240 | **DONE** | [review-autofix.md](review-autofix.md) | the single PATCH-autofix chokepoint (dedupe + tenant stamp); clean |
| `folder` | 7 | 1,270 | **DONE** | [chat-specchat-folder.md](chat-specchat-folder.md) | `.executespec/` writer (path-root guard, hash pre-flight); 📝 stale "system PAT fallback" comments |
| `chat` | 4 | 1,204 | **DONE** | [chat-specchat-folder.md](chat-specchat-folder.md) | read-only run copilot + write-path Apply; clean; dead `tryGitCommit` escape hatch |
| `specchat` | 4 | 709 | **DONE** | [chat-specchat-folder.md](chat-specchat-folder.md) | CLI chat = Phase-1 skeleton; 🟡 submit_run will bypass sanitize+quota when wired |
| `readymade` | 24 | 1,984 | **DONE** | [readymade-migration-test.md](readymade-migration-test.md) | solutions catalog; 🟡 instantiate/resolve lack project-ownership guard (IDOR) |
| `migration` | 24 | 2,004 | **DONE** | [readymade-migration-test.md](readymade-migration-test.md) | recipes + LLM-free dry-run; 🟡 apply/dry-run lack project-ownership guard (IDOR) |
| `activity` | 9 | 1,023 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | tenant-guarded partitioned feed; SQL-inject rail on DDL; admin-event RBAC advisory |
| `optimiser` | 5 | 558 | **DONE** | [optimiser-batch.md](optimiser-batch.md) | self-improving prompts; 🟡 default-ON but writes relative source path → dev-only-effective, prod-inert |
| `rollout` | 4 | 589 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | 🟡 retired canary `POST /api/v2/runs` — authenticated but UNMETERED full pipeline (no tier/quota/cost); delete or SUPER_ADMIN-gate |
| `compliance` | 3 | 739 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | GDPR/retention + disk/SCM cleanup crons; incident-hardened (137-orphan-dir fix); ShedLock'd |
| `anonrun` | 7 | 486 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | layered anti-abuse (cap→IP-rate-limit→heuristic→24h session); real dispatch stub-only (flag OFF) |
| `analytics` | 6 | 449 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | idempotent activation funnel; async; PostHog no-op when unconfigured; clean |
| `referral` | 4 | 438 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | grant on first-READY (anti-fraud), granted_at idempotency; 📝 recordInvite does findAll() user scan |
| `crypto` | 4 | 394 | **DONE** | [crypto.md](crypto.md) | (dup of Tier 5 row) AES-256-GCM JPA converter; silent plaintext passthrough default |
| `apitests` | 2 | 391 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | post-merge OpenAPI gen; 📝 Resource.getContentAsString MUST-NOT + dead hasFrontendFiles |
| `export` | 2 | 376 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | project tar.gz bundle; role+tenant defense-in-depth; no secrets in bundle |
| `lint` | 1 | 322 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | pre-reviewer formatter pass (token saver); strips secret env vars; clean |
| `landing` | 4 | 296 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | public anonymized marketing reads; markdown-stripped; uncached native queries |
| `composer` | 2 | 291 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | slot substitution + validator (FILE_PATH traversal guard, unknown-key reject); clean |
| `batch` | 8 | 291 | **DONE** | [optimiser-batch.md](optimiser-batch.md) | unbuilt P1 scaffold — every entry throws UnsupportedOperationException; 0 client beans |
| `onboarding` | 5 | 395 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | idempotent sandbox provisioning + hourly archive GC; clean |
| `contact` | 4 | 180 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | Jira-or-email feedback; validated DTO; config-sourced creds (no SSRF); clean |
| `governance` | 1 | 187 | **DONE** | [tier7-longtail.md](tier7-longtail.md) | cost-approval threshold + project spend cap + GH-owner allowlist; WIRED at approve; clean |
| `util` | 3 | 138 | **DONE** | [tier7-infra.md](tier7-infra.md) | HmacUtil (shared webhook-sig primitive), PoolSizing; clean |
| `test` | 9 | 1,760 | **DONE** | [readymade-migration-test.md](readymade-migration-test.md) | diagnostic harnesses; correctly fenced (@Profile local/dev + SUPER_ADMIN); not in prod |
| `ExecutespecApplication` | 1 | 60 | **DONE** | [readymade-migration-test.md](readymade-migration-test.md) | Spring Boot main; excludes Spring-AI auto-configs (graceful no-key boot); clean |

## Tier 8 — Frontend

| Dir | Files | Lines | Status | Checkpoint | Headline |
|---|--:|--:|---|---|---|
| `lib` | 14 | 5,487 | **DONE** | [frontend-core.md](frontend-core.md) | clean typed api.ts contract; ~8 component raw-fetches bypass it; monaco* trio dead |
| `stores` | 7 | 973 | **DONE** | [frontend-core.md](frontend-core.md) | thin Zustand slices; SSE log capped 500; legacy 'vibe'/MAX normalized |
| `hooks` | 11 | 812 | **DONE** | [frontend-core.md](frontend-core.md) | scoped-token SSE, double-tap-guard, client gating advisory (backend enforces) |
| `console` | 2 | 398 | **DONE** | [frontend-core.md](frontend-core.md) | flag-gated control-plane chrome + mission-control home; clean |
| `types` | 2 | 407 | **DONE** | [frontend-core.md](frontend-core.md) | project + execution DTO types; clean |
| `design-system` | 2 | 175 | **DONE** | [frontend-core.md](frontend-core.md) | WCAG contrast helpers + RUNNING regression test (corrects "0 tests" note) |
| `ide` | 18 | 15,695 | **DONE** | [frontend-core.md](frontend-core.md) | all read; 4 UI bugs confirmed (no-approve, no-bell, 2-views, raw-JSON log); raw-fetch bypasses + local ≥90 verdict drift |
| `signal` | 31 | 11,454 | **DONE** | [frontend-core.md](frontend-core.md) | ~3.6k LOC DEAD pages (ExtraPages 9/14, LegalPages.OrgSettings, MiscPages 3, AdminPage dup); fictional SOLO tier; canonical ≥85 verdict; Space-Grotesk @import vs JetBrains-Mono claim |

---

## Tier 9 — Non-backend-Java surfaces (added 2026-05-29 — were outside the original plan)

> Frontend (Tier 8) + everything below got a **delegated Explore-agent pass during the initial no-bias assessment** (second-hand, excerpt-level), but **NOT a line-by-line audit**.
>
> **Agreed plan (2026-05-29):** FULL line-by-line + per-file checkpoint on ALL of these too (no lighter pass), in **impact order**: finish Tier 1 (`tool`/`harness`) → Tier 2 (scoring/reviewer/report) → **frontend** → `clients/` → backend Tiers 3–7 → Tier-9 surfaces (tests, infra, website, scripts, migrations, remaining prompts).

| Surface | Files | Lines | Status | Notes |
|---|--:|--:|---|---|
| `clients/` (CLI + VS Code + core, Node/TS) | 22 | 2,220 | **DONE** | local-hands/remote-brain; PAT 0600, workspace-escape guard, CLI wires allowCommand confirm; reasoned-not-compiled (VERIFY.md), tool-proxy dormant |
| `src/test/` (backend tests, Java) | 172 | 28,895 | **DONE** | 🟡 **WorkerAgent/CodeGenEngine = ZERO unit tests**; scoring well-pinned (6 files); ArchUnit pins clean (empty allowlists); happy-path-only LLM contract fixtures |
| `src/main/resources/db/migration/` | 221 | — | **DONE** | V1→V221 clean/sequential/no anomalies; destructive ones idempotent; feature-flag seed map captured (V117 batch OFF = 50% cost) |
| `src/main/resources/prompts/` | 94 | — | **DONE** | all read; quality strong; planner.v1–v14 + compiler/conflict-resolver/task-compile-supervisor DEAD; scaffolds use legacy snake_case (V1/V3 lane) |
| `infra/` (Terraform) | 29 | — | **DONE** | private GCP topology (no public IP); Grafana admin:admin local-only; bucket asia-south1→us-east4 migration pending; CI has no FE/e2e gate |
| `observability/` | 7 | — | **DONE** | docker-compose + Grafana/Prom/Tempo; clean |
| `website/` (marketing) | ~18 | — | **DONE** | fictional SOLO $29 tier (= HIDDEN PRO); SEO files; no committed secrets |
| `scripts/` | ~40 | — | **DONE** | eval/bench harnesses + e2e; dev-wipe scoped SUPER_ADMIN+DEV_ENV_TAG; nothing destructive vs prod |
| `clients/`, root files (pom/README/etc.) | — | — | **DONE** | Spring Boot 3.5.14 / Spring AI 1.1.6, no CVEs; `.env.template` clean |
| `src/main/resources/static/` | — | — | SKIP | generated frontend build output |

**STATUS: COMPLETE.** All 2,061 tracked files read line-by-line ([MANIFEST.md](MANIFEST.md) = 0 unread).
Final prioritized synthesis: **[`../FINDINGS-2026-05-29.md`](../FINDINGS-2026-05-29.md)** (benchmark gap →
accuracy/efficiency/cost levers → P0/P1/P2 roadmap). Two bulk-agent claims verified false and discarded
(credentials.json gitignored; OPENAI→ANTHROPIC reroute already removed) — see §F of the report.

---

## Files already read in full (this audit, before per-package checkpoints existed)

- `engine/WorkerAgent.java` (1,241) · `harness/SequentialExecutor.java` (2,002) · `agent/LlmRouter.java` (486)
- `tool/{ToolOutputCompactor,EditFileTool,ReadFileTool,SearchFilesTool,BuildProjectTool}.java`
- `application.yml` model-matrix section (169–498) · `prompts/{patch-agent,outline-planner}.md`

## Cross-cutting findings log (deduplicated, severity-ranked)

Maintained as packages complete — see individual checkpoints for detail. Top items so far:

- 🔵 **No worker context compaction** — V2 worker keeps full history; `ToolOutputCompactor` OFF (`engine`/`tool`).
- 🔵 **No failover in V2 `LlmRouter`** — audit M#68; post-retry 5xx hard-fails a paid run (`agent`).
- 🔵 **Verification levers flag-OFF** — `build.test-exec-gate`, `worker.build-tdd` default OFF (`harness`).
- 📝 Doc drift fixed 2026-05-29: WORKHORSE=Sonnet (not Haiku), iter caps 40/75/120, migration head V221, readFile 5K.

### Tier 2 cross-cutting themes (added 2026-05-29)
- 🟡 **Abandoned "scoring-revamp" scaffolding in THREE places** — `scoring/RunRubricRow`+`RunRubricVerifier` (V197 table never written, verifier called only by its test), `report/rubricScore` (V194 column computed+persisted but **zero frontend reads** — Phase B never landed), and the entire `validator/` package (~2,000 LOC, 0 prod callers, superseded by `smoketest/`). Pattern: Phase A ships, Phase B/C abandoned, dead code + unused DB columns/tables left behind. Net deletable: ~2,500 LOC + 2 write paths (migrations immutable).
- 🔵 **LLM calls that bypass `RunCostTracker` (cost-accounting holes — directly relevant to the cost priority)** — `assessment/FeatureAssessmentService` calls the Anthropic bean directly (latent, flag-gated); usage-report emails re-estimate cost at stale blended-Haiku rates instead of summing the authoritative `pipeline_run.actual_cost_usd`. Two surfaces under-count real spend. (Audit the whole codebase for `*ChatModel.call(` + direct provider use in later tiers.)
- 🔵 **Scoring honesty is strong but has 2 live regressions** — the additive gaming-guard (A) re-injects the LLM read-budget endpoint false-low into CODE_DELIVERED without the reviewer-CLEAN/FIXED trust-gate the multiplicative path has (`report`); the dashboard `llmCoverageLevel` doesn't handle the GOOD/PARTIAL bands the accuracy prompt emits (`scoring`). Both modestly mis-score honest runs.
- 🔵 **Worker has no interrupt check (cross-ref `engine`)** — `WorkerAgent` never checks `isInterrupted()`; the reviewer's per-pass timeout `future.cancel(true)` can't actually stop a runaway pass, and the reviewer doesn't pass `wallClockSeconds`, so a timed-out review keeps burning charged tokens to completion. Likely also defeats `RunOrchestrator.cancel` (verify Tier 3).
- 🟡 **Convention violations to sweep** — direct `Resource.getContentAsString` (report generator) + `@Autowired` field injection (assessment) both contradict explicit CLAUDE.md MUST-NOTs; check prevalence in later tiers.

### Tier 3 cross-cutting themes (added 2026-05-29 — **Tier 3 COMPLETE**: integration/state/execution/recovery/saga/run all done)
- 🟡 **`RunOrchestrator` is THE backend god class (5,300 LOC, ~70 methods, ~40 deps)** — 6 `submitRun` + 6 `runPipeline` telescoping overloads, 4 near-duplicate orchestration tails. Highest-value refactor target. Confirms the cancel/pause **token-leak** (no worker interrupt — ties to engine/reviewer) and adds a LARGE-pipeline **pool-starvation** cliff (expansion futures submitted to the same pool the run blocks on).
- 🟡 **Stale/inconsistent fallback-provider defaults** — `"openai"` in `DEFAULT_PROVIDER`/`reReview`/`runClassifierShadow`/`providerForAccuracy` vs `"anthropic"` in crash-resume/retry; CLAUDE.md says Anthropic. Latent (API layer normally supplies provider — verify Tier 7).
- 🟡 **Inbound webhook auth is inconsistent + partly fail-open** — Jira (shared-secret-header) + PagerDuty (missing-header bypass) are weak; ServiceNow/Confluence/Bitbucket fail-closed HMAC. Webhook-triggered runs ALSO submit with **null user/org attribution** (E#36 resolver stubs) + PROTOTYPE+autoMerge. All gated behind opt-in `agent.integrations.enabled` (default OFF).
- 🟡 **Plaintext third-party credentials** — Slack bot tokens + Jira/Confluence API tokens + Bitbucket app passwords stored cleartext in DB (no `crypto/` wrapper). Check at-rest posture in Tier 5.
- 🟡 **Dead/unwired services keep appearing** — `integration/context/ReferenceExtractorService` (0 callers) joins the Tier-1/2 list (`validator/`, `RunRubricRow`, `rubricScore`). Recurring "built, never wired" pattern.
- 🟡 **Dual V1/V2 run-lifecycle (`state/`)** — `StateStore` keeps its own `markComplete`/`cancelRun`/`pauseRun`/`resumeRun` with semantics that DIVERGE from RunOrchestrator's V2 `terminate`/`cancel`/`pause` (e.g. StateStore.pauseRun marks tasks FAILED; RunOrchestrator.pause preserves them). Plus V1 parallel-worker coordination (`InboxService`/`MergeConflict`/`FileLock`/`AgentMessage`) is vestigial under sequential V2. `PipelineRun` is a ~80-col god-entity. Verify which lifecycle path each controller calls (wrong one corrupts state).
- 🔵 **Admin analytics do in-memory `findAll()` full-table scans (`StateStore`)** — model-comparison + per-user-stats load all runs/tasks into heap then filter; push filters into queries before the tables grow. (Plain metric counts correctly use repo aggregates; `sumActualCostBy*` accurate-cost queries exist — reinforces the usage-email cost-estimate fix.)
- 🔵 **Polling instead of events for cross-lane status (`execution/`)** — the V3 Launch lane mirrors V2 run status via 4h `Thread.sleep`-poll-per-launch threads, even though `IntegrationOutboundListener` already proves the event-driven (`PipelineEvent`) approach. Same poll pattern in the legacy per-Solution executor (2h). Recommend an event listener keyed by `pipelineRunId`.
- 🟢 **Strong reliability infra** — transactional outbox w/ atomic claim + backoff (`saga`), 5-listener boot crash-recovery fleet (`recovery`/`execution`/`state`), source-of-truth cost recompute + smart retry-gating (`execution`). Caveat: `saga` has **stub/unwired effect types** (COMPENSATE_CLOUD_RUN silently no-ops) + an orphaned-PROCESSING gap (no reaper).
- 🟡 **Recurring "scaffolded-not-finished / dead" pattern across Tier 3** — legacy `execution` per-Solution path (git-tag rollback, diff-codemap/test-anchor handover layers, incremental codemap all deferred-to-follow-up), `saga` stub effects, plus the Tier 1/2 dead code (`validator/`, RunRubricRow, rubricScore, ReferenceExtractorService). Lots of half-built features behind flags/deprecation.
- 🟢 Strong spots: per-phase cost attribution (RunOrchestrator), atomic token/status JPQL updates + `lockAndStart` (state), SCM push durability (orphan-branch saga, per-user PAT, fast-forward apply), inbound prompt-injection sanitization + idempotency, Slack OAuth CSRF state.

### Tier 5–7 cross-cutting themes (added 2026-05-29 — **ALL backend Java now read: 0 unread under `src/main/java/`**)
- 🔴/🟡 **`AccessGuardCoverageTest` has URL-pattern blind spots — the biggest systemic security theme.** The ArchUnit pin only inspects URLs containing `/runs/{`,`/projects/{`,`/executions/{`,`/launches/{`,`/repos/{`. Mutating surfaces with *other* URL shapes silently miss tenancy: (1) `rollout` `POST /api/v2/runs` — authenticated but **zero** tier/quota/cost/tenant guard, dispatches the real LLM pipeline unmetered (retired-canary dead code; delete or SUPER_ADMIN-gate); (2) `prreview` `POST /api/pr-reviews/{id}/autofix` — `assertCanRead` on a code-pushing action (should be `assertCanWrite`); (3) `readymade`/`migration` `instantiate`/`apply`/`dry-run`/`resolve` — **no guard at all** on a user-supplied `projectId` → cross-org run creation (victim-org billing attribution) + cross-org info disclosure. `ExecutionOrchestrator.createExecution` trusts its caller (no internal guard). **Fix the test's matcher set OR move to a method-level annotation guards must carry.** Contrast: `RunController` + `project/` + `codereview` + `export` + `activity` all guard correctly.
- 🔴 **`auth/AuthController` `GET /api/auth/generate` is a public bcrypt-hash oracle** (permitAll fallthrough) + `setRefreshCookie` hardcodes `secure=false` + SSRF in `validateExternalConnection`. ([auth.md](auth.md)) Highest-severity single endpoint found.
- 🟡 **Real defect: `codereview/SemgrepRunner` ThreadLocal capture bug** — stdout/stderr `set()` on worker threads, `get()` on the calling thread → **all semgrep findings silently dropped** whenever semgrep is present. Masked today (semgrep not installed in prod) → will silently defeat the static half of the paid code-review feature the moment the planned semgrep-in-prod work lands. ~4-line fix. ([review-autofix.md](review-autofix.md))
- 🔴 **`tenant/TenantInterceptor` is write-stamp only — NO ORM read isolation.** Tenant isolation rests *entirely* on the app-layer guards above — which makes the guard blind-spots more serious (there is no second line of defence). ([tenant.md](tenant.md))
- 🟡 **Plaintext credentials persist at rest** — `auth/AppUser.github_pat` (user + per-run) is the cleartext outlier (most other creds use `@Convert(EncryptedValueConverter)`); plus Slack/Jira/Confluence/Bitbucket tokens (Tier 3). `crypto/SimpleKeyEncryptionService` silently passthrough-stores plaintext when no key + `require-encryption=false` (the default). ([auth.md](auth.md), [crypto.md](crypto.md))
- 🟡 **3 independent AES-256-GCM impls** (`security/KeyEncryptor` Redis, `crypto/SimpleKeyEncryptionService` DB cols, `deployment/credential/CredentialEncryptionService` deploy creds) — all correct, consolidation candidate. ([deployment.md](deployment.md))
- 🟡 **3+ parallel quota/credit systems coexist** — `TierEnforcementService` (v2 monthly), `credit/LifetimeCreditService` (v3 lifetime), `billing` usage credits, + per-run cost-credit charge. ([entitlements.md](entitlements.md), [billing.md](billing.md))
- 🟡 **Guardrail tightening is OFF at the default LOW level** — `security/` evasion-resistant scanners + `ratelimit` are strong but tighten only at MEDIUM/HIGH; default LOW + fail-open. ([security.md](security.md), [ratelimit.md](ratelimit.md))
- 🟡 **Dormant/scaffolded/superseded subsystems (the dominant tech-debt shape, now ~10 instances)** — `rollout` (retired canary, still wired), `batch` (throws-everywhere P1 scaffold, 0 client beans), `optimiser` (default-ON but writes a relative source path → dev-only-effective, prod-inert), `specchat` (Phase-1 skeleton, `ProjectChatActionTools` built-not-wired + a latent sanitize/quota bypass in `submit_run` for when it lands), `swebench/SwebenchTaskService` (superseded oracle), `validator/`, `arch/InvariantChecker`, contracts/standards auto-detection (flag-OFF). Net deletable/dormant: several thousand LOC.
- 🔵 **`findAll()-then-filter-in-heap` recurs** — `state` admin analytics (hot-ish), `project` dashboard run/launch halves (2K+2K rows, hot path), `referral.recordInvite` (per-invite user-table scan), `compliance/StaleScmCleanupJob` ×2 (bounded nightly cron). Push `SUM`/filters to the DB; dashboard is the one that matters.
- 📝 **Convention-violation prevalence confirmed** — `Resource.getContentAsString` direct use (report generator + `apitests/ApiTestSuiteAgent`), stale provider defaults ("OPENAI"/"openai") scattered but latent (caller supplies provider). `@Autowired` field injection rare (assessment, RunCodeMapService).
- 🟢 **Strong infra is the norm in the long tail** — env-var-stripping before subprocesses (`deployment`, `lint`), incident-hardened cleanup crons (`compliance`), `GovernanceGuard` cost-approval/spend-cap/owner-allowlist WIRED at approve, partition-pruned tenant-guarded `activity` feed, layered anon-trial anti-abuse, `SafeYamlLoader` catalog seeders, correct Spring self-invocation `@Transactional` handling. The codebase's *engineering* quality is high; the gaps are concentrated in (a) the access-guard test's blind spots and (b) the pile of half-built flag-gated subsystems.
