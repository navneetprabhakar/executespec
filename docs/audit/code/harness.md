# Audit: `harness/` — V2 pipeline orchestration

Status: **DONE** · Updated 2026-05-29 · 15 files / 4,175 lines · all read in full

## Files read in full — package complete
- `SequentialExecutor.java` (2,002) ✓ · `UiStyleResolver.java` (541) ✓ · `VendoredPathGuard.java` (415) ✓ · `WorkspaceInitService.java` (409) ✓ · `GateStore.java` (291) ✓
- DTOs/config: `ExecutionRequest` (59) · `ExecutionResult` (53) · `TaskOutcome` (59) · `ExecutionMode` (24) · `DesignSystemProperties` (45) ✓
- **Subpackages (caught by the MANIFEST coverage-audit — were missed by the top-level glob):** `entity/GateRecord` (80) · `repository/GateRepository` (36) · `event/PipelineEvent` (72) · `event/PostMergeOrchestrator` (64) · `event/QuotaEvent` (26) ✓

## What it does (SequentialExecutor)
Runs a `Plan`'s tasks one worker at a time. Per task: compose system+user prompt → `WorkerAgent.run()` → smoke (`buildProject`) on final task of a multi-task plan → ONE retry with the build error injected → HIGH gets a 2nd retry → optional cross-provider retry. Holds the worker `SYSTEM_PROMPT_BASE` and `tierConfigFor` (model + caps by complexity).

## Findings

### Accuracy / Efficiency / Cost
- 🔵 **`tierConfigFor` is the real tier→model+caps map**: LOW→LIGHTWEIGHT (40 iter / 4k / 30m), MEDIUM→WORKHORSE (75 / 8k / 45m), HIGH→PLANNING (120 / 16k / 75m); project runs ×2 on iter+wall-clock. So MEDIUM/HIGH worker tasks run on Sonnet/GPT-5.4 — confirms the doc-drift fix.
- 🔵 **Worker `SYSTEM_PROMPT_BASE` is genuinely strong** (lines 77–173): localize→reproduce→fix-minimal→verify discipline, tests-are-mandatory, "batch aggressively / don't re-read after write", format-after-edit. Comparable to a well-tuned SWE-bench agent prompt.
- 🔵 **In-loop test-exec gate is OFF** (`build.test-exec-gate.enabled`, V209): after a clean compile it *can* run the worker's tests via `WorkspaceTestRunner` and flip smoke→FAIL on real test failures, but default-off → BUILD "tests pass" is LLM-judged. Flip candidate.
- 🔵 **Cross-provider fallback** (`worker.cross-provider-fallback.enabled`, V220) + **retry context retention** (`worker.retry-context-retention.enabled`, V221) both default OFF. Retention only wired on the build-fail retry path; primary tasks always cold-start (re-read the repo).
- 🔵 Retry gets +50% iter/wall-clock headroom if the primary hit the iter/wall-clock cap (adaptive, capped 1.5×).
- 🔵 Provider-prompt tuning fragments (`worker.provider-prompt-tuning.enabled`, V219) OFF.

### Correctness / bugs
- 🟡 `executeOne` is a long method with deeply nested retry branches (primary → retry1 → HIGH retry2 → cross-provider, each re-smoking) — many states, hard to test exhaustively; a wrong fall-through would silently mis-status a task.
- 🟢 Cancel honoured at task boundary (re-asserts interrupt); prior bug (cascade-FAILED on cancel) documented + fixed.
- 🟢 `persistTaskStatus` is `@Retryable` on `DataAccessException` (fixes stuck-WORKING rows).

### Stale-docs/drift
- 📝 Root CLAUDE.md iter caps were 30/50/75 → actual 40/75/120 (fixed 2026-05-29).

### Tech-debt
- 🟡 Smoke is skipped for non-final tasks in multi-task plans — intermediate breakage only surfaces at the reviewer's final build. Reasonable, but means per-task COMPLETE can hide a broken intermediate.
- 🟡 `tierConfigFor` has large inline comment-blocks of calibration history (valuable but the method is dense).

## Findings — init / gate / design / events (2026-05-29)

### Good (well-engineered)
- 🟢 **`GateStore` — robust 3-layer plan-approval gate**: in-memory `CompletableFuture` (instant same-instance) + Redis (cross-instance, 4h TTL) + Postgres `GateRecord` (durable fallback on Redis outage). `tryClaimPlanGate` → `GateRepository.tryResolveIfPending` is an **atomic conditional UPDATE** so concurrent `approve` calls race cleanly (winner 200, loser 409 — closes adversarial-test Finding 4). 24h max wait; survives server restart (Redis/DB recover the decision).
- 🟢 **`WorkspaceInitService`**: per-run isolated git repo (init `-b main` / clone existing / `copyFromRun` for iteration runs / `cloneMultipleRepos` per-repo subdir). PAT masked in error logs (`oauth2:***@`). `.gitattributes merge=union` on README/CHANGELOG. `VendoredPathGuard.augmentGitignore` on every clone + run-repo SHA snapshot capture.
- 🟢 **`VendoredPathGuard`**: stops `node_modules`/`venv`/`dist`-class dirs being committed (augment-gitignore + purge-staged + scrub-committed). Root-caused to run b171fa52 (9,605 vendored files committed, tanked the score). This is the guard `GitCommitTool` invokes pre-commit.
- 🟢 **`UiStyleResolver`**: 50+ filesystem `DESIGN.md` templates (`DesignSystemProperties.rootPath`), fuzzy "like Stripe/Notion" matching, token-budget truncation, and an authoritative frontend constraint-block injected into the worker prompt (curated: airbnb/claude/figma/linear/notion/stripe/supabase/vercel/…).
- 🟢 **`event/PostMergeOrchestrator`**: `@EventListener` on `MergeCompleted` → runs `ContextAgent` + `PromptOptimiser` + `ApiTestSuite` in parallel virtual threads, **all non-fatal** (failures logged, never affect run status). Clean decoupling of optional post-merge work from the orchestrator. `PipelineEvent` is a tidy sealed hierarchy (RunStarted/MergeCompleted/RunCompleted/RunFailed/RestartRun/TaskStuck/GateOpened); `QuotaEvent.QuotaWarning` at 80%.

### Stale-docs / legacy residue
- 📝 `GateStore`/`GateRecord` javadoc still describe MERGE + CLARIFICATION gates, but V2 retired them — only the PLAN gate is live (GateStore comment confirms). Doc residue.
- 🟡 `WorkspaceInitService`'s `.gitattributes merge=union` rationale cites "parallel worker agents" — V2 is sequential, so this (like the `tool/` worktree+lock machinery) is V1-parallel residue, harmless but dead-weight.

### Coverage-audit note
- ⚠️ These 5 subpackage files (`entity/`, `repository/`, `event/`) were **missed by the initial top-level `wc -l harness/*.java` glob** and only surfaced when the [MANIFEST.md](MANIFEST.md) per-file ledger flagged them unread. Going forward, package "DONE" requires **zero unread under the recursive package prefix** in the manifest, not a top-level file list. (Same audit also caught + re-read 7 falsely-prefix-marked `agent/model/` DTOs.)

## Cross-refs
WorkerAgent loop → [engine.md](engine.md). Smoke runner → [smoketest.md](smoketest.md) (Tier 2). Model resolution → [router.md](router.md). VendoredPathGuard consumer → `tool/GitCommitTool` ([tool.md](tool.md)). PostMerge consumers → context/optimiser/apitests (later tiers). `agent/model/` V1 DTOs (read closing agent/) → [agent.md](agent.md).
