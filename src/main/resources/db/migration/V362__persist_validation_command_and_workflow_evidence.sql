-- Persist deterministic workflow/config parser evidence and the exact command observations used
-- by final validation. Historical rows remain NULL rather than receiving fabricated evidence.
ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS workflow_config_evidence_json TEXT,
    ADD COLUMN IF NOT EXISTS build_command TEXT,
    ADD COLUMN IF NOT EXISTS build_working_directory TEXT,
    ADD COLUMN IF NOT EXISTS build_exit_code INTEGER,
    ADD COLUMN IF NOT EXISTS build_output TEXT,
    ADD COLUMN IF NOT EXISTS test_command TEXT,
    ADD COLUMN IF NOT EXISTS test_working_directory TEXT,
    ADD COLUMN IF NOT EXISTS test_exit_code INTEGER,
    ADD COLUMN IF NOT EXISTS test_output TEXT;
