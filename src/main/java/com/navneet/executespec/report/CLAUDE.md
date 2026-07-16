# `report/` — validation reports and score evidence

`ValidationReportService` is the score/evidence boundary. It persists deterministic build/test/reviewer
signals, derives the internal verified/rubric summaries, and reconstructs the reloadable
`verificationLedger` for authenticated and shared report DTOs.

`ValidationVerificationLedgerBuilder` is the package-local pure collaborator for reconstructing that
ledger from a persisted `ValidationReport` and mapped issues. Keep it side-effect free: it must not
load run state, call providers, or mutate score/report entities. `ValidationReportService` retains the
compatibility seam used by report projections and scoring/rescore flows.

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
- V273 persists E2 executable-verification status, `TESTS_RUN`, `TESTS_PASSED`, failure stage, and
  bounded command/output evidence. Any non-passing verdict forces the pipeline result to FAILED and
  both score summaries to zero; `CLEAN_COMPLETION` cannot override executable failure.

## Migration

- `V270__persist_validation_boot_evidence.sql` adds nullable provenance columns. Historical rows remain
  valid and compile-only reports retain their prior scoring behavior.
- `V273__persist_executable_verification_evidence.sql` adds nullable E2 evidence columns. Null keeps
  historical report behavior; new BUILD runs receive an explicit pass or fail-closed verdict.
