# Audit: `saga/` — transactional side-effect/compensation outbox

Status: **DONE** · Updated 2026-05-29 · 4 files / 437 lines · all read in full
- `SideEffectExecutor.java` (263) — 30s poller + dispatcher + backoff ✓
- `SideEffectOutbox.java` (85) — enqueue / enqueueCompensation ✓
- `SideEffectOutboxEntry.java` (59) — entity ✓ · `SideEffectOutboxRepository.java` (30) ✓

## What it does
A reliable-delivery outbox: callers `enqueue(runId, effectType, payloadJson)`; `SideEffectExecutor.processDue()` runs every 30s, atomically claims due PENDING entries (`markProcessing` PENDING→PROCESSING, multi-instance-safe), dispatches by `effectType`, marks COMPLETED or retries with exponential backoff (1m/5m/30m, max 3 → permanent FAILED). No secrets in the payload — the GitHub PAT is resolved from the run owner at execution time.

## Findings
- 🟢 **Solid outbox pattern** — atomic claim (race-safe across replicas), bounded backoff, idempotent GCS-archive retry (skips if `gcsWorkspaceKey` already set), per-type Micrometer counters. This is the durability layer behind the orphan-branch cleanup ([integration.md](integration.md) `ScmPushService` audit #16) + GCS workspace archiving.
- 🟡 **Most documented effect types are unimplemented / stubs.** Only `COMPENSATE_GITHUB_BRANCH` (real branch delete) and `GCS_ARCHIVE` (real retry) actually do work. `COMPENSATE_JIRA_COMMENT` + `COMPENSATE_CLOUD_RUN` are **log-only stubs** ("would delete service") that still mark the entry COMPLETED — so a `COMPENSATE_CLOUD_RUN` enqueue would silently NOT delete the orphaned Cloud Run service (a cost-leak risk if anything enqueues it). The forward types the `SideEffectOutbox` javadoc advertises (`GITHUB_PR`, `CLOUD_RUN_DEPLOY`, `JIRA_COMMENT`, `CONFLUENCE_PAGE`) aren't in the dispatch switch at all → they'd hit the "Unknown effect type — no-op" branch and be marked COMPLETED without acting. The outbox's real coverage is 2 types; the doc oversells it. (Verify nothing enqueues the stub/unknown types; if it does, those side-effects silently vanish.)
- 🟡 **Orphaned-PROCESSING entries never recover.** `findDue` returns `status IN ('PENDING','PROCESSING')`, but `markProcessing` only re-claims `status='PENDING'` — so an entry left PROCESSING by a crash between claim and terminal-save is re-fetched every sweep yet `markProcessing` returns 0 (already PROCESSING) → skipped forever. There's no PROCESSING→PENDING reaper. Add a stale-PROCESSING reset (e.g. PROCESSING older than N min → PENDING) so a mid-effect crash self-heals.

## Cross-refs
Enqueued by → [integration.md](integration.md) (`ScmPushService.enqueueOrphanBranchCleanup`). GCS retry → `deployment/` (`WorkspaceExportService`/`GcsWorkspaceStore`, Tier 7). Run-owner PAT (no system PAT) rule → [integration.md](integration.md).
