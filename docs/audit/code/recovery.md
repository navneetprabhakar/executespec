# Audit: `recovery/` — boot-time V2 crash recovery

Status: **DONE** · Updated 2026-05-29 · 1 file / 350 lines · read in full
- `StartupRunResumer.java` (350) ✓

## What it does
`@Async` `ApplicationReadyEvent` listener that scans non-terminal **v2** `pipeline_run` rows left mid-flight by a dead JVM (crash/SIGTERM/deploy) and recovers each:
- **AWAITING_APPROVAL** → untouched (user gate, no thread needed).
- **All tasks terminal + workspace present** → **salvage**: mark COMPLETE/PARTIAL with `REVIEWER_INTERRUPTED_CODE_PRESERVED` (code is on disk; user keeps preview/chat/retry-validation). Workspace-missing precheck (B-22) → fail-clean instead of a useless COMPLETE.
- **Else** → `RunOrchestrator.resumeAfterCrash` (Phase-2 replay from `plan_json` + Redis snapshot), capped at `MAX_RESUME_ATTEMPTS=3` and a 6h stale cutoff.

## Findings
- 🟢 **Well-designed fail-safe recovery.** Salvage-before-fail preserves the customer's generated code; the workspace-existence precheck closes the "COMPLETE-but-empty" trap; attempt cap + stale cutoff prevent resume loops; `@Async` never blocks startup; `ObjectProvider<RunOrchestrator>` breaks the bean-init cycle. Pairs cleanly with `ExecutionOrchestrator`/`AdminReconciliationService` boot listeners ([execution.md](execution.md)).
- 🔵 **Part of a 5-listener boot-recovery fleet** (this + `ExecutionOrchestrator.onApplicationReady` + `AdminReconciliationService.onApplicationReady` + `ExecutionTotalsBackfillJob` + `RunTitleBackfillService`). All `@Async`/non-blocking and individually justified, but it's a lot of startup machinery scanning overlapping row sets — worth a consolidation pass eventually.
- 🟢 Correctly scoped to `harnessVersion="v2"`; V1 resumer owns legacy rows.

## Cross-refs
Replay target → [integration.md](integration.md) (`RunOrchestrator.resumeAfterCrash`). Snapshot source → [context.md](context.md) (`ContextStore`). Salvage status reason consumed by → [report.md](report.md) (`reconcileWithRunStatus`). Sibling boot listeners → [execution.md](execution.md), [state.md](state.md) (`RunTitleBackfillService`).
