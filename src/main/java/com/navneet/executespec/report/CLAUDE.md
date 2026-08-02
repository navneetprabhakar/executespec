# `report/` — validation reports and score evidence

Validation-report inputs and test-failure details are wrapped user evidence. Static report and JSON
output policy stays in `SystemMessage`; repository-derived issue descriptions, expected/actual
values, conventions, and generated evidence never receive system authority.

Public-link creation, PDF download, and the legacy public report endpoint apply the
high-confidence generated-secret scanner immediately before publication. Credential-like material
fails closed with 422 and no matched value is returned.

`ValidationReportService` is the score/evidence boundary. It persists deterministic build/test/reviewer
signals, derives the internal verified/rubric summaries, and reconstructs the reloadable
`verificationLedger` for authenticated and shared report DTOs.

`ValidationVerificationLedgerBuilder` is the package-local pure collaborator for reconstructing that
ledger from a persisted `ValidationReport` and mapped issues. Keep it side-effect free: it must not
load run state, call providers, or mutate score/report entities. `ValidationReportService` retains the
compatibility seam used by report projections and scoring/rescore flows.
`ValidationEndpointCoverageEvidence` owns the runtime-surface applicability row so the main builder
stays below the architecture size ceiling while preserving explicit non-HTTP `N/A` evidence.

`RunChangeEvidenceService` is the authoritative baseline-to-final Git evidence boundary. It excludes
pre-existing files and deterministic ExecuteSpec metadata, includes tracked and untracked changes,
and preserves worker/reviewer-or-post-processing/system provenance. `filesGenerated`, test-generation
admission, and goal-doneness consumers must use its product diff rather than workspace inventory.
`ValidationReportValidityService` is the single fail-closed predicate for project score aggregates;
invalid reports are excluded and counted separately.

## Evidence contract

- V361 persists `validation_report.workspace_generation`, the SHA-256 of the canonical Git trees
  measured after deterministic run-end checks. Every reconstructed `VerificationEvidenceDTO`
  inherits that exact value, as do authenticated/shared report DTOs and durable agent-session
  evidence events. Historical nulls remain visibly unbound; no read path may substitute the current
  workspace or otherwise guess a generation.
- V362 persists bounded build/test command, working-directory, exit-code, and output evidence plus
  deterministic GitHub Actions workflow parser evidence. Workflow rows carry stable repository
  paths and content SHA-256 digests; this proves syntax/provenance only, never semantic satisfaction
  of an arbitrary natural-language deployment requirement.
- Reviewer/accuracy findings remain advisory evidence even when model output labels them CRITICAL.
  Only deterministic compiler/build/test/standards/invariant failures may hard-fail the report.
- Accuracy-assessment source citations are model-proposed but deterministically re-read through
  `AccuracyEvidenceCitationPolicy` before persistence. Only canonical workspace-relative
  `path:start-end` locations with an exact readable line range survive in `accuracy_json`.
  Uncited spec, endpoint, and test-coverage judgments remain `UNKNOWN` with an explicit
  `UNAVAILABLE` artifact; a model score alone cannot present a source-backed PASS or FAIL.
- V270 persists `boot_verdict`, `boot_healthy`, and `boot_reason` whenever the final deterministic
  preview verifier supplied a `BootVerification`. Concrete-test rescoring AND the SUPER_ADMIN
  `VerifiedScoreBackfillService` rehydrate that signal through the shared
  `ValidationReportService.bootVerification(report)` helper; no re-derivation path may pass `null`
  merely because the original `ReportInput` is gone. Dropping it silently upgraded an UNHEALTHY
  boot (BUILD slot 55) to a full BUILD slot.
- V273 persists executable-test run/pass state, verification status, failure stage, and bounded
  deterministic JSON. New reports and concrete-test rescoring populate those columns, and the
  reloadable ledger exposes the same evidence. `FAILED` and `UNAVAILABLE` rows are excluded from
  project health; historical nullable rows remain readable for backward compatibility.
- Ledger entries label provenance (`DETERMINISTIC_FUNCTIONAL`,
  `DETERMINISTIC_WORKSPACE_SUITE`, `DETERMINISTIC_EXECUTION` for legacy fresh-but-unscoped rows,
  `UNVERIFIED_COUNT`, `LLM_REVIEWER`, `LLM_ASSESSMENT`, `LLM_ASSISTED_COUNT`, `HYBRID`, or
  `INTERNAL_FORMULA`). A score is a summary, not independent proof.
- Authenticated report DTOs expose `goalProofReferences`: a typed, reloadable projection of the
  durable run lifecycle, validation result, and exact verification-ledger rows used by final goal
  proof. Each reference carries a proof role (`PROVEN`, `BLOCKING`, `UNPROVEN`, or `CONTEXT`),
  observed status, provenance, confidence, artifact pointer, and measured count. Frontends must use
  these references instead of parsing `GoalDonenessDecision.reason`, `recommendedNextStep`, or the
  legacy prose evidence list. Missing rows render as explicit UNPROVEN references with no invented
  artifact.
- V317's `validation_report.test_execution_verified` proves count freshness. V344 adds
  `test_evidence_scope` so freshness is not confused with the boundary exercised:
  `LIVE_PREVIEW_FUNCTIONAL` is independent product-level proof, while `WORKSPACE_TEST_SUITE` is
  deterministic code-level evidence only. Both can render their observed pass/fail status, but
  only a `DETERMINISTIC_FUNCTIONAL`/HIGH tests row can satisfy
  `ValidationReportGoalProofEvaluator` and reach `STOP_DONE`. Null/false freshness renders the row
  `UNKNOWN` with `UNVERIFIED_COUNT`; positive stale or unbound counts never render as pass.
- V371 persists `validation_report.tests_skipped` separately from passed/total. A fresh exit-zero
  suite with at least one pass and `passed + skipped == total` is green code-level evidence and may
  satisfy publication admission; all-skipped, malformed, non-zero-exit, failure, and error
  populations remain blocked. Ordinary run-end validation now persists fresh
  `WORKSPACE_TEST_SUITE` counts instead of discarding them as functional-tests-pending, while the
  existing workspace-only score/goal-proof caps remain intact. Skips are excluded from the scoring
  denominator only for an otherwise successful population; they never turn an all-skipped or
  failing population into a green one.
- `RunChangeEvidenceService` requires an admitted run-start baseline. When no `run_repo_snapshot`
  row exists, a Git workspace is accepted only if its history is a single commit — the case where
  the workspace was initialised for this run and `gitCommit` is filtered out of worker authority.
  Any deeper history fails closed (RR-287): diffing against the repository's first-ever commit
  counted every change since repository creation as this run's delivery, letting a no-op or failed
  run clear the `filesGenerated > 0` hard floor.
- Public/shared report DTOs expose score metadata and the same sanitized ledger, but not run-goal
  persistence or credentials. Public report routes reject non-`COMPLETE` rows so stale evidence
  cannot remain available through either governed project links or legacy report bearers.
- New report shares are `ProjectShareLink.Surface.VALIDATION_REPORT` links with digest-only storage,
  bounded expiry, revocation and access audit. V294 preserves historical `/reports/shared/{token}`
  URLs through a digest-only compatibility lookup with a maximum 30-day migration grace period;
  standalone runs may not mint an ungoverned public report bearer.
- Missing endpoint coverage on an HTTP or historical/unbound report is explicitly `UNKNOWN` in the
  ledger and receives neutral midpoint rubric credit (8/15), never full endpoint points. V367 binds
  new reports to their runtime surface; CLI, STATIC_WEB, LIBRARY and other non-HTTP reports emit an
  explicit `N/A` / `NOT_APPLICABLE` endpoint row rather than pretending evidence is missing. That
  N/A dimension is removed from both scoring numerator and denominator: it earns neither a penalty
  nor synthetic midpoint/full credit.
  Run-level goal proof does not treat an `UNKNOWN` optional row (notably endpoint coverage for a
  STATIC_WEB/CLI/LIBRARY deliverable) as unfinished work; measured WARN/FAIL optional evidence still
  keeps the proof uncertain, and missing spec-adherence evidence remains explicitly unproven.
- No approved product diff is the deterministic delivery hard floor and forces both headline
  scores to zero plus `pipelineResult=FAILED`. LLM-produced spec-adherence, endpoint-coverage and
  coverage-quality findings remain advisory confidence inputs: they may warn or cap a positive
  headline no lower than 1, but cannot create a zero. Every zero report carries a typed
  `score-zero-cause` ledger row bound to exact deterministic artifacts; historical or unbound
  zeros state that the cause is unavailable rather than inferring one.
- Fresh workspace suites remain customer-visible deterministic test evidence, explicitly labelled
  `workspace suite only`; they cannot certify deployed journeys and cap both verified/rubric
  headlines at 75. The independently generated black-box suite exercises the live preview/runtime
  and replaces the earlier population. Events are idempotent overwrite, not arithmetic merge:
  unit tests cannot dilute a failing functional result. Missing, failed, stale, zero-test, or
  provenance-unverified execution persists 0/0/unverified evidence; only a non-empty,
  provenance-verified `LIVE_PREVIEW_FUNCTIONAL` suite can earn full test credit and final-goal proof.
- Automatic-correction admission is synchronous and generation-bound: after read-only review,
  `RunValidationKickoff` re-executes the resolver-owned workspace suite, requires a non-empty green
  and provenance-verified result, and proves the canonical workspace generation is unchanged
  before persisting exact command/working-directory/exit/output/count/scope evidence. Normal run-end
  validation continues to persist `functional-tests-pending` until independent live tests finish.
- Anti-gaming guards (`scoring.gaming-guards`) are tighten-only and must not be defeated by absent
  evidence. With the guards on, a null endpoint-coverage reading gives CODE_DELIVERED the midpoint
  of the guarded range (75), not the full slot, and a missing or unrecognised test-coverage
  judgment caps the TESTS slot at 85 rather than falling through to an uncapped 100.
- The legacy multiplicative scorer (`scoring.additive-v2` off) is a supported rollback path and
  applies the same RL-2026-06-026 trust caps as the additive scorer. Missing build evidence and an
  unmeasured endpoint signal are neutral UNKNOWN factors rather than failure; any raw zero caused
  only by LLM-derived factors is lifted to 1, while explicit deterministic build/boot/test failures
  may still produce zero.
- `reviewer/AccuracyAssessmentService` receives test files through a separate bounded evidence lane
  (six files / 30K characters). Production-file inference still excludes tests, but the LLM coverage
  judgment must inspect real test content rather than infer `NONE` from an intentionally test-free
  source snippet.
- Final assessment is tighten-only: `CLEAN_*` pre-report status cannot erase a newly measured
  `NEEDS_ATTENTION` result; successfully auto-fixed visibility issues are not open blockers; and an
  accuracy report with admitted missing/partial/unknown requirements caps the headline/rubric score
  at its spec-adherence score. Unknown requirements additionally cap verified/rubric projections at
  84 (below the 85 passing band), so an internally inconsistent 100/100 assessment cannot coexist
  with an unproven release badge; legacy report DTOs and evidence-ledger score summaries apply the
  cap on read, while new reports persist it during score generation. The shared
  `ValidationReportScoreProjection` is authoritative for report, shared-report, run-list, and
  run-detail reads; those surfaces must never expose the uncapped persisted snapshot after newer
  accuracy evidence admits a gap. `AccuracyReportTruthPolicy` moves UNKNOWN entries that themselves state
  negative implementation evidence to PARTIAL before scoring. Any admitted partial requirement
  caps accuracy at 79/C, an admitted missing requirement caps it at 69/D, and genuinely unverified
  requirements cap it at 89/B. UNKNOWN is not treated as a defect, but its ledger row is WARN and
  cannot prove the run goal DONE. The assessor prompt
  requires readable route/runtime/error/idempotency/migration evidence; test names, mocked fixtures,
  OpenAPI entries, and raw exception strings cannot prove those contracts. Lexical grep hits no
  longer demote semantic MISSING findings to UNKNOWN: the release-blocker audit showed that words
  such as `rollback` and `version` can exist in code/tests while the required behavior is absent.
  The assessor receives `Plan.requirements()` as the canonical ledger, classifies every preserved
  ID/full clause, and falls back to extracting requirements from original prose only for legacy
  plans with an empty ledger.
- SUPER_ADMIN verified-score backfill replays the same admitted-gap cap from persisted
  `accuracy_json`. Historical partial requirements cap both recomputed headline scores at 79 (or
  the lower stored adherence score), missing requirements cap them at 69, and UNKNOWN-only evidence
  caps them at 89. A maintenance rescore must never erase known requirement gaps or inflate a row
  above the live report policy.

## Migration

- `V270__persist_validation_boot_evidence.sql` adds nullable provenance columns. Historical rows remain
  valid and compile-only reports retain their prior scoring behavior.
- `V273__persist_executable_verification_evidence.sql` adds nullable executable-verification columns.
  The migration is immutable; Java persistence and ledger reconstruction are owned by
  `ValidationReportService`, `ValidationReport`, and `ValidationVerificationLedgerBuilder`.
- `V294__retire_legacy_validation_report_bearers.sql` hashes and clears historical plaintext
  bearers, bounds their compatibility lifetime, and installs a rolling-deploy trigger that
  normalizes any write from an older application node before commit.
- `V361__validation_workspace_generation.sql` adds the nullable, format-constrained workspace
  generation binding. Null is reserved for pre-V361/unbound evidence.
- `V362__persist_validation_command_and_workflow_evidence.sql` adds reloadable command execution and
  workflow-parser evidence. Historical rows remain nullable and therefore visibly unmeasured.
- `V366__persist_score_zero_cause.sql` adds nullable `score_zero_cause_json`. New zero headlines
  persist their exact generation-time deterministic cause; historical and nonzero rows stay null,
  and readers must not reverse-infer causality from adjacent report fields.
- `V367__validation_report_runtime_surface.sql` adds nullable `runtime_surface`. New reports bind
  endpoint applicability to the persisted plan surface; null remains historical/unknown and is
  never interpreted as non-HTTP.
