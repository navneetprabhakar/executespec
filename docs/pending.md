# Pending items

> Created 2026-06-01. Detailed register of open work from the agent-quality hardening branch
> (`claude/agent-competitive-review-F4boG`, PR #199) and the items it deliberately parked.
> Newest context first. Cross-referenced to RL ids in [`reason-log/2026-05.md`](reason-log/2026-05.md).
>
> **Legend:** 🔴 blocks merge · 🟠 should do soon · 🟡 planned / eval-gated · ⚪ optional / pre-existing · ✅ done.

## Update 2026-06-01 (pass 2 — "fix all")
Fixed everything feasible without a compiler/network (the sandbox blocks maven repos — 403):
**B1 ✅** EditFileTool integration test · **B2 ✅** isTransientError 429-by-type · **B3 ✅** gold-source leak fully removed (residual noted) · **D4 ✅ (core)** `TaskWaveScheduler` + test, standalone-validated 10/10.
Still genuinely blocked here: **A1–A3** (need a network/compiler — your machine or CI), **E1** (god-class split must be by-hand *with a compiler*, per its plan), **C/D1–D3** (founder said keep flags admin-toggleable; eval-gated), **F** (founder-parked).

---

## A. Blocks PR #199 merge

### A1 🔴 Local build + format gate
- **What:** Run `JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn verify -q` and `mvn spotless:apply` on the branch.
- **Why:** the review sandbox blocked `repo.spring.io` (parent POM `spring-boot-starter-parent`), so the Spring-wired changes were **never compiled** here. `StringEditMatcher` is validated standalone (13/13), but `LlmRouter`, `SearchFilesTool`, and `SwebenchTaskService` need a real `javac` + spotless pass.
- **Watch for:** unused-param warnings on the two neutralised `SwebenchTaskService` methods (intentional — signatures preserved); spotless line-length / import-order on the new files (`StringEditMatcher`, `LlmRouterTest`, `StringEditMatcherTest`, the `SearchFilesTool` rg helpers).
- **Owner action:** founder local run. This is the single most important follow-up.

### A2 🔴 Confirm the 4 re-enabled worker tests are green
- **What:** `mvn test -Dtest=WorkerAgentStagnationTest,WorkerAgentProgressNudgeAndWallClockTest`.
- **Why:** the off-by-one was root-caused (verify-before-done nudge, RL-2026-05-063) and expectations bumped +1, but the iteration accounting was hand-traced (sandbox can't run the suite). If any count is off, the fix is a one-line expectation tweak.
- **Refs:** `WorkerAgentStagnationTest#writeFile_resets_stagnation_counter` (→17), `WorkerAgentProgressNudgeAndWallClockTest` (→9 / →4 / →3).

### A3 🔴 CI may hit the same `repo.spring.io` access
- **What:** if CI fails resolving the parent POM, that's an environment/network policy issue, not the diff.
- **Owner action:** confirm CI has milestone-repo access (or a mirror); otherwise the new-code verification falls to A1.

---

## B. Owed from this branch (small, in-scope)

### B1 ✅ `EditFileTool` integration test — DONE (RL-2026-05-065)
- Added `tool/EditFileToolTest.java` (5 cases): exact edit, whitespace-drift-through-the-tool, read-before-edit gate block, missing-file NOT_FOUND, no-partial-apply on a failed 2nd edit. Drives the real tool through `FileSandboxPolicy` + `ReadFileStateStore` against a `@TempDir`. (Not run here — needs A1.)

### B2 ✅ `isTransientError` robustness — DONE (RL-2026-05-065)
- Added a typed `HttpClientErrorException.TooManyRequests` instanceof to `agent/LlmRouter.java#isTransientError` (robust 429); substrings kept as fallback. 400/401/403/404 still non-transient.

### B3 ✅ Oracle-removal follow-up — DONE-with-note (RL-2026-05-065)
- The gold-test **source** leak is fully removed (RL-2026-05-064): `formatTestPatchSnippet` + `captureFailingTestFilesFallback` emit nothing → `captureFailingTestSource` returns "" → the "# Gold tests" prompt block is skipped (blank source). **Residual decision (not fixed):** the "# Failing test output" block in `prefixWithFailingTestOutput` still injects captured FAIL_TO_PASS *run output* — a lesser oracle signal on a path likely dead now that `SwebenchController` is gone. Left as-is rather than risk an unverifiable edit to a possibly-dead flow; revisit if the swebench event flow (`onRunStarted`) is confirmed reachable.
- **Where:** `swebench/SwebenchTaskService.java`, `swebench/SwebenchBootstrap.java`.

---

## C. Feature-flag toggles (SUPER_ADMIN, no migration needed)

### C1 🟡 New flags are seeded default-OFF and admin-toggleable
Per founder call, defaults are NOT flipped; toggle in the SUPER_ADMIN flag console (`POST /api/feature-flags/{key}`) after a no-regression eval:
- `router.failover.enabled` (V226) — multi-provider failover. Fail-safe/additive; lowest risk to flip.
- `worker.tool-compactor.enabled` (V216) + `worker.history-compaction.enabled` (V222) — context discipline. Prereq verified satisfied (WorkerAgent threads `req.provider()` into both compactors). Changes every run's context → flip behind an eval.
- `worker.cross-provider-fallback.enabled` (V220) — task-level cross-provider retry.

---

## D. Latency (see [`plans/latency-reduction-phased.md`](plans/latency-reduction-phased.md))

### D1 🟡 Phase 0a — context-compaction default-ON
- Flip C1's compaction flags after an eval. Prereq done. Wall-clock + verifiedScore comparison via `scripts/v2-eval.sh medium+high × 3 providers`.

### D2 🟡 Phase 0b — skip redundant per-task builds
- **What:** only run the per-task `buildProject` smoke when a task wrote build-relevant files (or at phase boundaries), not unconditionally.
- **Why:** N tasks = N full builds today; the final-run build/test gate preserves the correctness signal.
- **Where:** `harness/SequentialExecutor.java`. Eval-gated (catch-rate regression check).

### D3 🟡 Phase 0c — per-tier budget re-tune
- Revisit wall-clock / iteration caps once context is smaller (D1) so legit long tasks finish without the eval-script timeout.

### D4 🟡 Phase 1 — parallel task execution (the big win)
- **Core ✅ DONE (RL-2026-05-065):** `harness/TaskWaveScheduler.java` — the pure Kahn-levels scheduler (waves of `dependsOn`-independent tasks; cycle → reported in `unscheduled`, not thrown; unknown deps recorded). Standalone-validated 10/10 + `TaskWaveSchedulerTest` (7 cases). Additive, **not wired** to the executor.
- **Remaining (the risky, eval-gated half):** wire into `SequentialExecutor#execute` behind `harness.parallel-tasks.enabled` (default OFF), bounded concurrency `min(3, waveSize)`, thread-safe outcome aggregation, per-strategy task-count caps. **Blockers:** shared-workspace file races (only a live multi-task run can prove absence) + task-count band coupled to the legacy classifier. Eval-gated before the flag flips.
- **Prereq:** RL-2026-05-001 (real test exec — done).

---

## E. Larger refactors

### E1 🟠 RunOrchestrator god-class — next break
- **What:** continue splitting the ~4,456-LOC `RunOrchestrator` per [`plans/runorchestrator-god-class-split.md`](plans/runorchestrator-god-class-split.md) (6 by-hand increments; candidate next: the validation-report / PR-push run-end orchestration into a focused service with its own tests).
- **Why deferred:** the plan doc records that **automated extraction corrupted the file twice** — must be done by-hand, increment-by-increment, with the build running after each step. Not safe to attempt in a no-compile sandbox.
- **Owner action:** execute locally, one increment + full test per commit.

---

## F. Explicitly parked by founder (tracked, not actioned)

### F1 ⚪ Grading / `verifiedScore` strategy
- Parked — "we will figure out a better strategy." Review finding: 40–53% of the headline score is LLM-self-graded whenever real tests don't resolve, and the anti-gaming guard keys off the same LLM judge. No change made.

### F2 ⚪ Claude Code / Codex head-to-head report
- Handled on the founder's local setup; no work owed here. (The review's gap — no committed re-runnable head-to-head — is closed on the founder's machine.)

---

## G. Pre-existing debt surfaced by the review (optional, not this branch)

- ⚪ **Frontend:** no code-splitting (single ~800KB bundle), effectively zero running frontend unit tests (`api/core.ts` ~2K LOC untested). See `docs/tech-debt.md`.
- ⚪ **`ProviderHealthTracker` circuit breaker** on the V2 `LlmRouter` failover path — the failover (RL-2026-05-063) has retry + chain but no circuit breaker yet (the legacy `FallbackLlmRouter` has one). Noted in the M#68 javadoc.
- ⚪ **`JAVADOC.md` stale** — full rewrite queued (head ref was bumped, content not regenerated).
