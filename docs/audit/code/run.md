# Audit: `run/` — run-scoped helpers (skip-push flag + workspace sync)

Status: **DONE** · Updated 2026-05-29 · 2 files / 215 lines · all read in full
- `sync/RunSyncService.java` (163) — reset a terminal run's workspace to a branch HEAD or its original SHAs ✓
- `RunSkipPushFlag.java` (52) — Redis one-shot "skip auto-push" flag ✓

## Findings
- 🟢 `RunSyncService` — "Sync from branch" for terminal runs: `git fetch` + `git reset --hard` to either `origin/<branch>` (`sync`) or the exact clone-time SHAs from `run_repo_snapshot` (`syncToOriginalCommit`). Handles multi-repo (`repos/<name>/`) + single-repo (root) layouts; pre-v2.5.2 runs without snapshots return a clean empty outcome. `reset --hard` intentionally wipes local edits (documented); API layer gates the terminal-only invariant. Clean.
- 🟢 `RunSkipPushFlag` — Redis-only one-shot (`consume` = `getAndDelete`), 24h TTL, intentionally NOT persisted to `pipeline_run` (staging is a per-submit decision; a crash safely reverts to default auto-push). `RunOrchestrator` consumes it once at the auto-push gate ([integration.md](integration.md)). Clean.
- No issues. Small, focused, well-scoped helpers.

## Cross-refs
`RunSkipPushFlag` consumed at → [integration.md](integration.md) (`RunOrchestrator` auto-push gate). Snapshot source → [state.md](state.md) (`run_repo_snapshot` / `WorkspaceInitService.cloneMultipleRepos`).
