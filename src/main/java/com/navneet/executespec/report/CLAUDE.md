# `report/` — validation reports and score evidence

`ValidationReportService` is the score/evidence boundary. It persists deterministic build/test/reviewer
signals, derives the internal verified/rubric summaries, and reconstructs the reloadable
`verificationLedger` for authenticated and shared report DTOs.

`ValidationVerificationLedgerBuilder` is the package-local pure collaborator for reconstructing that
ledger from a persisted `ValidationReport` and mapped issues. Keep it side-effect free: it must not
load run state, call providers, or mutate score/report entities. `ValidationReportService` retains the
compatibility seam used by report projections and scoring/rescore flows.

`RunChangeEvidenceService` is the authoritative baseline-to-final Git evidence boundary. It excludes
pre-existing files and deterministic ExecuteSpec metadata, includes tracked and untracked changes,
and preserves worker/reviewer-or-post-processing/system provenance. `filesGenerated`, test-generation
admission, and goal-doneness consumers must use its product diff rather than workspace inventory.
`ValidationReportValidityService` is the single fail-closed predicate for project score aggregates;
invalid reports are excluded and counted separately.

## Evidence contract

- V270 persists `boot_verdict`, `boot_healthy`, and `boot_reason` whenever the final deterministic
  preview verifier supplied a `BootVerification`. Concrete-test rescoring rehydrates that signal;
  it must never pass `null` merely because the original `ReportInput` is gone.
- Ledger entries label provenance (`DETERMINISTIC`, `LLM_REVIEWER`, `LLM_ASSESSMENT`, `HYBRID`, or
  `INTERNAL_FORMULA`). A score is a summary, not independent proof.
- Public/shared report DTOs expose score metadata and the same sanitized ledger, but not run-goal
  persistence or credentials.
- Missing endpoint coverage is explicitly `UNKNOWN` in the ledger and receives neutral midpoint
  rubric credit (8/15), never full endpoint points. This prevents a public/shared score from being
  inflated by absent evidence while preserving partial credit for non-HTTP or unmeasurable work.
- No approved product diff, deterministic zero spec adherence with named missing requirements, or
  zero required-endpoint coverage forces both headline scores to zero and `pipelineResult=FAILED`.
- Concrete test evidence is monotonic. The run-end workspace suite may establish positive counts;
  a later HTTP/preview test-generation `NOT_APPLICABLE` event carries 0/0 and must not erase those
  counts or lower the score. A later positive concrete summary may still rescore the report.
- `reviewer/AccuracyAssessmentService` receives test files through a separate bounded evidence lane
  (six files / 30K characters). Production-file inference still excludes tests, but the LLM coverage
  judgment must inspect real test content rather than infer `NONE` from an intentionally test-free
  source snippet.

## Migration

- `V270__persist_validation_boot_evidence.sql` adds nullable provenance columns. Historical rows remain
  valid and compile-only reports retain their prior scoring behavior.
