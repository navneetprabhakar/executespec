-- V317 — RR-1660: persist the execution-provenance token alongside the concrete test counts.
--
-- RR-1646 downgraded the `tests` and count-derived `executable-verification` evidence-ledger rows
-- from DETERMINISTIC/HIGH to UNVERIFIED_COUNT/MEDIUM because no signal existed to distinguish a
-- real test run from stale report XML left on disk by an earlier attempt (RR-1582). That signal now
-- exists: producers set TestExecutionSummary.executionVerified only when the report directory was
-- reset before launch, the runner's exit status was checked, and every parsed report provably
-- postdates the launch.
--
-- Deliberately THREE-VALUED and nullable. NULL means "the producing path recorded no provenance
-- signal at all" and must be treated as UNPROVEN, never as proven-false: pre-V317 rows and any
-- future producer that does not establish freshness both land here and keep the conservative
-- UNVERIFIED_COUNT/MEDIUM label. Only TRUE restores the DETERMINISTIC/HIGH claim.
ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS test_execution_verified BOOLEAN;

COMMENT ON COLUMN validation_report.test_execution_verified IS
    'RR-1660 three-valued execution-provenance token for tests_passed/tests_total. TRUE = the '
    'counts were proven to come from this run''s own test execution (reports reset before launch, '
    'exit status checked, every report postdates launch). FALSE = the producer explicitly could '
    'not prove it. NULL = no provenance signal was recorded; treat as unproven, not proven-false.';
