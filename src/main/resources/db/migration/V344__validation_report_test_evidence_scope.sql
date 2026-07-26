ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS test_evidence_scope VARCHAR(40);

COMMENT ON COLUMN validation_report.test_evidence_scope IS
    'Boundary exercised by the proven test population: LIVE_PREVIEW_FUNCTIONAL, WORKSPACE_TEST_SUITE, or UNAVAILABLE. Null is legacy/unknown and never implies functional proof.';
