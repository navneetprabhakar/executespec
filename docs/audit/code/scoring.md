# Audit: `scoring/` — dashboard dimensions + (dead) rubric verifier

Status: **DONE** · Updated 2026-05-29 · 5 files / 783 lines · all read in full

## Files
- `ReviewScoringService.java` (325) — deterministic 6-dimension dashboard scores (no LLM) ✓
- `RunRubricVerifier.java` (258) — planner-rubric verification engine — **dead/unwired** ✓
- `TestCoverageRescorer.java` (71) — event listener re-derives testCoverageScore from real test results ✓
- `entity/RunRubricRow.java` (107) — rubric row entity (V197) — **table never written** ✓
- `repository/RunRubricRowRepository.java` (22) ✓

## What it does
`ReviewScoringService.score()` is called by `RunOrchestrator` + `ValidationReportService` to produce the six dashboard dimension bars (codeQuality / testCoverage / architecture / security / performance / designCompliance). It anchors on `verifiedScore` (or `accuracy.specAdherenceScore` as fallback) and subtracts category-bucketed issue penalties. **Honesty rules:** `codeQuality` + `performance` are ALWAYS `null` (the verifiedScore IS the code-quality summary; performance is never benchmarked); `architecture`/`security` return `null` unless a real category-issue / auth-hint signal exists; `designCompliance` only when `figmaUrl` is set; `testCoverage` is `null` for doc-only runs. `TestCoverageRescorer` later overwrites the persisted `testCoverageScore` from the real `TestExecutionCompletedEvent` (testgen) since the accuracy-derived value runs before tests land and always read NONE→20.

## Findings

### Correctness / reliability
- 🟡 **`llmCoverageLevel` doesn't handle the bands the accuracy prompt actually emits.** `AccuracyAssessmentService.SYSTEM_PROMPT` constrains `testCoverageLevel` to **NONE / PARTIAL / GOOD / COMPREHENSIVE**, but `ReviewScoringService.llmCoverageLevel` ([line 229-238](../../../src/main/java/com/navneet/executespec/scoring/ReviewScoringService.java)) switches on `HIGH/COMPREHENSIVE → 95`, `MEDIUM/ADEQUATE → 70`, `LOW/MINIMAL → 35`, `NONE → 10`, `default → 40`. So **`GOOD` and `PARTIAL` both fall to the `default → 40`** branch — a well-tested run graded GOOD gets the same LLM coverage signal (40) as an unknown one. Mitigated because the dimension is `(fileRatio×2 + llm)/3` so the file-ratio dominates (2/3 weight), but it's a clear enum-vocabulary drift between the producer and the consumer. Fix: switch on the four documented bands.
- 🟢 `deriveTestCoverage` returns `null` for zero source-files-changed (doc-only) and `scanWorkspace` only counts files touched after `startedAt - 10s` — closes the "README change scores 20/100" bug honestly.
- 🟢 `TestCoverageRescorer` is idempotent (skips when score unchanged) and race-safe (skips if no report yet; next regen picks up). Minor: the "X → Y" log reads the field *after* the setter, so it always prints "new → new" — cosmetic only.

### Security (latent — currently unreachable)
- 🟡 **`RunRubricVerifier.verifyShell` runs an LLM-supplied command with the full server environment, no sandbox.** It does `ProcessBuilder("/bin/sh","-c", row.getVerificationSpec())` in the workspace dir with **no env-strip and no `ShellSandboxPolicy`/`CodeAbuseScanner`** ([line 161-176](../../../src/main/java/com/navneet/executespec/scoring/RunRubricVerifier.java)) — even though the same class carefully guards path-traversal in `safeResolveUnder` (treats LLM input as untrusted for paths but not for commands). **This is currently inert** (see dead-code below) so it's not a live vuln, but it is a landmine: whoever wires Phase B/C to make the planner emit `shell` rubric rows would ship an RCE-with-secrets vector. Must sandbox the shell handler (env-strip + `ShellSandboxPolicy`, mirroring the worker's `ShellTool` / `PostEditFormatter`) **before** the rubric path is ever activated.

### Tech-debt — abandoned initiative (dead code)
- 🟡 **Scoring Revamp Phase A (V197, 2026-05-14) is dead scaffolding.** `RunRubricVerifier.verifyRubric` is called **only by its unit test** (`grep verifyRubric src/` = test-only); **nothing anywhere builds or saves a `RunRubricRow`** (`grep RunRubricRow.builder|RunRubricRowRepository` outside `scoring/` = empty). So the planner never emits rubrics despite the entity/verifier javadoc asserting "Planner emits these at plan time" — that's aspirational and never happened. Net: `RunRubricVerifier` + `RunRubricRow` + `RunRubricRowRepository` (~387 LOC) + the `run_rubric_row` table (V197) are wired to nothing; the `endpoint`/`pr-state`/`manual` handlers are explicit "Phase B" stubs. Either revive (Phase B/C per `docs/plans/scoring-revamp-design.md`) or delete. The migration is immutable (can't drop V197 in place) but the Java + the never-read table are removable.
- 📝 Entity/verifier docs claim live planner integration that doesn't exist — stale.

### Accuracy / Efficiency / Cost
- 🔵 `ReviewScoringService` is fully deterministic (no LLM call) and caps the workspace walk at 5,000 files — cheap. The auth-hint scan reads the head 4K of every file until the first hint fires (then short-circuits) — bounded.
- 🔵 The "honest null" framing (don't show a bar we can't measure) is the right call for a trustworthy dashboard — aligns with the verifiedScore honesty pass ([report.md](report.md) pending).

## Cross-refs
Anchor + issue models → [reviewer.md](reviewer.md) (`ReviewReport`/`ReviewIssue`/`AccuracyReport`; confirms `ReviewReport` IS live — built by `RunOrchestrator`). Score consumers → `report/ValidationReportService` (this tier), `integration/RunOrchestrator` (Tier 3). Rescorer trigger → `testgen/` (Tier 6, `TestExecutionCompletedEvent`). Shell-sandbox comparison → [tool.md](tool.md) (`ShellTool`+`ShellSandboxPolicy`), [reviewer.md](reviewer.md) (`PostEditFormatter` env-strip).
