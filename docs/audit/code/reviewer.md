# Audit: `reviewer/` — post-execution review + accuracy assessment

Status: **DONE** · Updated 2026-05-29 · 12 files / 2,547 lines · all read in full

## Files
- `ReviewerAgent.java` (769) — 3-pass reviewer (general → wiring → fix-build) as a thin composition over `WorkerAgent` ✓
- `AccuracyAssessmentService.java` (1,053) — Phase-3 spec-adherence scorer + 4 anti-hallucination layers ✓
- `PostEditFormatter.java` (318) — host-side black/prettier/gofmt pass after reviewer edits ✓
- `ReviewerRequest.java` / `ReviewerResult.java` — DTOs ✓
- `model/{AccuracyReport,ReviewReport,ReviewIssue,FixInstruction,IssueCategory,IssueSeverity,ReviewVerdict}.java` ✓

## What it does
`ReviewerAgent.review()` runs after `SequentialExecutor`. It captures a pre-review `buildProject` smoke (injects the verbatim build error as the pass's "spec"), then runs **general** + **wiring** passes as narrowly-scoped `worker.run()` invocations on a daemon thread pool with per-pass wall-clock timeouts (600s each). After both, `PostEditFormatter` runs language formatters, then a final `buildProject`. If the build is still red and `reviewer.third-pass.enabled` (default ON), a **fix-build third pass** (25 iters / 360s, general prompt) retries with the build error, then rebuilds. Verdict: CLEAN / FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED, with a write-aware downgrade (FIXED_ISSUES→CLEAN when build passes but neither pass wrote a file). `AccuracyAssessmentService.assess()` is a separate **PLANNING-tier** LLM call (via the deprecated `FallbackLlmRouter`) that scores spec adherence 0–100 over a file-tree + key-file snippet + review-issue summary, then runs Layer-3 grep-verify + Layer-4 execution-time filtering to demote false-MISSING requirements.

## Findings

### Correctness / reliability
- 🔵 **A timed-out reviewer pass is NOT actually stopped — it keeps burning charged LLM tokens.** `runPassWithTimeout` does `future.get(600s)`, then on timeout `future.cancel(true)` ([ReviewerAgent.java:550-551](../../../src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java)) and returns a synthetic ERROR. But `cancel(true)` only sets the thread interrupt flag, and **`WorkerAgent` has zero interrupt checks** (`grep isInterrupted engine/WorkerAgent.java` = empty). Worse, `runPass` builds the `WorkerTaskRequest` **without `wallClockSeconds`** ([ReviewerAgent.java:439-454](../../../src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java)), so the worker's own per-iteration wall-clock guard (`WorkerAgent.java:337`, only active when `wallClockSeconds>0`) is disabled. Net: the only bound on a timed-out pass is `maxIterations=50`; it runs to natural completion on a detached daemon thread — each iteration potentially a multi-minute `buildProject` — and every LLM call is still recorded by `RunCostTracker` against the run. The verdict was already discarded as REVIEW_FAILED, so the spend produces no value. The 600s bump (from 90s) makes the timeout fire rarely, but when it does it leaks. **Fix options:** pass `wallClockSeconds≈timeout` into the reviewer's `WorkerTaskRequest` so the worker self-terminates, and/or add an `isInterrupted()` check at the top of the worker loop. (Same interrupt-unawareness likely defeats `RunOrchestrator.cancel` future-cancellation — verify in Tier 3.)
- 🟢 **Build is ground truth.** `determineOverallRaw` lets `!buildPassed` dominate any agent-claimed verdict; the third pass re-runs the build and keeps the post-pass state. Correct.
- 🟢 **Write-aware downgrade** (FIXED_ISSUES→CLEAN with zero writes + green build, [ReviewerAgent.java:635-643](../../../src/main/java/com/navneet/executespec/reviewer/ReviewerAgent.java)) closes a real "reviewer claims a fix it didn't make" mismatch (dev run 61c9fffa). Good.
- 🟢 Boot-time prompt existence check + hardened `loadPrompt` (distinct missing-vs-IO error) — fail-loud on a stale JAR. Good.

### Accuracy / Efficiency / Cost (the priority)
- 🔵 **Reviewer fires up to 4 standalone `buildProject` smokes per run** (pre-general + pre-wiring + final + post-third-pass rebuild), on top of each pass's own internal builds. On a Spring Boot project each smoke is a full `mvn` build — wall-clock heavy though not LLM cost. The pre-general + pre-wiring smokes are partially redundant (pre-wiring re-runs only if pre-general was red). Candidate to cache the build state between the final-build and the pre-third-pass capture.
- 🔵 **Strong anti-hallucination machinery** — Layer-3 grep-verify (demote MISSING→UNKNOWN when the requirement's distinctive tokens grep-hit the workspace) + Layer-4 execution-time filter (drop "commit/push/--check exit 0" requirements the static snapshot can't witness). This directly attacks the "false-MISSING tanks verifiedScore" class (run 6024f510: 85→43). This is the accuracy-honesty backbone and is well-targeted — keep.
- 🔵 **Accuracy assessment is ~25K input tokens every run** — up to 18 key files × 10K chars (capped 100K total) + file tree + issue summary, one PLANNING-tier call. The key-file picker (`inferKeyFilePaths`/`pickWithStructuralPreference`/`structuralTier`, ~200 LOC) is a careful structural-dir-first heuristic with multi-repo per-repo budgets — reduces wasted reads on boilerplate/vendored. Reasonable, but the 100K-char snippet is a fixed cost on every completed run regardless of spec size.
- 🔵 **Gemini reviewer → WORKHORSE (Flash), Anthropic/OpenAI → PLANNING** for implicit-cache reasons (Pro's 4096 threshold missed; 0/14 calls hit cache). Consistent with CLAUDE.md + router.md.
- 🔵 Third pass reuses `reviewer-general.md` (no dedicated fix-build prompt) and its `VERDICT:` line is ignored — only the post-pass rebuild exit code matters. Correct by design (build is the spec), but the pass's own completion summary IS appended to the wiring summary for the UI changelog.

### Stale-docs / drift
- 📝 `ReviewReport.java:14` javadoc references **`ReviewerAgentV2`** — no such class exists (`grep "class ReviewerAgentV2"` = empty). Stale rename residue; should read `ReviewerAgent` or be dropped.
- 📝 `ReviewerRequest.java` javadoc says "null = default 30" (twice) but `DEFAULT_MAX_ITER_PER_PASS=50` since 2026-04-19. Minor.
- 📝 `ReviewReport.getRecommendationCompat()` self-describes "no live caller as of V2 cutover" + a block of "V1-compatible fields" — dead compat surface.

### Tech-debt
- 🟡 **`AccuracyAssessmentService` runs on the deprecated `FallbackLlmRouter`** (RL-2026-05-041 migration target) — same posture as `ContextAgent`. It's a non-tool single-shot JSON call, so the legacy router is the intended home today, but it's on the migration list.
- 🟡 **`FixInstruction` is near-dead** — referenced only by legacy `agent/ConversationAgent.java`. Dies with the FallbackLlmRouter path.
- 🟡 `AccuracyAssessmentService` is 1,053 LOC mixing the prompt, the 4 post-processing layers, and the ~200-LOC key-file selector — the selector (`inferKeyFilePaths`+helpers) is a cohesive unit that could be its own `KeyFileSelector`.
- 🟢 `PostEditFormatter` is best-effort + env-stripped ProcessBuilder + 60s timeout + skip-dirs; never throws. Clean.

## Cross-refs
Worker composition + the interrupt gap → [engine.md](engine.md) (`WorkerAgent` has no interrupt check; `wallClockSeconds` guard). Verdict/issue consumers → `integration/RunOrchestrator` (Tier 3), `report/ValidationReportService` + `scoring/ReviewScoringService` (this tier). Accuracy report → `report/ValidationReport` entity. Build smoke → [tool.md](tool.md) (`BuildProjectTool`). Deprecated router → [agent.md](agent.md) (`FallbackLlmRouter`).
