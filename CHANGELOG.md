# Changelog

All notable changes to ExecuteSpec are documented in this file.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic Versioning](https://semver.org/).

The pre-1.0 development history (the v2.5.x line that ran from 2026-04-04 through 2026-05-01) is preserved in git log + commit messages; this changelog starts at the first numbered milestone release.

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
