-- Validation Report: stores LLM-generated pipeline validation summaries per run
CREATE TABLE validation_report (
    id              TEXT PRIMARY KEY,
    run_id          TEXT NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,

    -- Structured data
    files_generated     INTEGER NOT NULL DEFAULT 0,
    languages           TEXT NOT NULL DEFAULT '',
    pipeline_result     VARCHAR(30) NOT NULL DEFAULT 'PASSED',
    issues_json         TEXT NOT NULL DEFAULT '[]',
    checks_passed       TEXT NOT NULL DEFAULT '',

    -- LLM-generated report
    report_markdown     TEXT,
    model_used          VARCHAR(50),
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    generation_time_ms  BIGINT,

    -- Sharing
    share_token         VARCHAR(64) UNIQUE,
    shared_at           TIMESTAMPTZ,

    -- Metadata
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_validation_report_run_id ON validation_report(run_id);
CREATE INDEX idx_validation_report_share_token ON validation_report(share_token) WHERE share_token IS NOT NULL;
