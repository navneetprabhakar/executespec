ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS tests_skipped INTEGER NOT NULL DEFAULT 0;

ALTER TABLE validation_report
    ADD CONSTRAINT chk_validation_report_tests_skipped_nonnegative
    CHECK (tests_skipped >= 0);
