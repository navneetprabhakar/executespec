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

`RunChangeEvidenceService` is the authoritative baseline-to-final Git evidence boundary. It excludes
pre-existing files and deterministic ExecuteSpec metadata, includes tracked and untracked changes,
and preserves worker/reviewer-or-post-processing/system provenance. `filesGenerated`, test-generation
admission, and goal-doneness consumers must use its product diff rather than workspace inventory.
`ValidationReportValidityService` is the single fail-closed predicate for project score aggregates;
invalid reports are excluded and counted separately.

## Evidence contract

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
- Ledger entries label provenance (`DETERMINISTIC`, `UNVERIFIED_COUNT`, `LLM_REVIEWER`,
  `LLM_ASSESSMENT`, `LLM_ASSISTED_COUNT`, `HYBRID`, or `INTERNAL_FORMULA`). A score is a summary,
  not independent proof.
- `DETERMINISTIC` is the only label that claims independence from an LLM. RR-1646 removed it from
  the persisted concrete-test counts because report files read off disk carried no proof they
  described THIS run. **V317 (RR-1660) supplies that proof and restores the label where — and only
  where — it was earned.** `validation_report.test_execution_verified` is a THREE-VALUED token set
  TRUE only when the producing runner reset the report directory before launch, checked the exit
  status, and admitted only reports postdating the launch. The `tests` row, and the
  `executable-verification` row whenever `executable_tests_run` is true, read
  `DETERMINISTIC`/HIGH on TRUE and `UNVERIFIED_COUNT`/MEDIUM otherwise.
  **`null` is unproven, never proven-false** — every pre-V317 row and every producer that
  establishes no freshness lands there and keeps the conservative label. `merged` populations are
  only as proven as their weakest contributor. Row *status* is unchanged, and
  `ValidationReportGoalProofEvaluator` keys on status rather than source/confidence, so restoring
  the strong label does not by itself change run-level goal proof.
- `RunChangeEvidenceService` requires an admitted run-start baseline. When no `run_repo_snapshot`
  row exists, a Git workspace is accepted only if its history is a single commit — the case where
  the workspace was initialised for this run and `gitCommit` is filtered out of worker authority.
  Any deeper history fails closed (RR-287): diffing against the repository's first-ever commit
  counted every change since repository creation as this run's delivery, letting a no-op or failed
  run clear the `filesGenerated > 0` hard floor.
- Public/shared report DTOs expose score metadata and the same sanitized ledger, but not run-goal
  persistence or credentials.
- New report shares are `ProjectShareLink.Surface.VALIDATION_REPORT` links with digest-only storage,
  bounded expiry, revocation and access audit. V294 preserves historical `/reports/shared/{token}`
  URLs through a digest-only compatibility lookup with a maximum 30-day migration grace period;
  standalone runs may not mint an ungoverned public report bearer.
- Missing endpoint coverage is explicitly `UNKNOWN` in the ledger and receives neutral midpoint
  rubric credit (8/15), never full endpoint points. This prevents a public/shared score from being
  inflated by absent evidence while preserving partial credit for non-HTTP or unmeasurable work.
  Run-level goal proof does not treat an `UNKNOWN` optional row (notably endpoint coverage for a
  STATIC_WEB/CLI/LIBRARY deliverable) as unfinished work; measured WARN/FAIL optional evidence still
  keeps the proof uncertain, and missing spec-adherence evidence remains explicitly unproven.
- No approved product diff, deterministic zero spec adherence with named missing requirements, or
  zero required-endpoint coverage forces both headline scores to zero and `pipelineResult=FAILED`.
- Concrete test evidence is monotonic. The run-end workspace suite may establish positive counts;
  a later HTTP/preview test-generation `NOT_APPLICABLE` event carries 0/0 and must not erase those
  counts or lower the score. A later positive concrete summary is **combined** with the workspace
  suite, never substituted for it: the two are different populations, so a 2/3 generated smoke may
  not discard a passing 10/10 worker suite. The merge is anchored on a `baselineTestsTotal` /
  `baselineTestsPassed` pair persisted inside `executable_evidence_json`, so a re-delivered
  execution event recomputes the same counts instead of stacking increments.
- Anti-gaming guards (`scoring.gaming-guards`) are tighten-only and must not be defeated by absent
  evidence. With the guards on, a null endpoint-coverage reading gives CODE_DELIVERED the midpoint
  of the guarded range (75), not the full slot, and a missing or unrecognised test-coverage
  judgment caps the TESTS slot at 85 rather than falling through to an uncapped 100.
- The legacy multiplicative scorer (`scoring.additive-v2` off) is a supported rollback path and
  applies the same RL-2026-06-026 trust caps as the additive scorer. Its neutral factor for an
  unmeasured endpoint signal is intentional — the shape has no denominator to drop a slot from —
  and is safe only because the trust cap constrains the result.
- `reviewer/AccuracyAssessmentService` receives test files through a separate bounded evidence lane
  (six files / 30K characters). Production-file inference still excludes tests, but the LLM coverage
  judgment must inspect real test content rather than infer `NONE` from an intentionally test-free
  source snippet.
- Final assessment is tighten-only: `CLEAN_*` pre-report status cannot erase a newly measured
  `NEEDS_ATTENTION` result; successfully auto-fixed visibility issues are not open blockers; and an
  accuracy report with admitted missing/partial/unknown requirements caps the headline/rubric score
  at its spec-adherence score. `AccuracyReportTruthPolicy` moves UNKNOWN entries that themselves state
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
