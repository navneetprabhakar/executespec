# Removing PARTIAL as a Run Status

> Status: **design draft** — 2026-04-19. Frames the scope for replacing the
> `PARTIAL` terminal status with a pair of richer signals. Not scheduled;
> proposal only, waiting on user review.

## The problem

`PARTIAL` has become a dumping ground. Today it covers:

| What actually happened | Reason tag | What users see |
|---|---|---|
| Reviewer pass timed out → final build skipped | `FINAL_BUILD_FAILED` | "Partial · FINAL_BUILD_FAILED" |
| Reviewer returned UNRESOLVED_ISSUES | `REVIEWER_UNRESOLVED` | "Partial · REVIEWER_UNRESOLVED" |
| Smoke test exit ≠ 0 on a real build | `FINAL_BUILD_FAILED` | "Partial · FINAL_BUILD_FAILED" |
| Worker(s) hit iteration limit but wrote usable code | `CLEAN_WITH_INTERMEDIATE_WARNINGS` | "Partial" (no tag in the old path) |
| Legacy pre-v2.5.10 runs with missing reason | `LEGACY_UNKNOWN` | "Partial" |

Observed operationally (today's retries alone):

- Run `1ad38274` — cascaded to PARTIAL because tokens UI diverged; code was fine.
- Run `0a0201c7` — worker iter-cap hits on T1/T4 bubbled up as PARTIAL even though all 7 scaffold files were written and `expo export` succeeded.
- Run `988364af` — reviewer pass timed out at 60s (scaffold + tests + 4 screens = too much for the old 60s wiring timeout), REVIEW_FAILED cascaded to FINAL_BUILD_FAILED even though the workspace built cleanly.

The common thread: "partial" tells the user nothing actionable. The `status_reason` column carries the nuance, but the headline badge washes out all the detail. Users treat `PARTIAL` as synonymous with "broken" and re-run, wasting credits.

## Proposal

Replace `PARTIAL` with one of three outcomes, chosen by a pair of independent signals:

| Signal | Values | Source |
|---|---|---|
| `codeBuildPassed` | YES / NO / SKIPPED | Reviewer's final `buildProjectTool` exit; YES iff exit=0 |
| `reviewerOutcome` | CLEAN / FIXED / UNRESOLVED / INCOMPLETE | Reviewer pass outcomes + summary heuristics |

Terminal status becomes:

| codeBuildPassed | reviewerOutcome | New status |
|---|---|---|
| YES | CLEAN \| FIXED | **`COMPLETE`** (green, done) |
| YES | UNRESOLVED \| INCOMPLETE | **`COMPLETE_WITH_CONCERNS`** (amber; build works, reviewer flagged something) |
| NO | any | **`FAILED`** (red; build broken) |
| SKIPPED | any | **`FAILED`** (red; something upstream crashed) |

Three terminals instead of four. `PARTIAL` is deleted. `COMPLETE_WITH_CONCERNS` is the new amber; users immediately know "your code runs, but the reviewer has notes".

## UI changes

- `StatusBadge` — drop `PARTIAL`, add `COMPLETE_WITH_CONCERNS`. Existing tooltip mechanism (v2.5.10) already supports extended copy per status.
- `SignalBreakdown` — currently shows `Build ✓/✗ · Tests N/M · Reviewer VERDICT · Verified N · Est. N`. Keep as-is; it already exposes the two signals separately.
- Run list colour coding — COMPLETE=green, COMPLETE_WITH_CONCERNS=amber, FAILED=red. Three colours, three buckets.

## Data migration (V119)

- Add `V119__collapse_partial_status.sql` that:
  1. For every `pipeline_run` with `status = 'PARTIAL'`:
     - If `validation_report.final_build_passed = true` → `COMPLETE_WITH_CONCERNS`
     - Else → `FAILED`
  2. For every future row, DB constraint restricts `status` to `{QUEUED, PLANNING, AWAITING_APPROVAL, WORKING, REVIEWING, COMPLETE, COMPLETE_WITH_CONCERNS, FAILED, CANCELLED}` (9 values, was 10).
- `RunStatus` enum in code mirrors the constraint; `PARTIAL` stays as a deprecated `@Deprecated` synonym that resolves to `COMPLETE_WITH_CONCERNS` during deserialization for one major version, then gets deleted.

## Backend implementation

- `V2RunOrchestrator.resolveTerminalStatus(exec, review)` — rewrite the decision tree:
  ```java
  boolean buildPassed = review != null && Boolean.TRUE.equals(review.finalBuildPassed());
  var verdict = review == null ? null : review.overall();
  boolean reviewerClean = verdict == CLEAN || verdict == FIXED_ISSUES;

  if (buildPassed && reviewerClean) return new StatusAndReason("COMPLETE", …);
  if (buildPassed)                  return new StatusAndReason("COMPLETE_WITH_CONCERNS", …);
  return new StatusAndReason("FAILED", …);
  ```
- `status_reason` stays — drives the tooltip copy. Existing values (`CLEAN_COMPLETION`, `CLEAN_WITH_INTERMEDIATE_WARNINGS`, `FINAL_BUILD_FAILED`, `REVIEWER_UNRESOLVED`, `REVIEWER_FAILED`, `ALL_TASKS_FAILED`, `LEGACY_UNKNOWN`) are all still useful; `REVIEWER_UNRESOLVED` migrates to showing up on `COMPLETE_WITH_CONCERNS` instead of `PARTIAL`, same copy.
- Remove the `"PARTIAL"` branch from `mapOverallToV1Status` / `resolveTerminalStatus` fallbacks. Dead code.

## Credit / billing semantics

Today FAILED refunds the charge, PARTIAL does not. The new model:

- `COMPLETE` → no refund.
- `COMPLETE_WITH_CONCERNS` → no refund (the code builds; the user got value).
- `FAILED` → refund, same as today.

This is actually clearer than today — right now we charge for PARTIAL even though half the time the output is unusable (reviewer-failed with no deliverable). The split between "build works" and "build broken" is the honest line.

## Rollout

- Land the backend + DB migration behind a feature flag `status.tri-state.enabled` (default false).
- Ship the UI with `COMPLETE_WITH_CONCERNS` support but keep rendering `PARTIAL` when the flag is off.
- Flip the flag on a staging org for 2 weeks; if nothing breaks, flip for everyone; drop the flag + deprecated enum at the next major bump.

## What this does NOT solve

- Users still want the pipeline to succeed more often. This proposal clarifies the status; it doesn't make the reviewer or build more reliable. That's separate work (iteration caps, scaffold adherence, evidence-based reviewer — see today's F1–F5).
- Runs that timeout in the reviewer with an otherwise-fine workspace still get labelled `COMPLETE_WITH_CONCERNS` (the build passes). That's arguably the honest label but may be confusing — we'd want the SignalBreakdown to make "reviewer timed out" visible as a distinct chip.

## Estimated effort

- Backend + migration: 1 PR (~1 day).
- Frontend (badge + list colours): 0.5 day.
- QA across existing PARTIAL run archive: 0.5 day.
- Feature-flag rollout window: 2 weeks wall time.

Total dev: ~2 days + flag-wait.
