# Audit: `report/` — verifiedScore + validation report assembly

Status: **DONE** · Updated 2026-05-29 · 14 files / 3,031 lines · all read in full

## Files
- `ValidationReportService.java` (1,524) — **the verifiedScore home**: report assembly + 3 score formulas + DTO mapping ✓
- `ValidationReportGenerator.java` (383) — LLM markdown narration (FallbackLlmRouter, WORKHORSE) ✓
- `UsageReportService.java` (283) — weekly/monthly usage emails (SUPER_ADMIN) ✓
- `VerifiedScoreBackfillService.java` (149) — one-shot SUPER_ADMIN re-score of historical rows ✓
- `ValidationReportRepository.java` (162) — findByRunId/shareToken + landing/exec-demo aggregates ✓
- `ValidationReportController.java` (88) / `ValidationReportPdfService.java` (90) ✓
- `entity/{ValidationReport,PipelineResult}.java` + `model/{ReportInput,ValidationReportDTO,ValidationReportShareDTO,ReportIssueDTO,CompileResult}.java` ✓

## What it does
`generateAndPersist(runId, ReportInput)` runs after `ReviewerAgent`. It extracts issues (reviewer flat list + compiler + grouped integration-test failures), computes **`verifiedScore`** (additive default / multiplicative legacy, flag `scoring.additive-v2`), a companion **`rubricScore`**, and the **6 dashboard dimensions** (via `ReviewScoringService`), narrates a human markdown summary via an LLM (`ValidationReportGenerator`), reconciles `pipelineResult` against the orchestrator's `status_reason` (R-09), and persists `ValidationReport`. Companions: PDF export (Thymeleaf+FlyingSaucer), public share tokens, landing-page aggregates, usage emails, and a one-shot score backfill.

## Findings

### Accuracy / scoring honesty (the priority)
- 🟡 **Anti-gaming guard (A) re-injects the LLM read-budget false-low into the headline.** The additive `computeVerifiedScoreAdditive` CODE_DELIVERED blend (`gamingGuards && endpointCoveragePercent != null` ⇒ `0.5×100 + 0.5×ep`, [line 1049-1052](../../../src/main/java/com/navneet/executespec/report/ValidationReportService.java)) trusts the accuracy LLM's endpoint count — the *exact* signal the multiplicative path documents as unreliable (read-budget-limited undercount on runs 7483896a / 0444c95f) and protects with a "floor `endpointFactor` at `reviewerFactor` when reviewer is CLEAN/FIXED" trust-gate ([line 1278](../../../src/main/java/com/navneet/executespec/report/ValidationReportService.java)). The additive path has **no** equivalent trust-gate. Net: an honest CLEAN-reviewer run whose endpoints the LLM under-reads (e.g. 40%) loses ~9–15 of the 30-pt CODE_DELIVERED slot (~3–5 headline pts). Not the old cliff, but a known-bad signal back in the headline. CLAUDE.md even claims "endpointFactor is gone: the reviewer's wiring pass already catches this" — yet guard-A quietly brought the endpoint % back without the wiring-pass floor. **Fix:** gate the guard-A blend behind reviewer-CLEAN/FIXED (mirror the multiplicative floor), or only blend a *verified* endpoint count.
- 🟢 `countUnfixedCritical` filters `SYNTH-ACC-*` (LLM read-budget hallucinations) out of the headline penalty — belt-and-braces (id-prefix + `isAccuracyHallucinationDescription`), while keeping them in the dashboard issue panel. Strong honesty engineering.
- 🟢 Additive TESTS slot handles **all four** documented coverage bands (GOOD/COMPREHENSIVE/PARTIAL/NONE/UNKNOWN, [line 1089-1096](../../../src/main/java/com/navneet/executespec/report/ValidationReportService.java)) — unlike the dashboard's `ReviewScoringService.llmCoverageLevel`, which drops GOOD to its default ([scoring.md](scoring.md)). So the verifiedScore reads the bands correctly even though the dimension bar doesn't.
- 🟢 R-09 `reconcileWithRunStatus` makes `pipelineResult` agree with the orchestrator's authoritative `status_reason` (trust order: reason > heuristic) — retired a frontend special-case.

### Cost (the priority)
- 🔵 **Usage emails report cost at stale blended-Haiku rates — systematically low.** `UsageReportService.estimateCost` hardcodes `$0.80/1M in + $4.00/1M out` ([line 268-270](../../../src/main/java/com/navneet/executespec/report/UsageReportService.java)) "blended Haiku" — but the platform default tier is now **Sonnet/gpt-5.4** (WORKHORSE), so per-org/user cost in the emailed report under-counts real spend. Meanwhile `pipeline_run.actual_cost_usd` already stores the authoritative per-run USD (RunCostTracker, cache-aware — [cost.md](cost.md)) and the landing/exec-demo queries (`execSnapshotTotals`, `spendByProjectSince`, `topWinsSince`) sum exactly that column. Two surfaces, two different cost numbers for the same runs — and the customer-facing email is the wrong one. **Fix:** sum `actual_cost_usd` instead of re-deriving from tokens.

### Tech-debt — dead writes / abandoned Phase-B
- 🟡 **`rubricScore` is a dead write.** Computed every run (`computeRubricScore` + `rubricTestPointsFromCoverageLevel`, ~80 LOC), persisted (V194 column + `ValidationReportDTO.rubricScore`), but **zero frontend references** (`grep rubricScore frontend/src` = empty) despite the entity/DTO javadoc asserting "UI picks the more informative of the two (Phase B)". Phase B never landed. Pairs with the dead `scoring/RunRubricRow` Phase A ([scoring.md](scoring.md)) — **the scoring revamp left abandoned Phase-A scaffolding in two packages** (V194 `rubric_score` write-only; V197 `run_rubric_row` never-written). Either wire Phase B or delete the write path + DTO field.
- 🟡 **Backfill scores on a different basis than live runs.** `VerifiedScoreBackfillService` calls the 6-arg `computeVerifiedScore` → `filesGenerated=-1` → additive path skips the whole 30-pt CODE_DELIVERED slot (it's inside `if (codeKnown)`), so backfilled rows are averaged over BUILD+TESTS+REVIEWER+CRITICALS (≤70 weight) while live rows include CODE_DELIVERED≈100. Dropping a ~100 slot pulls the average **down** → backfilled historical scores skew lower than a live re-run would produce. One-shot admin tool, low blast radius, but the two populations aren't comparable.
- 🟡 **1,524-LOC god-service.** Mixes report assembly + issue extraction + **three** score formulas (additive / multiplicative / rubric) + DTO mapping + share tokens + fallback markdown + failure-signature grouping. The scoring math (3 formulas, ~10 overloads from historical call-site accretion: `computeVerifiedScore`×5, additive×2, multiplicative×2, rubric×2) is a cohesive unit that wants to be a `VerifiedScoreCalculator`.

### Stale-docs / drift
- 📝 `ValidationReportService.java:324` comment "Issues from ReviewerAgentV2" — 2nd occurrence of the non-existent `ReviewerAgentV2` (1st in [reviewer.md](reviewer.md) `ReviewReport.java`).
- 📝 `VerifiedScoreBackfillService` top comment "(see SuperAdminController.backfillVerifiedScore — to be added separately)" — stale; it WAS added (`SuperAdminController:120`).
- 🟡 `ValidationReportGenerator` loads `prompts/validation-report.txt` via `Resource.getContentAsString` directly ([line 51](../../../src/main/java/com/navneet/executespec/report/ValidationReportGenerator.java)) — the one pattern CLAUDE.md explicitly forbids ("Never use `Resource.getContentAsString()` directly in agents"; use `PromptAssembler`). Minor (no markers in this prompt, narrator not pipeline agent) but a literal violation.

### Good
- 🟢 Controller: every authed endpoint calls `runAccessGuard.requireRunAccess`; the public `/reports/shared/{token}` is token-only by design. Share token = 32-byte `SecureRandom` URL-base64. Clean.
- 🟢 LLM-narration failure is non-fatal — `buildFallbackMarkdown` always persists a structured report; the markdown is cosmetic (never feeds scoring).

## Cross-refs
Scoring inputs (issues/accuracy/verdict) → [reviewer.md](reviewer.md). Dashboard dimensions → [scoring.md](scoring.md) (`ReviewScoringService`; the dead `RunRubricRow` Phase A). Per-run USD truth → [cost.md](cost.md) (`RunCostTracker` → `pipeline_run.actual_cost_usd`). Report assembly caller + `status_reason` source → `integration/RunOrchestrator` (Tier 3). Test re-score trigger → `testgen/` (Tier 6). LLM narrator router → [agent.md](agent.md) (`FallbackLlmRouter`).
