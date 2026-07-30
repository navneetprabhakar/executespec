-- Bind validation reports and their derived evidence ledgers to the exact Git workspace
-- generation that was measured. Historical rows stay NULL: guessing a generation would turn
-- unbound evidence into a false deterministic claim.
ALTER TABLE validation_report
    ADD COLUMN IF NOT EXISTS workspace_generation VARCHAR(64);

ALTER TABLE validation_report
    ADD CONSTRAINT chk_validation_report_workspace_generation
    CHECK (
        workspace_generation IS NULL
        OR workspace_generation ~ '^[0-9a-f]{64}$'
    );
