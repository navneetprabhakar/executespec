# Changelog

All notable changes to ExecuteSpec are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

The pre-1.0 development history (the v2.5.x line that ran from 2026-04-04 through 2026-05-01) is preserved in git log + commit messages; this changelog starts at the first numbered milestone release.

---

## [Unreleased] — work on `dev` since 1.0.0-M1 (range 2026-05-02 → 2026-05-12)

Twenty-one additive migrations landed on `dev` (V166 → V186) plus a multi-track feature push around the V2 patch-mode pipeline, four customer-facing autofix surfaces, and the structural fixes that came out of the 2026-05-08 SWE-bench Lite eval.

### Added — patch mode + SWE-bench adapter (`RunMode.PATCH`, V182, V183)

- **Patch-mode pipeline** (`agent/PatchAgent`) — second pipeline shape on V2. Single thin-agent loop, 120-iter cap (per-provider tuned: Anthropic 150 / OpenAI 100 / Gemini 120), forced PLANNING-tier model. Skips classifier / planner / executor / reviewer; runs against a failing-test spec rather than a natural-language one. BUILD-mode (the default) is unchanged.
- **Three structural layers** on patch mode: (1) failing-test injection — `SwebenchTaskService` runs FAIL_TO_PASS pytest in container at `onRunStarted` and prepends verbatim output to `userRequest`; (2) `RunTestsTool` (new) parses pytest output into `{summary, passed, failed, errored, raw_tail}`; (3) `completeTask` test-verification gate — refuses completion unless the test envelope shows `failed:[]`, `errored:[]`, `exit_code:0` within `TEST_VERIFICATION_RECENCY_ITERS=5`.
- **SWE-bench adapter** (`swebench/` package) — submits SWE-bench Lite instances as PipelineRuns, captures `git diff HEAD` from the cleanroom container, emits `preds.json` for the official harness. SUPER_ADMIN-gated `POST /api/admin/swebench/run`.
- **ProgramBench adapter** (`programbench/` package, V181 backbone) — same shape for the Meta ProgramBench cleanroom benchmark. `POST /api/admin/programbench/run`.
- **Empirical results** (2 matrices × 5 instances × 3 providers, ~$45 spend): medium matrix 67% (10/15), hard matrix 60% (9/15). `pylint-dev__astroid-1268` resolved by the new pipeline (first time ExecuteSpec scored on a public SWE benchmark).
- **Worker resilience fixes** — Lever A (force-edit watchdog injects "commit to ANY edit now" UserMessage at 0.7×maxIter when `totalWriteCalls=0`); Lever B (best-guess salvage — one final LLM call on iter-limit OR BUDGET_EXCEEDED if no writes happened, "wrong-file beats empty patch"). Stagnation guard tightened: STAGNATION_FORCE outcome now triggers Lever B salvage too. Per-task RETRY enforces `requireBuildVerification=true`. HIGH-complexity tasks get a 2nd retry attempt when retry-1 still fails.

### Added — finding-triggered autofix (V183 `triggered_by_finding`)

Four customer-facing autofix surfaces all routed through one shared `finding/FindingPatchSubmitter`. Submitter dedupes per finding key, dispatches with `runMode=PATCH` + `source="finding:<TYPE>"`, and `runPatchPipeline` auto-pushes via `scmPushService.push()` on terminal:

| Phase | Surface | UX |
|-------|---------|-----|
| 1 | `prreview/PrReviewAutofixService` | PR Review verdict popover → "Autofix N comments" → patch pushes to PR head branch via `branchOverride` |
| 2 | `project/techdebt/TechDebtFixService` | TechDebtPanel category rows → "Fix" per category → fresh PR per category |
| 3 | `project/pr/CiFailureAutoFixService` | GitHub `check_run` webhook auto-fires on PR CI failure (flag-gated `ci.autofix.enabled`, default OFF; max 3 attempts/PR) |
| 4 | `codereview/CodeReviewAutofixService` | CodeQualityPanel severity chips → "N CRITICAL" / "N MAJOR" → one PR per severity |

### Added — external project sharing (V175)

- **Cross-org `ProjectMember` invites** — paid-tier admins invite by email; on accept the invitee gets a `is_external=true` member row. New `PROJECT_VIEWER` role (read-only).
- **Public read-only share links** — `project_share_link` table with `Surface` enum (PROJECT_OVERVIEW / RUN_DETAIL / CODE_REVIEW / PR_REVIEW / VALIDATION_REPORT / ARCHITECTURE_DOC). Tier-gated by `tier_config.share_link_active_cap` (FREE=3, PRO=25, paid=unlimited).
- 8 new endpoints under `/api/projects/{id}/invites`, `/api/projects/{id}/share-links`, `/api/invites/...`, `/api/shared/{token}/...`.

### Added — PR + Code review agents (V172, V173)

- **PR Review Agent** (`prreview/`) — single-call PLANNING-tier review of one PR's diff. Async-with-SSE; `submitReview` tool with `verdict ∈ {APPROVE, COMMENT, REQUEST_CHANGES}` and line-anchored comments persisted to `pr_review` + `pr_review_comment`.
- **Code Review Agent** (`codereview/`) — whole-repo two-stage scan. Stage 1 `SemgrepRunner` runs `semgrep --config p/ci` (graceful degradation when semgrep missing). Stage 2 `CodeReviewAgent` triages KEEP / REWRITE / DROP per finding + adds architectural / security issues. Renders single markdown report.
- **PR comment inbox** (V181) — comments on PRs flow into the inbox so the autofix surface knows what to address.
- Per-tier monthly quotas: PRO=10 PR reviews / 4 code reviews; Team/MAX/ENTERPRISE unlimited.

### Added — verified score v2 + reviewer structured verdict

- **Multiplicative `verifiedScore`** (264cca58) — replaces the additive 40+20+20+25+10−5×crit ladder; now `clamp(0,100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`. Patch-mode runs surface evidence chips ("Build ✓ · Tests 73/73 · Reviewer CLEAN") on the V3 ScoreCard.
- **Structured `VERDICT:` prefix** on `completeTask` summary (413011cc) — reviewer emits `VERDICT: CLEAN | FIXED | UNRESOLVED | REVIEW_FAILED` as first line of completion summary so downstream consumers don't have to regex the prose.
- **AccuracyAssessmentService LLM read budget hardened** (10k char/file, 100k char ceiling, `[truncated N chars]` marker recognised). `countUnfixedCritical` filters out `SYNTH-ACC-*` IDs so LLM read-budget bugs don't drag the headline number.
- **GA-readiness P3.1** — `WorkerAgent` budget-exceeded outcome consistent with metric (was emitting `Outcome.ERROR` while metric said BUDGET_EXCEEDED).
- **AccessGuardCoverageTest** (ArchUnit) — pins every `@*Mapping` method whose URL touches `/runs/{` / `/projects/{` / `/executions/{` / `/launches/{` / `/repos/{` to call a tenant access guard. Known-legacy ledger of 10 sites that still need migration.

### Added — Solo tier (V176, V177) then retired (V179, V185)

- **V176 + V177 (2026-05-06)** — un-hid PRO as `Solo` at $29/mo + added Solo Yearly billing plans + the founder-pricing 50% lifetime discount schema (`FounderPricingService.tryAllocate` with row-level lock).
- **V179 + V185 (2026-05-06 / 2026-05-09)** — retired Solo tier + the product-split discriminator. Single surface `executespec` for all accounts. Tier ladder is now FREE → PRO (HIDDEN — grandfathered) → TEAM → ENTERPRISE.

### Added — billing polish

- **`user_cost_cap_usd`** on `pipeline_run` (V178) — customers can lower the ceiling at submission time. `RunOrchestrator.persistEstimate` clamps `hard_cap_usd = min(platformCap, userCostCap)`. Default platform cap raised $4 → $8 (Gemini Pro was hitting BUDGET_EXCEEDED at $4.06-$4.18 on hard SWE-bench instances).
- **BYOK cost-cap exemption** — when org is `OWN_KEYS` mode, platform cap is skipped (customer pays their own API bill). Customer's explicit `userCostCap` is still honoured.
- **Visible cost-cap progress bar** on RunDetail Credits panel (`CostProgressBar`) — three-threshold horizontal bar (green 0-80% / amber 80-100% / red ≥100%) with aria-progressbar attributes. For ALL users, not gated.
- **Cache pricing reads YAML** — `RunCostTracker.priceCall` now does direct math on `p.getCachedInputCostPer1m()` from `application.yml model-matrix.model-pricing[]` (was: hardcoded multipliers that ignored YAML).
- **PR record `project_id` + `kind`** (V169) + **CI auto-fix attempt counter** (V170).

### Added — UX polish + a11y

- **JetBrains font unification + 11 audit fixes** (no-billing batch, 0e02da39) — JetBrainsMono variable font, alignment fixes across login + dashboard + project surfaces.
- **PR refresh button** also polls CI check-runs alongside open/merged state (V186, 75195d9c).
- **SPA fallback** for 4-segment routes (e.g. `/projects/:slug/runs/:runShort/...`) (aaa41a58); favicon redirect; V3 chat actions restored; file scope toggle.
- **RunDetail auto-refresh** — files refresh every 5s while non-terminal (was: only on RUN_STATUS SSE). Folder tree replaces flat list of changed files. Goal / userRequest expandable with `RunSpecBlock`.
- **Update PR button** on `InlineRunDetail` when `alreadyPushed=true` — per-task retry (`POST /runs/{id}/tasks/{taskId}/retry`) doesn't auto-push, so users can re-push manually after a retry.

### Added — model-mapping consolidation (single source of truth)

- **YAML is the single source of truth** for every model identifier / context-window / max-output-tokens / pricing rate / display label / fallback chain / free-tier lock / agent→tier map / provider-default model.
- Deleted `ModelContextWindowMap` (was disagreeing with YAML on `gpt-5.4` context window).
- `RunCostTracker.priceCall` reads YAML cache rates.
- `ModelController.MODEL_LABELS` reads YAML `display-label`.
- `RunOrchestrator.defaultModelFor` routes through `ModelRegistry.workhouseModelFor(provider)`.
- Frontend `PricingTable.MODEL_ROLES` reads YAML `chainTier` via new DTO fields on `PricingRow`.

### Added — operations + reference docs

- **`docs/operations/slos.md`** — 5 SLOs (run terminal in 15min ≥95%, verified-score correctness ≥90%, cost estimate within 30% on ≥80%, webhook in 60s ≥99%, hot-path 5xx <0.5%) with measurement queries, alert thresholds, owners.
- **`docs/reports/model-mapping-audit-2026-05-06.md`** — single source of truth audit + 3-provider LOW-band cost eval baseline.
- **GDPR data-export endpoint** — `GET /api/auth/me/data-export` returns ZIP (manifest + payload + README) with sensitive fields stripped.

### Fixed — pipeline reliability

- **OpenAI 0.7× iter cap lifted** (`c2a36d2b`) — premise was stale; V2 OpenAI uses `OpenAiNativeToolClient` which preserves prefix stability and now hits 43% auto-cache utilization. HIGH non-project tasks back at 75 iters.
- **Gemini cache prefix preservation** (`dda33577`) — stagnation warning is now `UserMessage` not `SystemMessage` so it doesn't invalidate Gemini's implicit cache prefix. Patch-mode now hits 41-49% implicit cache (was 0%). Plus always-on `cache_read_input_tokens` metadata on Gemini + OpenAI clients.
- **Gemini reviewer→Flash** (`e9d0b6e8`) — `ReviewerAgent.runPass` resolves Gemini reviewer to WORKHORSE tier (`gemini-3-flash-preview`, 1024-token implicit-cache threshold) instead of PLANNING tier (4096 threshold). Fixes 0% Pro-reviewer cache hit rate.
- **V184 widens `agent_task.last_llm_failure`** VARCHAR(255) → TEXT — closes the overflow class found on dev run `7483896a` (worker actually completed but `saveAndFlush` threw on >255-char soft-completion `softWarnReason`). 4000-char defensive cap added in `SequentialExecutor.persistTaskStatus`.
- **V171 reconciles `pipeline_result` with `status_reason`** (R09) — historical PARTIAL rows that should have been COMPLETE_WITH_CONCERNS now consistent.
- **Build-error-as-spec on retry** (`91779ccd`) — `ReviewerAgent` captures pre-pass build state; when failing, prepends verbatim build error to user prompt on the second attempt (the build error is the explicit spec on retry).

### Removed

- **Solo tier branding** (V185) — un-renamed the V176 PRO→Solo rebrand; PRO stays HIDDEN (grandfathered) as before.
- **`product_id` discriminator + `app_user_product_access`** (V179) — the consumer/B2B surface split was prototyped (V143) and rolled back. All accounts on single `executespec` surface; `TierProductSurfaceMapper` no longer mediates anything.
- **Twenty-one audit + handoff + greenfield-redesign docs** + the entire `docs/audits/` tree, consolidated into the kept reference docs (operational truth lives in CLAUDE.md / ARCHITECTURE.md). 8 per-plan files under `docs/plans/` consolidated into a single `docs/plans/pending.md`.

### Migration notes

- Database migrations **V1–V186** apply on first boot. All additive / idempotent. Highlights since 1.0.0-M1: V172 (PR + Code review agent tables), V175 (external sharing), V178 (user cost cap), V182 (`pipeline_run.run_mode = BUILD | PATCH`), V183 (`pipeline_run.triggered_by_finding`), V184 (widen `last_llm_failure` to TEXT), V186 (PR CI status columns).
- **Feature flags** new since 1.0.0-M1 — `ci.autofix.enabled` (default OFF — gates the Phase-3 GitHub check_run webhook autofix path until SWE-bench resolved% justifies trusting unattended).

---

## [1.0.0-M1] — 2026-05-02

First public milestone. Consolidates every shipping feature from the pre-1.0 development line into a single tagged release.

### Added — pipeline + agents

- **V2 multi-agent pipeline** as the only execution path. Smart classifier (LIGHTWEIGHT) → outline planner (PLANNING) → sequential executor with per-task tier-routed worker → reviewer (general + wiring passes) → final smoke + validation report + auto-preview. Hierarchical planning for LARGE/LEGACY specs (skeleton outline → parallel task expansion → coherence patch). Plan-strategy circuit breaker auto-degrades when a strategy fails repeatedly.
- **Validation v3 / Pillar 1** — planner emits a `RequirementSpec{id, text, kind}` graph on every plan. Each task declares which requirement IDs it covers; reviewer's coverage check counts CLEAN only when every requirement has at least one passing task. Gated behind `validation.v3-strict` (default OFF).
- **Deterministic verifiedScore** — derived from build-passed + tests-passed/total + reviewer verdict + critical-issue penalty. No LLM self-rating in the score path. `accuracyScore` retained as a secondary "Est." badge for callers that want the LLM judgement.
- **Per-run codemap + project architecture** — 3-step pipeline (per-repo codemap → per-repo HLD/LLD architecture doc → combined project architecture). Idempotent by `generatedFromSha`. Versioned. Lazy-refreshed before each run if SHA drift is detected.
- **Project memory** — every completed project run appends a digest to `project_run_ledger`; rewritten into a versioned `project_memory_snapshot` every 10 runs. Planner reads it as `{{PROJECT_MEMORY}}`.
- **RAG corpus** — Tika + pgvector. User-uploaded docs indexed; planner + worker read narrowed retrieval at run-time.
- **Generated docs** — on-demand EXPLANATION + SETUP markdown synthesised from project state. Per-project, versioned.

### Added — pause/resume (V168)

- **`POST /api/runs/{id}/pause`** — flips status to PAUSED, interrupts the active pipeline future, preserves workspace files on disk. Valid only from WORKING / REVIEWING. Behind the `v2.pause.enabled` feature flag (default OFF).
- **`POST /api/runs/{id}/resume`** — same-JVM only (workspace dir must still exist). Re-launches `runPipeline` with the original spec; planner sees workspace state and adapts. Cross-JVM resume returns 409 with a retry hint.
- Six new `pipeline_run` columns: `pause_requested`, `paused_at`, `resumed_at`, `paused_task_id`, `paused_at_iteration`, `pause_count`.
- PAUSED is non-terminal — `cancel` + `halt-all` still work on PAUSED runs.
- `StartupRunResumerV2` leaves PAUSED runs alone on JVM boot — user resumes manually after deploys.
- **Deferred follow-ups (tracked separately):** mid-task checkpoint replay (executor + worker iteration-boundary checks), per-provider mid-LLM-call abort, cross-JVM workspace shipping via GCS. All scoped + bounded; today's pause is "interrupt + resume-from-disk" semantics.

### Added — runs

- **Multi-repo project runs** — clone each linked repo into `<workspace>/<runId>/repos/<name>/`, plan with explicit per-repo paths, push per-repo PRs. Per-repo codemap + per-repo architecture doc. Cross-link in PR bodies.
- **TDD mode** — planner leads with test tasks; implementation tasks `dependsOn` the test ids. Toggle on `NewRunModal`.
- **Spec templates** — project-scoped + org-scoped, surfaced via `TemplatePicker` above the requirement textarea.
- **Heuristic launch estimate** — debounced `~M-N tasks · ~$X-Y` chip below the requirement textarea, computed from the project's last 20 completed runs (no LLM call).
- **Tech debt panel** — issues by category + severity + file hotspots + dimension trend, aggregated from `validation_report.issues_json`.
- **Workspace health rollup** — per-repo SHA drift, webhook install status, open PR count, last-analysed timestamp on the project Overview tab.
- **Post-merge cascade banner** — "✓ PR #N merged — Start next requirement →" up to 3 stacked entries, polled on dashboard mount.
- **Cancel rollback** — aborting a run closes any opened PRs (idempotent), with a live cleanup view via `EXEC_COMPENSATION_*` SSE events.

### Added — billing + tiers

- **4-tier ladder**: Free (5 runs/month, no credit card) · Pro $20/mo (HIDDEN — grandfathered, new buyers choose Team) · Team $39/seat/month BYOK (min 2 seats) · Enterprise (custom).
- **Cost-proportional run credits** — `credits = max(1, ceil(estimate × 1.10 / $5.0))`. Charged at plan approval, refunded on FAILURE. FREE tier limited to 1-credit specs.
- **Tier lifecycle state machine** — LISTED / HIDDEN / SUNSETTING per `tier_config` row. SUNSETTING cancel-at-period-end behind `billing.lifecycle.sunsetting.enabled` flag.
- **Audit log entries date-only** in the SUPER_ADMIN tier-lifecycle view (full reason on hover) so migration churn doesn't leak into screenshots.
- **Multi-gateway billing** via `PaymentGatewayRouter` (Razorpay + Stripe). Subscriptions, run top-up credits, invoicing with GST.
- **`run_cost_log`** audit table — estimated vs actual cost, margin, budget-hit rate per run.

### Added — UX

- **V2 sidebar shell + V3 top-bar shell** co-habit. V3 chrome lives under `frontend/src/v3/` with its own theme tokens.
- **Outcome-led empty state** on V3 project — `What do you want to build, {firstName}?` + tier-aware run-count copy + `Already have a GitHub repo? Attach it first →` secondary link.
- **Project chat** with compaction (summarise older N messages), reset, copy-on-hover per bubble, branch override on launch, live cost polling on RunDetail.
- **Generate Docs panel** on project Overview — Generate / Regenerate / View / Download for EXPLANATION + SETUP types.
- **VerdictPill** — Shippable / Needs review / Blocked derived from build + tests + reviewer + verifiedScore. Numerics demoted to disclosure.
- **`<Glossary>`** primitive — hover/focus tooltip with plain-English definitions. Seed dictionary covers `codemap`, `wiring pass`, `verified score`, `status reason`, `classifier`.
- **First-run sample gallery** — recipe-shaped templates (Add Stripe Checkout, Migrate Express → Fastify, Convert REST → tRPC, Add feature flags via GrowthBook).
- **Project memory + activity feed** panels on the project dashboard.
- **`.executespec/` per-repo folder** — bootstrapped on repo link via `BootstrapService.bootstrapAsync`. Contracts / standards / ADRs / architecture invariants persist there.

### Added — security + governance

- **8-layer security**: request validation, input sanitization (NFKC + zero-width strip + base64 decode), LLM prompt defense, code abuse scanning, shell sandbox, file sandbox, process isolation, container hardening.
- **Guardrail levels** (LOW / MEDIUM / HIGH) flippable via `security.guardrail_level` flag; per-level deltas across input sanitizer, code abuse scanner, rate-limit policy, shell sandbox, file sandbox.
- **Tier-based RBAC** — SUPER_ADMIN / ORG_ADMIN / BILLING_ADMIN / DEVELOPER / PROJECT_ADMIN / PROJECT_DEVELOPER. Force-logout via feature-flag epoch JWT invalidation.
- **R8 — personal-org permission gap fix** — TEAM/ENTERPRISE users on personal orgs get `canViewAudit / canInviteMembers / canViewOrgMembers / canManageIntegrations / canViewPricing` correctly.
- **F-14 — EntitlementResolver tier-rank fix** — picks the higher of (user.tier, org.tier) instead of "org always wins". Closes the FREE-leak class for operator-promoted accounts, expired-but-not-demoted subs, and test fixtures.
- **R9 — typed-confirmation modal** for destructive ops (Halt all runs requires typing HALT + an optional reason).
- **R10 — RoleGuard deep-link fix** — `/admin/tiers` no longer bounces on auth-store hydration.
- **Audit chain verification** — SUPER_ADMIN-only endpoint to prove no `activity_audit` row was tampered or deleted.

### Added — accessibility

- **WCAG-AA contrast tokens** (F-15 / F-16 / F-17): `--border-default` 1.59 → 3.13 (graphite-7 #5e6771; passes SC 1.4.11 non-text), `--status-executing` 4.19 → 5.22 (#5b89e0; AA-body), new `--c-signal-blue-on-dark` (#7aa5f5; 7.29 contrast for body links on dark surfaces).
- **`frontend/src/design-system/contrast.{ts,test.ts}`** — pure-function WCAG ratio helpers + vitest-shape regression test pinning every (token, surface) ratio + counter-examples that must still fail. Dormant until vitest is wired.
- **`frontend/.pa11yci.json`** — pa11y-ci config for the public surface (login, register, plans, pricing, terms, privacy, dpa). Auth-gated routes deferred.
- **`frontend/A11Y.md`** — enablement guide.

### Added — code quality + CI

- **Google Java + TypeScript style refactor** — `spotless-maven-plugin` 2.43.0 + `googleJavaFormat` 1.22.0 (GOOGLE profile) wired into `mvn verify`. Frontend `prettier` 3.3.3 + `eslint` 8.57 + `@typescript-eslint/*` 7.18 + react / react-hooks plugins. 1303+ files reformatted; zero semantic changes.
- **Documented deviations** in `docs/design/code-style.md`: D-J1 wildcard imports retained · D-J2 Javadoc only on top-level classes + REST controllers · D-J3 `// VisibleForTesting` comment vs annotation · D-T1 default exports OK for `*.tsx` · D-T2 `any` warn-mode · D-T3 interface vs type both accepted · D-T4 JSDoc on internal types skipped.
- **CI lint enforcement** — `.github/workflows/lint.yml` runs `mvn spotless:check` + `npm run format:check` + `npm run lint` on every PR to dev/main. `.github/workflows/deploy.yml` upgraded `mvn package` → `mvn verify` so the format gate fires on dev push too.
- **Documentation hygiene policy** — every commit must keep root `CLAUDE.md` + `docs/CODEMAP.md` + `docs/document_map.md` in lockstep with code changes.

### Fixed — credibility-class bugs (the audit r3 sweep)

- **F-21** V3 billing for paid tiers no longer hardcodes "Unlimited" — reads `runLimitMonthly` per tier so PRO renders "20 of 20 runs left this month" correctly.
- **D3** empty-state copy reframed from "Welcome, Reval." → "What do you want to build, Reval?" (outcome-led; tier-aware run-cap line; `Attach it first →` secondary link).
- **F-23 (multi-repo)** worker prompt now receives the workspace-summary block. Pre-fix, multi-repo project runs since V2 cutover wrote files at workspace root and per-repo push opened empty PRs. Real, latent regression vs V1 — covered by `MultiRepoWorkerPromptTest` (4 cases).
- **Tier lifecycle audit-log** dates-only (reason on hover) so migration-version churn no longer leaks into SUPER_ADMIN screenshots.
- **Login hero** rewritten — `Ship AI-written PRs you can actually merge.` (was `Sign in to ship a spec.`).
- **Website hero** tightened — H1 17 → 6 words, 45-word subtitle replaced with an 18-word muted proof strip below the composer.
- **OAuth localhost redirect** — `OAuthSurfaceResolver` no longer stamps `SURFACE_EXECUTESPEC` on no-match; localhost defaults seeded in `application.yml`.

### Changed

- **Tier ladder consolidated to 4 tiers** — Pro hidden / Team renamed from MAX / per-seat TEAM dropped. Tier copy aligned across website + frontend + tier_config.
- **Free-tier monthly run cap** — 5 runs/month (V145's bump to 10 reverted via V167 — positioning decision: "free tier will always remain 5").
- **CHANGELOG** consolidated to a single `1.0.0-M1` entry; pre-1.0 history lives in git.
- **Default LLM provider** — Anthropic (was OpenAI). User-selectable per project.
- **Pause/resume endpoints** — were 410 Gone, now functional behind `v2.pause.enabled`.

### Deprecated

- **PRO tier hidden from new buyers** (`tier_config.PRO.lifecycle_state = HIDDEN`). Existing PRO subscribers continue to renew; new signups choose Team or Free.

### Removed

- **MAX tier** (renamed to TEAM via V165).
- **Per-seat TEAM tier** (the v2.6.1-Wave-1 introduction; reverted in V165).
- **V1 PipelineHarness** — V2 is the only execution path. V1 endpoints (`/runs/{id}/clarify`, `/chat`, `/apply`, `/iterate`, `/merge`, `/reopen-merge`, `/retry`, `/rework`, `/implement-suggestions`, `/edit-plan`, `POST /api/feasibility`) return 410 Gone or have been removed entirely.
- **Old persona-test reports** (rounds 1+2+3) — superseded by `docs/audits/persona-test-2026-05-04-r4.md` + `docs/audits/dev-verification-2026-05-04.md`.
- **`audit-output/` legacy plan dir** — 16 stale planning docs cleaned up; current plans live under `docs/plans/`.

### Migration notes

- Database migrations V1–V168 apply on first boot. Non-destructive — existing rows are backfilled / left untouched. V165 (drop MAX) and V167 (revert FREE to 5) run UPDATE-only; V162 + V163 add personal-org bootstrap; V168 adds the pause/resume columns.
- **Feature flags shipped OFF by default** — `validation.v3-strict`, `assessment.llm.enabled`, `billing.lifecycle.sunsetting.enabled`, `v2.pause.enabled`. Ops flips them on after smoke-verifying each on dev.
- **Style enforcement** — first push to a clean repo will format every Java + TypeScript file; from then on `mvn verify` + the lint workflow keep it from drifting.
