# `report/` — validation reports and score evidence

`ValidationReportService` is the score/evidence boundary. It persists deterministic build/test/reviewer
signals, derives the internal verified/rubric summaries, and reconstructs the reloadable
`verificationLedger` for authenticated and shared report DTOs.

## Evidence contract

- V268 persists `boot_verdict`, `boot_healthy`, and `boot_reason` whenever the final deterministic
  preview verifier supplied a `BootVerification`. Concrete-test rescoring rehydrates that signal;
  it must never pass `null` merely because the original `ReportInput` is gone.
- Ledger entries label provenance (`DETERMINISTIC`, `LLM_REVIEWER`, `LLM_ASSESSMENT`, `HYBRID`, or
  `INTERNAL_FORMULA`). A score is a summary, not independent proof.
- Public/shared report DTOs expose score metadata and the same sanitized ledger, but not run-goal
  persistence or credentials.
- Missing endpoint coverage remains a backwards-compatible rubric N/A policy (full endpoint points);
  the ledger explicitly marks that signal `UNKNOWN` so consumers cannot mistake it for measured
  coverage. Changing the rubric policy requires a separate versioned scoring decision and tests.

## Migration

- `V268__persist_validation_boot_evidence.sql` adds nullable provenance columns. Historical rows remain
  valid and compile-only reports retain their prior scoring behavior.
