-- Asynchronous execution evidence must settle against the report generation it measured.
ALTER TABLE validation_report
    ADD COLUMN concrete_test_execution_id VARCHAR(255),
    ADD COLUMN concrete_test_started_at TIMESTAMPTZ;
