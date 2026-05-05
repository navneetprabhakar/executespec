# 90-Day GA Readiness Execution Plan

> **Source audit:** `/tmp/ga-readiness-audit.md` (2026-05-04 · senior-eng review).
> **Verdict from audit:** NO-GO for 60-day public GA. Pursue Plan B — 90-day private beta, public GA Q3 2026.
> **This document:** the per-week, per-task execution plan that operationalises Plan B.
> **Owner:** founding eng team (3 engineers assumed: Backend/AI, Full-stack, Frontend/UX).
> **Cadence:** Friday demo + Monday sprint plan.

---

## 1. North-star goal

GA on **2026-08-04** (13 calendar weeks from today, 2026-05-05) with:

- **Trustworthy quality signal:** dashboard `avgScore` ≥75 on the same artefacts that today read 43.75 — closes the "looks broken" gap that would torch a public launch.
- **Coherent pricing ladder:** FREE → SOLO ($29/mo managed) → TEAM ($49/seat BYOK) → REVIEW bolt-on → ENTERPRISE — closes the FREE-to-$78 chasm that breaks the conversion funnel.
- **HIGH-band reliability:** 95% terminal in <600s p95 on all 3 providers — closes the current 100% TIMEOUT on HIGH-band v2-eval (`v2-eval-20260504-165829.md`).
- **Cost ceiling enforced:** no run exceeds approved cap × 1.0; visible cap progress in the UI — closes the runaway-worker class that produced the $20.05 single-run on dev.
- **SOC 2 Type I in audit:** kicked off, on track to issue Type I report inside the GA window — unblocks ENTERPRISE procurement.
- **25 beta partners with ≥3 merged PRs each** — converts "private beta" from a fig-leaf into 75 real PR data points + testimonials for the GA launch.

Below the success bar but explicitly **out of scope** for GA: SOC 2 Type II, native Android, GitLab/Bitbucket support, V2 batch, IDE plugin (CLI yes, IDE no), Figma integration v2.

---

## 2. Sequencing strategy

The audit identifies one fix that dominates everything else: **AccuracyAssessmentService can't read its own files, so the validation report flags real implementations as "partial".** Until that lands, no other work matters — beta partners watching working code score 45/100 will churn.

Therefore the plan is **front-loaded**: Phase 1 (quality fix) runs in parallel with Phase 0 (land in-flight branches) and gates everything downstream. Phases 2-5 only start once the dev `avgScore` SLO is met on real runs.

```
Week 0    1    2    3    4    5    6    7    8    9    10   11   12   13
─────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────┼────
P0 ███
P1   ████████████
P2             ██████████
P3                  ██████████
P4                       █████████
P5                            ██████████████████
P6                                                              ███
                                                              GA └──┘
```

- **P0 — Land outstanding work** (Week 0): merge worker-runaway + hierarchical-planner + project-cost-pool-tuning into dev.
- **P1 — Quality scoring fix** (Weeks 1-3): the single highest-leverage workstream. Gates everything.
- **P2 — Pricing ladder rewrite** (Weeks 3-5): un-hide SOLO, ship REVIEW bolt-on, activate annual.
- **P3 — Cost ceiling + HIGH-band reliability** (Weeks 5-7): wire `hardCapUsd` into WorkerAgent; merge hierarchical-planner; pass v2-eval HIGH band.
- **P4 — Observability + multi-tenant security** (Weeks 7-9): ArchUnit guard pin, SLO definitions, alert rules, status page.
- **P5 — Beta program + SOC 2 Type I + CLI** (Weeks 8-12): 25 partners, weekly cohort calls, SOC 2 Type I audit, CLI on npm.
- **P6 — GA cutover** (Week 13): unhide marketing, activate Stripe production prices, public launch.

---

## 3. Phase 0 — Land outstanding branches (Week 0)

The three feature branches that are already cooked but not in dev. Land them in dependency order so Phase 1 can build on a clean baseline.

### P0.1 — Merge `feature/project-cost-pool-tuning` (PR #75)

**Why.** Already on the current branch and fully tested locally. Carries V174 (cost rollup), V175 (sharing), and core-dependent dispatch pools. Other work in P2 (REVIEW bolt-on pricing) reads from the new `costBreakdown` DTO, so this must land first.

**Files (already committed, just needs review + merge).**
- `src/main/java/com/navneet/executespec/util/PoolSizing.java`
- 5 pool-sizing call sites (`CodeReviewService`, `PrReviewService`, `V2RunService`, `RunConversationController`, `RunOrchestrator`)
- V174 + V175 migrations, sharing/ package, root + per-package CLAUDE.md.

**Acceptance.**
- [x] PR #75 already opened.
- [ ] Squash-merge into `dev`. Verify dev deploys clean.
- [ ] Run `mvn verify` on dev branch HEAD; 967/0/0/4 expected.
- [ ] Smoke test: `POST /api/projects/{id}/share-links` returns 201; `GET /api/shared/{token}/meta` returns 200; `GET /api/projects/{id}/dashboard` returns `costBreakdown` object summing to `totalCostUsd`.

**Effort.** 0.5 day. **Owner.** Full-stack.

---

### P0.2 — Merge `feature/worker-runaway-fixes` into `dev`

**Why.** Audit Risk #4 directly. The 4 commits (`358ec267` strip-gitCommit + `72cbf65a` stagnation guard + `74547b99` honest soft-completion gate + `8bfe9a58` per-repo language parity) close the failure class behind the 60-iteration / 70k-token wasted runs. Required for P3 hard-cap enforcement to behave sanely.

**Files.** As listed in CLAUDE.md "In Progress" §worker-runaway-fixes — 4 backend commits + new test classes (`ToolRegistryV2WorkerBindTest`, `WorkerAgentV2StagnationTest`, `SequentialExecutorV2SoftCompletionTest`, `LanguageParityBlockTest`).

**Acceptance.**
- [ ] `git pull --ff-only origin dev` then rebase `feature/worker-runaway-fixes` onto dev (cleanly).
- [ ] `mvn test` 881/881 green.
- [ ] Open a PR, merge after review.
- [ ] Re-run a small multi-task spec on dev; confirm `WorkerAgent` does NOT call `gitCommit` (grep tracelogs for `tool=gitCommit role=worker` — should be 0 hits).
- [ ] Confirm a deliberately-stagnant task (write the same line then verify 12x) terminates with `outcome=COMPLETED_BY_STAGNATION`.

**Effort.** 1 day (review + merge + smoke). **Owner.** Backend/AI.

**Risk.** May surface latent test-class drift on dev. Mitigation: rebase locally first, fix any conflicts in a single follow-up commit.

---

### P0.3 — Merge `feature/hierarchical-planner` into `dev`

**Why.** Audit Risk #3 directly. v2-eval HIGH band currently 100% TIMEOUT at 900s on all 3 providers. The 5 slices on this branch (Outline → parallel Expand → Coherence + LEGACY recursive depth-2) are the architectural fix. Required for P3 SLO acceptance.

**Files.** As listed in CLAUDE.md "v2.6.2-track hierarchical planner" entries — `SmartClassifierAgent`, `OutlinePlannerAgent`, `TaskExpanderAgent`, `CoherencePlannerAgent`, `PlanStrategyCircuitBreaker`, V130 migration, plus the 2026-04-30 fix(planner) JSONB mapping.

**Acceptance.**
- [ ] `git pull --ff-only origin dev` then rebase or merge — likely rebase due to migration-number conflicts (V130 vs V175 sequence).
- [ ] `mvn test` green (target 967+ tests).
- [ ] Run **single dry-run** on dev on a HIGH-band spec; confirm `pipeline_run.plan_strategy` is set to `LARGE`, the task list is fully expanded (0 skeleton-only rows), and the run reaches a terminal status. Time budget: 600s p95.
- [ ] Update `application.yml` to enable hierarchical planner by default if a feature flag exists (verify the rollout knob).

**Effort.** 2 days (rebase, conflict resolution, dev verification). **Owner.** Backend/AI.

**Risk.** This branch is 4 weeks old; rebase will hit migration sequencing + test-class drift. Mitigation: do this AFTER P0.1 so the migration trail is settled at V175, then renumber V130 → V176 if conflict.

---

## 4. Phase 1 — Quality scoring fix (Weeks 1-3)

**The single most important phase.** Audit §3 + Risk #1. Closes the 45→75 score gap on dashboard, unblocks customer perception, and is the prerequisite for charging money for output.

### P1.1 — `AccuracyAssessmentService.inferKeyFilePaths` rewrite

**Why.** Today's selection: when ANY file matches a structural keyword (`route|controller|service|main|app`), only structural matches are returned, even if there's only 1 of them in a 6-slot read budget. On run `0444c95f`, that meant `main.py` was read but the 5 implementation files in nested dirs (where the actual endpoint code lives) were never selected. The phrase **"controller exists but content not readable"** appears 14 times in that report — that's the pattern.

**Files.**
- `src/main/java/com/navneet/executespec/reviewer/AccuracyAssessmentService.java` — `inferKeyFilePaths`
- `src/test/java/com/navneet/executespec/reviewer/AccuracyAssessmentInferKeyFilePathsTest.java` — extend to cover R-343 + this regression class

**Specific change.**
1. Walk the changed-file set (use `agent_task.files_modified_json` rolled up across all tasks, NOT the full file tree).
2. Sort by `(structuralKeywordMatch DESC, fileSize ASC)` so structural files come first AND when budget remains, smallest non-structural source files are pulled in.
3. **Fill the read budget** until either all candidates exhausted or budget-bytes consumed (default 60KB / 6 slots).
4. Add streaming chunk reads (16KB chunks) so a single 200KB controller file doesn't blow the budget — concatenate up to N chunks per file with a `[…truncated, file too large…]` marker.
5. Add explicit test: a multi-repo run with 30 modified files across `repos/api/src/routes/*.py`, `repos/frontend/src/pages/*.tsx`, `repos/mobile/src/screens/*.tsx` should produce a `keyFilePaths` covering at least one file from each repo's modified set.

**Acceptance.**
- [ ] New unit test `inferKeyFilePaths_topsUpWithSmallestSourceFiles` passes.
- [ ] New unit test `inferKeyFilePaths_perRepoCoverage` passes (per-repo file selection).
- [ ] Re-run `accuracyJson` against the persisted artefacts of run `0444c95f` (replay endpoint or a one-shot integration test); assert "content not readable" string count drops from 14 to ≤2.
- [ ] No regression on the 6 existing tests in `AccuracyAssessmentInferKeyFilePathsTest`.

**Effort.** 4 days. **Owner.** Backend/AI.

---

### P1.2 — Decouple `verifiedScore` from LLM read budget

**Why.** Audit §3: `ValidationReportService.computeVerifiedScore` documented formula yields 75 on run `0444c95f`, but the persisted value is 50. The −25 gap is an undocumented downstream adjustment that anchors on `accuracyScore` (which itself anchors on the LLM's read budget). Until verifiedScore stops compounding the read-budget bug, P1.1 is necessary but not sufficient.

**Files.**
- `src/main/java/com/navneet/executespec/report/ValidationReportService.java` — `computeVerifiedScore`
- `src/main/java/com/navneet/executespec/scoring/ReviewScoringService.java` — `score` anchor logic (line ~110)
- `src/test/java/com/navneet/executespec/report/ValidationReportServiceVerifiedScoreTest.java` — new test class

**Specific change.**
1. `computeVerifiedScore` reads ONLY: `finalBuildPassed`, `testsPassed/testsTotal`, `reviewerVerdict`, `endpointCoverage.coveragePercent`. Drop any anchoring on `accuracyScore`.
2. Formula (already documented; just enforce):
   ```
   base 40
   + 20 if finalBuildPassed
   + round(20 × testsPassed / testsTotal)  // 0 if no tests
   + verdict points (CLEAN=20, FIXED_ISSUES=15, UNRESOLVED=5, REVIEW_FAILED=0)
   + 10 if endpointCoverage.coveragePercent >= 95
   - 5 × unfixedCriticalCount
   clamp 0..100
   ```
3. `ReviewScoringService.score` anchors on `verifiedScore` directly (not `accuracy.specAdherenceScore`). Demote `accuracyScore` to "Est." in the UI.
4. Backfill: re-score every existing run via a one-shot `@Profile("backfill")` `ApplicationRunner` over `validation_report` rows; flip `scoring.v2-verified` flag ON globally on dev after backfill.

**Acceptance.**
- [ ] New test `computeVerifiedScore_pinsToFormula` covers all 5 inputs at boundaries.
- [ ] Backfill runner writes to a new `validation_report.verified_score_v2` shadow column first; sanity-check diffs before swapping.
- [ ] Dev `avgScore` over the 4 terminal runs in project `ppl-ai` (`a092ffaa-...`) goes from 43.75 to ≥70.
- [ ] No frontend regression: `ScoreRing` + `SignalBreakdown` render identical for runs where the formula yields the same result.

**Effort.** 3 days. **Owner.** Backend/AI + Frontend (1 day for UI label demotion).

**Dependencies.** P1.1 (without read-budget fix, the gradingNotes deduction still drives the score).

---

### P1.3 — Per-repo accuracy file-tree listing (already partially shipped)

**Why.** Commit `c1340c7f` on `feature/worker-runaway-fixes` ("fix(accuracy): per-repo file-tree listing — closes architecture=0 false-positives") was a partial fix. P1.1 + P1.2 close the rest. Verify it carries over and stays green.

**Files.** Already on the worker-runaway branch; lands via P0.2.

**Acceptance.** Already covered by P0.2's `AccuracyAssessmentInferKeyFilePathsTest`.

**Effort.** 0 incremental (covered by P0.2). **Owner.** Backend/AI.

---

### P1.4 — UI honesty pass on the dashboard

**Why.** Current state: amber `CLEAN_WITH_INTERMEDIATE_WARNINGS` reads as failure to a customer. The `pipelineResult: "FAILED"` field in some validation reports despite `finalBuildPassed=true` (per Audit Appendix A) is a regression worth one careful UI review.

**Files.**
- `frontend/src/components/runs/StatusBadge.tsx` — DISPLAY_NAMES + tooltip copy
- `frontend/src/components/runs/ValidationReportPanel.tsx` — drop "FAILED" label when `verifiedScore >= 70 && finalBuildPassed && reviewerVerdict ∈ {CLEAN, FIXED_ISSUES}`
- `frontend/src/components/runs/SignalBreakdown.tsx` — add a small "ⓘ Why" inline link that opens the new glossary tooltip explaining `CLEAN_WITH_INTERMEDIATE_WARNINGS` in plain English

**Acceptance.**
- [ ] Visual-regression test (Playwright snapshot) on a representative run with `statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS`; the badge reads "Complete · with intermediate warnings" not "Failed".
- [ ] Glossary tooltip opens with one click, copy is plain English.

**Effort.** 2 days. **Owner.** Frontend.

**Dependencies.** P1.2 (so `verifiedScore` is trustworthy).

---

### P1 — Phase exit criteria

- [ ] Dev `avgScore` ≥75 on existing project `ppl-ai`.
- [ ] No "controller exists but content not readable" in the 3 most recent COMPLETE run reports.
- [ ] Re-run live HIGH-band spec; verifiedScore matches the formula within ±2 points.
- [ ] Frontend renders no false "FAILED" labels on COMPLETE runs.

---

## 5. Phase 2 — Pricing ladder rewrite (Weeks 3-5)

**Audit §6 + Risk #7.** Closes the FREE-to-$78 conversion chasm by un-hiding the existing PRO tier as SOLO at $29/mo managed, activating annual billing, and exposing the V172 review agents as a $9/mo per-repo bolt-on.

### P2.1 — Un-hide PRO as SOLO ($29/mo managed)

**Why.** PRO already exists in `tier_config` (HIDDEN since 2026-04-22 V122 → grandfathered, then re-confirmed in V141). The Stripe price IDs and `billing_plan` rows exist. The only blocker is `lifecycle_state='HIDDEN'` and the absence of a frontend card.

**Files.**
- New migration: `V176__unhide_solo_tier_and_rename.sql` — `UPDATE tier_config SET display_name='Solo', lifecycle_state='LISTED' WHERE tier='PRO'`. Update both `billing_plan` rows (INR + USD) display_name to "Solo Monthly".
- Frontend: `frontend/src/pages/PlansPage.tsx` — add SOLO card between FREE and TEAM in `tierOrder` array.
- Frontend copy: `frontend/src/pages/PlansPage.tsx` — "30 LOW or 10 MEDIUM or 4 HIGH credits / month · Managed keys".
- `application-prod.yml` — verify Stripe price ID env vars present (`STRIPE_PRO_PRICE_ID` → consider `STRIPE_SOLO_PRICE_ID` rename for clarity, but existing IDs work).

**Acceptance.**
- [ ] `GET /api/billing/plans` returns 4+ plans (SOLO, TEAM-USD, TEAM-INR, FREE; PRO grandfathered hidden via separate logic).
- [ ] `/plans` renders 4-tier ladder with SOLO between FREE and TEAM.
- [ ] Stripe checkout for SOLO completes E2E using a Razorpay test card on dev (`5500 6700 0000 1002`).
- [ ] Tier rank invariant: FREE < SOLO < TEAM < ENTERPRISE; existing PRO subscribers still resolve to PRO permissions (grandfathering preserved).

**Effort.** 3 days. **Owner.** Full-stack.

---

### P2.2 — REVIEW bolt-on at $9/mo per repo

**Why.** V172 already ships PR Review + Code Review agents. Audit §6 recommends bundling these as a per-repo bolt-on for the SOLO tier. PRO/TEAM/ENTERPRISE already get them included.

**Files.**
- New migration: `V177__review_bolt_on.sql` — `add_on` table (id, type='REVIEW_PER_REPO', price_usd=9, price_inr=799, included_pr_reviews=100, included_code_reviews=5, lifecycle_state='LISTED').
- New entity: `billing/entity/AddOn.java` + repo.
- New endpoint: `POST /api/billing/add-ons/subscribe` — body `{addOnId, repoId, gateway}`. Returns checkout session URL.
- `TierEnforcementService.assertCanSubmitPrReview` + `assertCanSubmitCodeReview` — read from `app_user_add_on` join table for SOLO users.
- Frontend: `PlansPage` adds a REVIEW bolt-on row under the SOLO card with "Add to your plan" CTA.

**Acceptance.**
- [ ] `GET /api/billing/add-ons` returns the bolt-on.
- [ ] SOLO user without bolt-on hits 403 on `POST /api/pr-reviews`.
- [ ] SOLO user with bolt-on hits 200; review runs and succeeds.
- [ ] Quota: 100 PR reviews/mo cap enforced (returns 429 at limit).

**Effort.** 5 days. **Owner.** Backend/AI + Frontend.

**Dependencies.** P2.1 (SOLO tier must exist).

---

### P2.3 — Activate annual billing (2 months free)

**Why.** V146 added annual schema; the seed never ran on dev. Annual is the standard SaaS knob for retention + cash-flow.

**Files.**
- Investigate `V146__annual_billing.sql`; either re-run on dev or add `V178__seed_annual_plans.sql` to fill in the rows that didn't seed.
- `BillingService.subscribe` — add `interval=YEAR` branch with 10× monthly = 12× monthly discount applied automatically.
- Frontend: `PlansPage` toggle "Monthly / Annual (save 16%)" above the tier cards.
- Stripe: ensure `*_YEARLY_PRICE_ID` env vars set on prod.

**Acceptance.**
- [ ] `/api/billing/plans?interval=YEAR` returns annual rows for SOLO + TEAM.
- [ ] Annual checkout succeeds E2E on dev.
- [ ] Frontend toggle updates the displayed price + saves correctly to Stripe metadata.

**Effort.** 3 days. **Owner.** Full-stack.

---

### P2.4 — Founder pricing 50% off lifetime (first 100 SOLO subscribers)

**Why.** Pre-PMF SaaS converts 3-5× better with founder pricing. Audit §6 recommends activating before GA so beta partners convert at the discount.

**Files.**
- New migration: `V179__founder_pricing.sql` — `founder_pricing_discount` table (id, percent=50, max_subscribers=100, current_count=0, lifetime=TRUE, expires_at=NULL).
- `BillingService.subscribe` — pre-checkout, if `tier=SOLO && current_count < max_subscribers`, apply Stripe coupon `FOUNDER50_LIFETIME` and increment count atomically (pessimistic lock).
- Stripe: create the coupon via Stripe dashboard or `StripeCouponBootstrapJob` `@PostConstruct`.
- Frontend: SOLO card shows "Founder pricing: $14.50/mo for life · 47 of 100 left" badge while available.

**Acceptance.**
- [ ] First 100 SOLO subscribers get 50% off applied.
- [ ] Subscriber #101 sees full price.
- [ ] Discount survives churn + re-subscribe by the same user (lifetime locked to user, not subscription).

**Effort.** 3 days. **Owner.** Backend/AI.

**Dependencies.** P2.1.

---

### P2 — Phase exit criteria

- [ ] `/api/billing/plans` returns 5+ entries (SOLO, TEAM-USD, TEAM-INR, FREE, plus annual variants).
- [ ] Pricing page renders 4-tier ladder with REVIEW bolt-on.
- [ ] Founder pricing visible to first 100; activates correctly.
- [ ] CLAUDE.md tier section updated; `website/index.html` tier copy matches `/api/billing/plans`.

---

## 6. Phase 3 — Cost ceiling + HIGH-band reliability (Weeks 5-7)

**Audit Risk #4 + Risk #6 + Risk #3.** Closes the runaway-cost class and the 100% HIGH-band timeout class. Required before opening to beta partners on plans where they pay real money.

### P3.1 — Wire `hardCapUsd` enforcement into WorkerAgent

**Why.** Audit §6: the cap exists in `RunCostTracker.estimateRunTierAware()` (returns `hardCapUsd = credits × $5 × 1.5` in the estimate), but no code path reads it as a runtime ceiling. A worker stuck in a loop today can burn past the cap.

**Files.**
- `src/main/java/com/navneet/executespec/v2/agent/WorkerAgent.java` — at top of each iteration, read `RunCostTracker.snapshot(runId).costUsd()` vs `pipelineRun.hardCapUsd`. If `current >= cap × 0.9`, inject a "PROGRESS CHECK — at 90% of cost cap" system message. If `current >= cap × 1.0`, force-terminate with `outcome=COMPLETED_BY_COST_CAP`.
- `src/main/java/com/navneet/executespec/v2/cost/RunCostTracker.java` — add `currentSpend(runId)` helper.
- `src/main/java/com/navneet/executespec/v2/integration/RunOrchestrator.java` — propagate cap from `pipelineRun.hardCapUsd` into the `LlmRequest` context so reviewers also respect it.
- New test: `WorkerAgentV2HardCapTest` — pin force-terminate at threshold + warning at 90%.

**Acceptance.**
- [ ] Synthetic test: spec with `creditsCharged=1` (`hardCapUsd=$7.5`); worker loops mock-billing $1/iter; at iter 8, `outcome=COMPLETED_BY_COST_CAP`.
- [ ] Test passes; existing 881 tests still green.
- [ ] Run a real LOW spec on dev; confirm no regression.

**Effort.** 3 days. **Owner.** Backend/AI.

**Dependencies.** P0.2 (worker-runaway merged so the new outcome enum can chain off existing terminal-state plumbing).

---

### P3.2 — UI cost-cap progress + "Stop at $X" toggle

**Why.** Audit Risk #6: customers will dispute a $20 charge if they can't see it coming. The plan-approval gate already shows an estimate; what's missing is a live progress bar + a hard ceiling toggle.

**Files.**
- `frontend/src/components/runs/RunDetailTabs.tsx` — `CostProgressBar` component, polls `/api/runs/{id}/cost` every 5s (already exists in v2.5.13), renders bar from 0 → `hardCapUsd`. Amber at 80%, red at 100%.
- `frontend/src/components/modals/NewRunModal.tsx` — add "Stop at $X" input field (defaults to estimate × 1.5, user can lower it).
- Backend: `RunRequest.userCostCap` — when set, overrides `hardCapUsd` in the pipeline.

**Acceptance.**
- [ ] Submit a run with `userCostCap=$2.00`; observe pipeline terminate at $2 (not the system cap).
- [ ] Bar renders correctly on a live run (manual visual check).
- [ ] Tooltip explains the cap in plain English.

**Effort.** 4 days. **Owner.** Frontend + Backend (1 day backend).

**Dependencies.** P3.1.

---

### P3.3 — Pass v2-eval HIGH band 100% across all 3 providers

**Why.** Audit Risk #3: today HIGH-band v2-eval = 100% TIMEOUT at 900s on Anthropic, OpenAI, and Gemini. P0.3 lands the architectural fix; P3.3 *verifies* it.

**Files (test infrastructure only).**
- `scripts/v2-eval-strict.sh` — already exists; ensure it runs HIGH band fully.
- `scripts/v2-eval-specs/high.txt` — the spec used. Verify it's representative.
- New CI workflow: `.github/workflows/v2-eval-nightly.yml` — runs HIGH/MEDIUM/LOW × 3 providers nightly, posts JSON + markdown to a dev-only Slack channel.

**Acceptance.**
- [ ] Run full v2-eval on dev: 9 cells (LOW × MEDIUM × HIGH × Anthropic/OpenAI/Gemini), all reach a terminal status in <600s p95, all return `verifiedScore >= 80`.
- [ ] Nightly workflow green for 7 consecutive nights before exiting Phase 3.

**Effort.** 2 days (workflow setup + first soak). **Owner.** Backend/AI.

**Dependencies.** P0.3 (hierarchical planner merged) + P1.2 (verifiedScore reliable).

---

### P3 — Phase exit criteria

- [ ] No run on dev exceeds `min(userCostCap, hardCapUsd)`.
- [ ] v2-eval HIGH band: 0% timeout, p95 <600s.
- [ ] UI cost progress bar live on every running run.

---

## 7. Phase 4 — Observability + multi-tenant security (Weeks 7-9)

**Audit Risk #5 + Operations score 5/10.** Closes the "any controller could leak across tenants" class and gives us SLOs we can defend.

### P4.1 — ArchUnit pin: every run/project endpoint calls a guard

**Why.** Audit §security: `RunAccessGuard` works, but every controller has to *remember* to call it. One missing call = tenant leak. ArchUnit pins it at compile time.

**Files.**
- `src/test/java/com/navneet/executespec/arch/AccessGuardCoverageTest.java` — new test.
- Bytecode scan: every method annotated with `@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@PatchMapping` whose return type is `RunDetail`, `RunSummary`, `Project*Dto`, `ValidationReport*`, or any DTO containing a `runId`/`projectId` field MUST call `RunAccessGuard.requireRunAccess` or `ProjectAccessGuard.assertCanRead` somewhere in its body.
- Allowlist: public endpoints (`/api/shared/**`, `/api/invites/preview`, `/webhooks/**`).

**Acceptance.**
- [ ] Test fails today on at least 0 sites (verify by reverting one guard call temporarily).
- [ ] Test passes after audit + adding any missing guard calls.
- [ ] Allowlist documented inline.

**Effort.** 3 days. **Owner.** Backend/AI.

---

### P4.2 — SLO definitions + Grafana alert rules

**Why.** Audit §operations: no SLOs documented. Without SLOs, we can't honestly answer "is the product working" at GA.

**Files.**
- New doc: `docs/operations/slos.md` — defines:
  - `run_terminal_in_15min` ≥ 95% (target), 99.5% (stretch)
  - `verified_score_correct` ≥ 90% (verified via random-sample manual review)
  - `cost_estimate_within_30%` ≥ 80% (estimate vs actual on `run_cost_log`)
  - `webhook_processed_in_60s` ≥ 99%
  - `hot-path 5xx rate` < 0.5%
- New alert rules: `observability/grafana/alerts/*.yml` — wire to PagerDuty (cheap tier, $20/mo) or OpsGenie.
- `observability/grafana/dashboards/executespec-slos.json` — single dashboard, per-SLO burndown chart.

**Acceptance.**
- [ ] All 5 SLOs defined with formula, target, alert threshold.
- [ ] Alerts page in Grafana renders the 5 burndowns.
- [ ] At least 2 alerts fire on a synthetic incident (kill a Cloud Run instance; observe `hot-path 5xx` alert; restore).
- [ ] On-call rotation set up in PagerDuty (founders rotate weekly until headcount supports).

**Effort.** 5 days. **Owner.** Backend/AI + Full-stack (1 day on dashboards).

---

### P4.3 — Public status page + customer-visible incident comms

**Why.** Audit §operations: no `status.executespec.ai`. Required for ENTERPRISE deals that ask "where's your status page" in due diligence.

**Files.**
- Set up `status.executespec.ai` on Better Stack ($25/mo) or Statuspage.io.
- 5 monitored components: API (`https://api.executespec.ai/actuator/health`), Frontend (`https://app.executespec.ai`), GitHub webhook ingest, Stripe webhook ingest, LLM provider availability (Anthropic, OpenAI, Gemini — synthetic probes).
- Add `/status` link to the marketing site footer + the in-app sidebar.

**Acceptance.**
- [ ] Status page live with all 5 components.
- [ ] Synthetic incident creates an entry that's visible publicly.
- [ ] Status page links from website + app.

**Effort.** 2 days. **Owner.** Full-stack.

---

### P4 — Phase exit criteria

- [ ] ArchUnit guard coverage test green.
- [ ] All 5 SLOs visible in Grafana; alerts rules wired.
- [ ] Status page live, linked, with valid synthetic probes.
- [ ] On-call rotation in PagerDuty or OpsGenie.

### P4.1-followup — Migrate 37 legacy guard sites to canonical RunAccessGuard / ProjectAccessGuard

When the `AccessGuardCoverageTest` was first authored on 2026-05-06, it
discovered 37 controller methods in the codebase that handle run / project /
execution ids from the URL but don't call any tenant access guard. These
were captured in the test's `KNOWN_LEGACY_DRIFT` set as documented tech
debt. Adding guards to all 37 in one batch is risky (each path needs
controller-specific test coverage); doing it incrementally is the safer path.

**Migration order (from highest tenant-leak risk to lowest):**

1. **`PullRequestController.listPrsForRun`** + **`PullRequestController.refreshProjectPrs`**
   — exposes PR records cross-org. Highest leak risk in the set.
   *Add `RunAccessGuard.requireRunAccess` / `ProjectAccessGuard.assertCanRead`.*
2. **`TestResultController.applyTestFixes`** + **`TestResultController.runTests`**
   — mutates run state across orgs.
   *Add `RunAccessGuard.requireRunAccess`.*
3. **`V2RunController.poll`** — V2 run polling endpoint.
   *Add `RunAccessGuard.requireRunAccess`.*
4. **`ProjectExportController.export`** — full project state in one ZIP.
   *Add `ProjectAccessGuard.assertCanRead`.*
5. **`ProjectController.{addMember, removeMember, archiveProject, updateSettings}`**
   — modifies access controls directly. Need `assertCanWrite`.
6. **`ProjectController.{getProject, listMembers}`** — read paths.
   Need `assertCanRead`.
7. **`ProjectDocumentController.{list, upload, delete}`** — RAG documents.
8. **`SpecTemplateController.{listForProject, createForProject, delete}`**.
9. **`CodeReviewController.{listForProject, latest}`**, **`PrReviewController.listForProject`**.
10. **`ActivityFeedController.list`**, **`AdrController.{list, get}`**,
    **`InvariantController.{list, recentViolations}`**,
    **`StandardsComplianceController.report`** — all need `assertCanRead`.
11. **`ProjectRepoController.*`** — repo-scoped, needs guard on the project
    that owns the repo OR org-scope check for ORG-scoped repos.
12. **`SuperAdminController.*`** + **`ExecutespecMigrationController.migrate`**
    — already SUPER_ADMIN-gated by SecurityConfig; just consolidate on a
    canonical `requireSuperAdmin` call for review-time clarity.

**Acceptance per migration:**
- Guard call added at top of method body.
- Drop the entry from `KNOWN_LEGACY_DRIFT`.
- Existing controller integration tests still green.
- New test pinning the 403 case (caller in different org gets 403).

**Effort.** ~1 day per controller × 14 controllers = ~14 person-days.
Schedule as background work during P5 (beta partner support) — no blocker
for GA cutover, but should be done by Week 12.

---

## 8. Phase 5 — Beta program + SOC 2 Type I + CLI (Weeks 8-12)

This is the *outcome* phase. Quality + cost + pricing have all landed; now we need 25 real customers using the product, a CLI for spec-as-file workflow, and SOC 2 Type I in audit so ENTERPRISE conversations don't die at procurement.

### P5.1 — Beta partner program (25 invited customers)

**Why.** Audit §plan: "Push 25 invited customers to merge ≥3 PRs each → 75 PR data points". Real testimonials beat any number of internal evals.

**Process.**
1. Founder outreach: warm intro to 50 candidates from existing network. Filter to 25 who match the "tech lead at small company" persona.
2. Onboarding pack: 30-min Zoom + a 2-page Notion doc walking through (a) repo registration, (b) first multi-repo spec, (c) reading the validation report, (d) merging the PR.
3. Weekly Friday cohort call (60 min) — partners demo what they shipped + flag bugs. Founders do the demo first to set the bar.
4. Slack workspace for partners. Channel: `#bug-reports`, `#feature-requests`, `#general`. Founders reply within 4 business hours.
5. Founder pricing locked: 50% off lifetime (P2.4), no contract pressure.

**Acceptance.**
- [ ] 25 partners signed up by end of Week 9.
- [ ] By end of Week 11: ≥20 partners have merged ≥3 PRs each (60 PR floor).
- [ ] By end of Week 12: ≥10 written testimonials collected; ≥3 video testimonials recorded.

**Effort.** 0.5 FTE for 5 weeks (one founder dedicates half their time). **Owner.** Founder.

---

### P5.2 — CLI on npm (`@executespec/cli`)

**Why.** Audit §DevX: every competitor ships a CLI. Spec-as-file (`executespec.yml`) is the killer workflow that lives natively in CI/CD pipelines.

**Files.**
- New repo: `executespec-cli` (or under `cli/` in this repo if monorepo preferred).
- Commands:
  - `executespec login` — opens browser for OAuth, persists JWT to `~/.executespec/credentials.json`.
  - `executespec submit <path>` — uploads spec, polls run, streams events.
  - `executespec status <runId>` — fetches run status.
  - `executespec report <runId>` — downloads validation report markdown.
  - `executespec init` — creates `.executespec.yml` in cwd.
- Publish to npm under `@executespec/cli`.

**Acceptance.**
- [ ] `npm install -g @executespec/cli` succeeds.
- [ ] `executespec submit spec.md` from a fresh terminal works E2E against dev.
- [ ] CI integration test: GitHub Action that runs `executespec submit` on a spec file change.
- [ ] Documentation: `docs/cli.md` + linked from marketing site.

**Effort.** 8 days. **Owner.** Full-stack.

**Dependencies.** P0.2 (clean run path), P1.x (trustworthy reports), P2.1 (SOLO tier so CLI users can subscribe).

---

### P5.3 — SOC 2 Type I audit kickoff

**Why.** Audit §compliance: "blocks ENTERPRISE deals through procurement". Type I is achievable in 90 days; Type II takes 6+ months and is post-GA.

**Process.**
1. Engage Drata or Vanta ($1K-2K/mo) for evidence collection automation.
2. Hire fractional vCISO from Drata's network or similar (~$5-10K/mo for the audit window).
3. SOC 2 Type I auditor engagement: AICPA-registered firm; budget $15-30K for Type I.
4. Evidence categories: access control, change management, encryption at rest + in transit, vendor management, incident response, business continuity.
5. Target: report issued by end of Week 12.

**Files (technical evidence to provide).**
- Existing: `activity_audit` table (V22) + hash chain (V147). Solid.
- New: `docs/security/policies/` — 5 policies (Access Control, Acceptable Use, Incident Response, Change Management, Data Retention). Drata templates work.
- New: encryption-at-rest column converter audit. Pin via `EncryptionAuditTest` ArchUnit test.

**Acceptance.**
- [ ] Drata/Vanta wired and pulling evidence automatically.
- [ ] All 5 policies published.
- [ ] Encryption audit passes.
- [ ] Type I report issued by end of Week 12.

**Effort.** 0.25 FTE for 5 weeks (one founder coordinates) + ~$25K external. **Owner.** Founder + vCISO.

---

### P5.4 — DPA + GDPR data-export endpoint

**Why.** Same audit gap. Required for any EU customer on TEAM or ENTERPRISE.

**Files.**
- `docs/legal/dpa-template.md` — Data Processing Agreement template, reviewable by customer's counsel.
- New endpoint: `GET /api/auth/me/data-export` — returns ZIP of every row tied to the user across `app_user`, `org`, `project`, `pipeline_run`, `agent_task`, `validation_report`, `notification`, `activity_audit`. Async (the export takes >30s).

**Acceptance.**
- [ ] DPA published at `/legal/dpa`.
- [ ] Data-export ZIP includes all 8+ tables.
- [ ] Endpoint covered by `RunAccessGuard`-style guard.

**Effort.** 4 days. **Owner.** Backend/AI.

---

### P5 — Phase exit criteria

- [ ] ≥20 of 25 beta partners shipped ≥3 PRs each.
- [ ] CLI on npm with ≥50 weekly downloads.
- [ ] SOC 2 Type I report issued.
- [ ] DPA template published, GDPR data-export endpoint live.

---

## 9. Phase 6 — GA cutover (Week 13)

**Single goal.** Flip the public switch. Marketing site goes from "Request access" to "Sign up". Stripe production prices live. Public Hacker News + Product Hunt launch.

### P6.1 — Marketing copy refresh

**Files.**
- `website/index.html` — hero copy: "Autonomous multi-repo agent for tech leads at small companies". Drop "early access" + "request invite" wording.
- Pricing page: 4-tier ladder with SOLO highlighted as the conversion target.
- Add testimonials section (from P5.1).

**Effort.** 2 days. **Owner.** Frontend.

---

### P6.2 — Stripe production prices live; founder pricing window closed

**Files.**
- Verify `STRIPE_*_PRICE_ID` env vars set on prod.
- Check founder counter (P2.4); decide whether to leave open ("first 100 lifetime") or close.

**Effort.** 0.5 day. **Owner.** Backend/AI.

---

### P6.3 — Public launch

**Process.**
- HN launch: founder posts at 6am PT Tuesday. Title focused on the multi-repo niche, not generic "AI agent" framing.
- Product Hunt launch: 12am PT Tuesday. Use the existing `website/product-hunt-launch.md` kit (created 2026-04-21).
- Status page link prominent on marketing site.
- On-call team active for the launch week.

**Acceptance.**
- [ ] HN/PH launches happen.
- [ ] No P0 incidents in launch week.
- [ ] First 100 SOLO conversions within 48h.

**Effort.** 1 day execution + on-call. **Owner.** Founder + all engineers on standby.

---

## 10. Risk register (post-plan)

| # | Risk | P × I | Mitigation in this plan |
|---|---|---|---|
| 1 | P1.1 read-budget fix doesn't fully close the gap | M × H | P1.2 decouples `verifiedScore` so even a partial P1.1 fix yields a trustworthy number. |
| 2 | Hierarchical planner merge breaks legacy paths | L × H | P0.3 includes a single-spec verification on dev before declaring done; circuit breaker auto-falls-back. |
| 3 | Founder pricing exhausts before beta program ends | M × M | P5.1 onboarding paced at 5/week × 5 weeks = 25; founder counter has 75 spare slots. |
| 4 | SOC 2 Type I slips past Week 12 | M × M | P5.3 starts in Week 8 to give 5-week buffer; if it slips, GA cutover proceeds without it (Type I unblocks ENTERPRISE, not SELF-SERVE). |
| 5 | Anthropic Sonnet 4.6 deprecation mid-window | L × H | `model-matrix` is YAML-driven; bump in <4 hours. v2-eval nightly catches drift. |
| 6 | Beta partner churn during the program | M × M | Founder pricing + weekly demo calls; 5 churn buffer (target 25, accept 20). |
| 7 | Cost-cap UI bug exposes a runaway customer to a real bill | L × H | P3.1 enforces server-side regardless of UI state; UI is informational. |
| 8 | Multi-tenant guard test misses a controller | L × C | P4.1 ArchUnit catches at compile time; nightly CI runs full test suite. |
| 9 | CLI auth flow leaks JWT | L × H | OAuth device flow (no localhost callback); JWT scoped to 1h, refresh stored encrypted. |
| 10 | GA launch coincides with a quality regression | L × C | Friday-only deploys to prod for 4 weeks pre-GA; freeze 72h before launch. |

---

## 11. Cutover criteria for GA (Week 13 gate)

All of these must be GREEN to ship GA:

- [ ] Dev `avgScore` ≥75 sustained for ≥30 days.
- [ ] v2-eval nightly green for ≥7 consecutive nights across all 9 cells.
- [ ] Zero cost-cap exceptions in the prior 30 days on real customer runs.
- [ ] All 5 SLOs in Grafana within target for prior 30 days.
- [ ] Beta partner satisfaction ≥4.0/5 (informal survey at end of Week 12).
- [ ] CLI weekly downloads ≥50.
- [ ] SOC 2 Type I report issued OR explicit founder decision to ship without it (ENTERPRISE-only block).
- [ ] Status page live with ≥7 days of green data.
- [ ] On-call rotation tested with one synthetic incident drill.
- [ ] No P0/P1 bugs open against any 2026-05+ commit.

If any item is RED, push GA by 1 week and re-evaluate. Maximum slip: 2 weeks (target 2026-08-04, hard cap 2026-08-18).

---

## 12. Out-of-scope (deliberately not in this plan)

These were considered and explicitly punted to post-GA:

- **SOC 2 Type II** — 6+ months, ship Type I in window, Type II becomes a Q4 2026 project.
- **Native Android (Kotlin + Compose)** — RN+Expo path is sufficient for v1; native is a 2027 conversation.
- **GitLab / Bitbucket integration** — GitHub-only at GA; broaden after PMF signal.
- **V2 batch execution** — single-task and multi-task multi-repo cover real use cases; batch is an optimisation.
- **IDE plugin** — CLI yes, IDE no. The CLI is the universal entry point; IDE is a 2027 conversation when we have a target persona to design around.
- **Figma integration v2** — 22 files / 2400 LOC pending; not on the GA critical path. Re-evaluate after 25 beta partners report whether they actually need it.
- **Customer-success runbook + dedicated CSM** — founders own customer success through GA. Hire after first $200K ARR.

---

## 13. Telemetry to watch weekly

These metrics get a 1-line review every Monday morning:

| Metric | Source | Target | Alert threshold |
|---|---|---|---|
| `avg verified_score (last 7d)` | `validation_report` | ≥75 | <70 for 3 consecutive days |
| `runs reaching terminal in <15min` | `pipeline_run` | ≥95% | <90% for 24h |
| `cost estimate vs actual delta` | `run_cost_log` | within 30% on 80% of runs | >50% delta on 20% of runs |
| `runs hitting cost cap` | `pipeline_run.terminal_status_reason` | <2% | >5% in 24h |
| `webhook processing p95` | `webhook_event` | <60s | >120s |
| `5xx rate hot path` | Grafana | <0.5% | >2% sustained 10min |
| `beta partner DAU` | event_tracking | ≥15 / 25 daily | <10 for 3 days |
| `beta partner PRs merged this week` | `pull_request_record` | ≥5 / week aggregate | 0 for 7 days |

---

## 14. Open questions for founder review

1. **Headcount.** Plan assumes 3 engineers. If actual is 2, P5.2 (CLI) and P5.4 (DPA) move to post-GA.
2. **Budget.** ~$30K external (SOC 2 audit + Drata + status page + PagerDuty). Confirm authorised.
3. **Founder pricing maths.** 50% off lifetime for 100 customers = 100 × $14.50 × 12 = $17.4K/year ARR floor. Acceptable?
4. **Public commitment.** Plan B requires saying "GA in Q3 2026" publicly. Comfortable?
5. **CSM hire trigger.** Plan assumes "first $200K ARR". If beta partners convert to TEAM faster, may need CSM in Phase 5 itself.
6. **Sonnet 4.6 risk.** If Anthropic deprecates mid-plan, do we add an extra 2-week buffer or absorb into existing slack?

---

## Appendix — Effort summary

| Phase | Work | Person-days |
|---|---|---|
| P0 | Land in-flight branches | 3.5 |
| P1 | Quality scoring fix | 9 |
| P2 | Pricing ladder rewrite | 14 |
| P3 | Cost ceiling + HIGH-band reliability | 9 |
| P4 | Observability + multi-tenant security | 10 |
| P5 | Beta program + SOC 2 Type I + CLI + DPA | 35 (incl. 0.75 FTE founder time) |
| P6 | GA cutover | 3.5 |
| **Total** | | **~84 person-days** |

Distributed across 13 weeks × 3 engineers × 5 days = 195 capacity person-days. **43% utilisation on plan items**, leaving 57% for bugs, beta partner support, and unforeseen work. That's the right ratio for a pre-GA team.

---

> **Last refresh:** 2026-05-05 (initial draft).
> **Refresh trigger:** end of each phase or when any cutover criterion goes RED.
> **Plan status:** PROPOSED — awaiting founder approval before kickoff.
