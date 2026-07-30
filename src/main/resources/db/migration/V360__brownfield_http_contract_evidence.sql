-- Deterministic Spring HTTP compatibility evidence.
--
-- The admitted baseline must survive workspace eviction/restoration and instance failover, so its
-- digest and canonical route envelope are bound to pipeline_run rather than existing only under
-- the ephemeral .agent-context directory.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS brownfield_http_baseline_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS brownfield_http_baseline_json JSONB,
    ADD COLUMN IF NOT EXISTS brownfield_http_current_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS brownfield_http_compatibility_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS brownfield_http_compatibility_json JSONB;

ALTER TABLE pipeline_run
    ADD CONSTRAINT chk_pipeline_run_brownfield_http_baseline_digest
        CHECK (
            brownfield_http_baseline_digest IS NULL
            OR brownfield_http_baseline_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_pipeline_run_brownfield_http_current_digest
        CHECK (
            brownfield_http_current_digest IS NULL
            OR brownfield_http_current_digest ~ '^[0-9a-f]{64}$'
        ),
    ADD CONSTRAINT chk_pipeline_run_brownfield_http_status
        CHECK (
            brownfield_http_compatibility_status IS NULL
            OR brownfield_http_compatibility_status IN ('BASELINE', 'COMPATIBLE', 'BREAKING')
        );
