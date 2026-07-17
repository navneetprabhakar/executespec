ALTER TABLE validation_report
    ADD COLUMN executable_tests_run BOOLEAN,
    ADD COLUMN executable_tests_passed BOOLEAN,
    ADD COLUMN executable_verification_status VARCHAR(40),
    ADD COLUMN execution_failure_stage VARCHAR(40),
    ADD COLUMN executable_evidence_json TEXT;
