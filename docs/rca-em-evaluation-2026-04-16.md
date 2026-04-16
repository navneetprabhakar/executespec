# Deep-Dive RCA — Senior EM Evaluation Findings

> Version: v2.5.7 · Date: 2026-04-16 · Scope: root-cause analysis of the 10 distinct issues surfaced by the EM-perspective evaluation, each traced to specific code locations with a minimal-fix description.

---

## Table of Contents

1. [P0 — CRITICAL: Cost / token aggregation is disconnected](#p0-cost-aggregation)
2. [P0 — CRITICAL: Plan name inconsistency (two sources of truth)](#p0-plan-inconsistency)
3. [P0 — CRITICAL: V2 runs never fire notifications](#p0-notifications)
4. [P1 — HIGH: Run card + billing page lack $ spent](#p1-run-cost-display)
5. [P1 — HIGH: Feature-flag UI not gated in frontend](#p1-feature-flag-gating)
6. [P1 — HIGH: PARTIAL + FINAL BUILD PASSED contradiction](#p1-status-contradiction)
7. [P1 — HIGH: Overview tab shows V1 pipeline on V2 runs](#p1-v1-pipeline-v2)
8. [P2 — MEDIUM: No per-engineer / per-project cost attribution](#p2-cost-attribution)
9. [P2 — MEDIUM: Historical ExecutionRun totals never backfilled](#p2-historical-backfill)
10. [P2 — MEDIUM: No budget caps / overage alerts / usage enforcement](#p2-budget-caps)

---

<a name="p0-cost-aggregation"></a>
## 1. P0 — Cost / token aggregation disconnected (project dashboard shows `—`)

**Symptom.** `/projects/{id}/dashboard` shows `TOKENS: —` and `COST: —` even after multiple Launches with 2M+ tokens consumed.

**Root cause.** `ProjectDashboardController` aggregates from `pipeline_run` ONLY, never from `execution_run`:

```
src/main/java/com/navneet/executespec/project/dashboard/ProjectDashboardController.java:82-91
  findByProjectIdOrderByStartedAtDesc(...)    ← pipeline_run only
  sum(totalInputTokens + totalOutputTokens)   ← pipeline_run only
  sum(actualCostUsd)                          ← pipeline_run only
```

The v2.5.7 fix (yesterday) correctly writes to `ExecutionRun.totalActualTokens` / `totalCostUsd` via `SolutionExecutionService.accumulateExecutionRunTotals()` — **but no UI surface reads from it**. The dashboard DTO was designed before ExecutionView existed and the integration was never landed.

**Should do.** Extend `ProjectDashboardController` to also query `ExecutionRunRepository.findByProjectId(...)` and sum `totalActualTokens` + `totalCostUsd` into the dashboard totals.

**Minimal fix.** ~40 LoC: add `execRunRepo.findByProjectId(...)` call; sum alongside pipeline_run totals; update `DashboardDto.totalTokens/totalCost` computation.

---

<a name="p0-plan-inconsistency"></a>
## 2. P0 — Plan name inconsistency (Settings says "Enterprise", Billing says "Pro Monthly INR")

**Symptom.** Settings page shows "Enterprise Plan · Unlimited runs, priority support" while Billing page shows "Pro Monthly INR (Managed Keys)" for the same user, same session.

**Root cause.** Two independent data sources that have drifted:

| Surface | Source | File |
|---|---|---|
| Settings | JWT claim `user.tier` | `SettingsPage.tsx:281` → `user?.tier === 'ENTERPRISE'` |
| Billing | `BillingSubscription.billingPlanId` → `BillingPlan.name` | `BillingService.java:369` (`planRepo.findById(sub.getBillingPlanId())`) |

When a user's tier is promoted manually (e.g., via super admin), `AppUser.tier` is set but the `BillingSubscription` row is untouched. Billing keeps the old plan. Settings reads the new tier. **Neither page fetches the other source as a consistency cross-check.**

**Should do.** Billing is the source of truth. Settings should call `fetchBillingStatus()` and render `plan.name` from that — or the backend should guarantee that `AppUser.tier` matches `BillingPlan.tier` via a DB trigger / `@PostUpdate` listener.

**Minimal fix.** ~20 LoC frontend: replace the hardcoded tier→name mapping in `SettingsPage.tsx:280-291` with a `billingStatus?.plan?.name` read (already fetched on the Billing page — trivially hoisted). Follow-up backend fix: update `AppUser.tier` inside the same transaction that writes `BillingSubscription` on subscribe/cancel.

---

<a name="p0-notifications"></a>
## 3. P0 — V2 runs never fire notifications

**Symptom.** Notification bell shows "No notifications yet" even when runs have completed successfully.

**Root cause.** `RunCompletionNotifier` subscribes to Spring `@EventListener(PipelineEvent.RunCompleted)`. The V1 `PipelineHarness` publishes this event on completion. **V2 `V2RunOrchestrator.terminate()` (line 950) only broadcasts SSE events — it does NOT publish the Spring ApplicationEvent**:

```
V2RunOrchestrator.java:947-953
  runRepo.save(r);                                       // DB status set
  broadcaster.emit(runId, RunEvent.runStatus(...));      // SSE only
  // ← MISSING: eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
```

Since every Launch's underlying solution is a V2 run, and since all new standalone runs are V2, **the entire V2 codebase bypasses the notifier.**

**Should do.** Emit Spring ApplicationEvent alongside the SSE broadcast so listeners (Slack, email, in-app) fire consistently.

**Minimal fix.** ~15 LoC: inject `ApplicationEventPublisher` into `V2RunOrchestrator`; in `terminate()`, after the SSE emit, branch on `finalStatus` and call `publishEvent(new PipelineEvent.RunCompleted(runId))` or `RunFailed(...)`.

---

<a name="p1-run-cost-display"></a>
## 4. P1 — Run card and billing page lack $ spent

**Symptom.** Run card shows `1.3M tok` but no $. Billing page shows `Runs 243 / 10` but no $ spent this period.

**Root cause.**

**(a) RunSummary DTO omits cost field.**

```
src/main/java/com/navneet/executespec/api/RunMapper.java:34-65   — toSummary() maps 14 fields, omits actualCostUsd
frontend/src/lib/api.ts:81-122                                    — RunSummary interface has no actualCostUsd
frontend/src/components/runs/RunCard.tsx:130-134                  — renders fmtTokens() only
```

The decision was "RunSummary is a list payload — keep it thin" — but the cost field is an 8-byte `Double`. Trivial to add.

**(b) BillingStatusResponse is quota-based, not consumption-based.**

```
src/main/java/com/navneet/executespec/billing/service/BillingService.java:364-433
  BillingStatusResponse = { plan, usage: [RUNS, PROJECTS, STORAGE_MB] }
  // ← No spentUsd field, no overage calculation, no per-model cost
```

The billing system tracks "you used 243 runs" but never computes "you owe $X for overages" because run quotas are hard caps, not metered thresholds. But the dashboard should at minimum show $ actually spent by LLM.

**Should do.** Add `actualCostUsd` to `RunSummary` + render on `RunCard`. Add `spentUsd` to `BillingStatusResponse` computed as `SUM(pipeline_run.actualCostUsd + execution_run.totalCostUsd) WHERE createdAt >= billingPeriodStart`.

**Minimal fix.** ~30 LoC backend + ~10 LoC frontend.

---

<a name="p1-feature-flag-gating"></a>
## 5. P1 — Feature-flag UI not gated in frontend (silent-failure trap)

**Symptom.** `/integrations` page shows "Configure" buttons on Jira/Slack/Confluence even though `/admin/feature-flags` shows `integrations.jira.enabled=FALSE` at PLATFORM scope. A user clicks Configure, fills a form, and gets a cryptic 403 from the backend.

**Root cause.**

```
src/main/java/com/navneet/executespec/config/featureflag/FeatureFlagController.java:21-40
  @GetMapping("/api/admin/feature-flags")   // SUPER_ADMIN only
  // ← No public endpoint for the frontend to check flags pre-flight

frontend/src/pages/IntegrationsPage.tsx:17-25
  INTEGRATIONS = [...]  // All 7 hardcoded with configurable: true
  // ← never fetches any flag state
```

The backend controllers DO check flags (e.g., `V2RunOrchestrator.java:631` reads `featureFlagService.isEnabled(...)`) — so the enforcement works. But the frontend UI gives the user no signal that the flag is off, leading to wasted form-fills and confusing errors.

**Should do.** Expose a `/api/feature-flags/public` endpoint scoped to the caller (returns flags visible at their role level). Frontend fetches on mount, gates "Configure" buttons, and shows a tooltip "Disabled by platform operator" when off.

**Minimal fix.** ~25 LoC backend (new controller method + scope filter) + ~20 LoC frontend (fetch + gate).

---

<a name="p1-status-contradiction"></a>
## 6. P1 — PARTIAL + FINAL BUILD PASSED contradiction

**Symptom.** A completed run header shows `PARTIAL` (orange alarm) + `✓ FINAL BUILD PASSED` (green reassurance) simultaneously.

**Root cause.**

```
src/main/java/com/navneet/executespec/v2/integration/V2RunOrchestrator.java:1377-1384
  mapOverallToV1Status(overall):
    ALL_SUCCESS            → "COMPLETE"
    PARTIAL_WITH_WARNINGS  → "PARTIAL"   ← even if final-build validator passed
    FAILED                 → "FAILED"

frontend/src/components/runs/RunDetail.tsx:328-335
  {activeRun.status === 'PARTIAL' && finalBuildPassed(activeRun) && (
      <Chip green>✓ FINAL BUILD PASSED</Chip>   ← rendered alongside orange PARTIAL
  )}
```

V2 distinguishes "some task-level smoke tests failed" (→ PARTIAL) from "the final reviewer build validator passed" (→ `reviewJson.finalBuildPassed=true`). These are two legitimately different quality signals but the UI conflates them into a single status-row that visually contradicts itself.

**Should do.** Pick one of two options:
- **(a)** Adjust `mapOverallToV1Status` to promote `PARTIAL_WITH_WARNINGS → COMPLETE_WITH_WARNINGS` when final build passes. Render as green-amber "completed with N warnings".
- **(b)** Keep PARTIAL status but move the FINAL BUILD chip into a separate "Quality signals" panel (not the header) to avoid visual contradiction.

**Minimal fix (a).** ~10 LoC backend (status mapping branch) + ~10 LoC frontend (new status constant + styling).

---

<a name="p1-v1-pipeline-v2"></a>
## 7. P1 — Overview tab shows V1 pipeline state-machine on V2 runs

**Symptom.** Overview tab on a completed V2 run shows empty circles for QUEUED / PLANNING / AWAITING_APPROVAL / WORKING / COMPILING / CODE_GENERATED — all unchecked even though the run is COMPLETE.

**Root cause.**

```
frontend/src/lib/constants.ts:37-40
  PIPELINE_STEPS = ['QUEUED','PLANNING','AWAITING_APPROVAL','WORKING','COMPILING','COMPLETE']
  // ← Hardcoded V1 phase list

frontend/src/components/runs/RunDetailTabs.tsx:527-564
  OverviewTab renders PIPELINE_STEPS regardless of harnessVersion
  // ← V2 skips AWAITING_APPROVAL and COMPILING entirely
```

V2's actual phase sequence is `QUEUED → PLANNING → WORKING → REVIEWING → COMPLETE` (no AWAITING_APPROVAL in PROTOTYPE mode, no COMPILING ever). The hardcoded V1 list means V2 runs always look half-done on the Overview tab, contradicting the header stepper which correctly shows checkmarks.

**Should do.** Either branch on `run.harnessVersion` and render a V2-appropriate phase list, OR hide the state-machine widget entirely on V2 runs (the header stepper already covers it).

**Minimal fix.** ~15 LoC: conditional render based on `run.harnessVersion === 'v2'`.

---

<a name="p2-cost-attribution"></a>
## 8. P2 — No per-engineer / per-project cost attribution

**Symptom.** Managers cannot see "Alice spent $120, Bob spent $80 this month" or "Project X burned $300 this week". Critical for 10-engineer team FinOps.

**Root cause.**

```
src/main/java/com/navneet/executespec/billing/cost/RunCostLog.java:24-89
  columns: user_id, org_id, actual_cost   ← has user_id and org_id
  // ← MISSING: project_id column

src/main/java/com/navneet/executespec/billing/service/AnalyticsService.java
  getMemberUsage()   → per-user RUN COUNTS only, no cost
  getUsageMetrics()  → project-grouped counts only, no cost
  // ← No getCostByUser() or getCostByProject() methods
```

`RunCostLog` is populated on every run completion (see `V2RunOrchestrator.writeRunCostLog`) but no analytics code queries it. Aggregation uses `model_task_metric` duration-based approximation, not actual spend.

**Should do.** Add `project_id` column to `run_cost_log`; add `AnalyticsService.getCostByUser(orgId, periodDays)` + `.getCostByProject(orgId, periodDays)`; expose at `GET /api/admin/analytics/cost-breakdown` and render in admin analytics dashboard.

**Minimal fix.** ~1 Flyway migration + ~80 LoC backend + ~120 LoC frontend (new admin page section).

---

<a name="p2-historical-backfill"></a>
## 9. P2 — Historical ExecutionRun totals never backfilled

**Symptom.** ExecutionRuns that completed BEFORE the v2.5.7 `accumulateExecutionRunTotals` fix still show `totalActualTokens=0` and `totalCostUsd=0` on the project dashboard.

**Root cause.** The v2.5.7 fix only runs in the live path when a new solution completes. Old ExecutionRun rows already have null/zero totals and nothing retroactively updates them.

**Should do.** One-time backfill: on app startup (or via a SUPER_ADMIN endpoint), scan `execution_run` rows where `total_actual_tokens=0`; for each, sum its child `execution_solution.actual_tokens` and `cost_usd`; persist.

**Minimal fix.** ~40 LoC: `@PostConstruct` on a Spring `@Component` with a conditional `@Value("${executespec.backfill.exec-totals.enabled:true}")`; run the JPQL sum query once; log the count of rows updated.

---

<a name="p2-budget-caps"></a>
## 10. P2 — No budget caps / overage alerts / usage enforcement

**Symptom.** Billing page shows `Runs 243 / 10` (2330% over limit) with no block, no alert, no upgrade prompt. Users cannot set per-user or per-project budget caps.

**Root cause.** Run quotas are "advisory" in the DB: `BillingPlan.runLimit` is compared against `AppUser.personalRunCount` for display only. Tier enforcement on `submitRun` rejects when over quota (`TierEnforcementService.assertAndIncrementRunCount`) but the UI never surfaces "you're about to hit your limit" or "you've exceeded and owe $X".

**Should do.**
- Add `BudgetCap` entity: per-user or per-project monthly $ threshold.
- Nightly batch job computes spend-to-date against caps; emits in-app notifications at 50% / 80% / 100%.
- `submitRun` checks projected cost against remaining cap budget; rejects with 402 Payment Required + clear "raise the cap" link if over.
- Billing page renders "$ spent this month" with progress bar vs. cap.

**Minimal fix.** Non-trivial: new entity + migration + job + notification wiring + frontend display. Estimate ~300 LoC across backend + frontend. Ships as a separate 3-day feature.

---

## Cross-cutting architectural root cause

Every P0 and most P1 findings trace to **one architectural gap**: `ExecutionRun` (the Launch model) was added to the system in v2.4.0 but the surrounding infrastructure — notifications, billing, dashboard aggregation, run-card display — was written for `PipelineRun` (the V2 run model) and has not been updated to treat ExecutionRun as a first-class run type.

A **single unified `Run` interface** that abstracts over both `PipelineRun` and `ExecutionRun` (or a `RunView` projection materialised via a DB view) would collapse 4 of the 10 findings into one fix. This is the right long-term refactor; in the short term, targeted fixes per surface are the practical path.
