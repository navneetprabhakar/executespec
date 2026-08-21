-- Additive first stage: new runs persist origin at submission; historical rows remain nullable and
-- are interpreted from their already-durable admission fields. A later migration may tighten the
-- column only after deployed parity evidence proves every historical shape.
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS workspace_origin VARCHAR(32);

ALTER TABLE pipeline_run
    ADD CONSTRAINT chk_pipeline_run_workspace_origin
    CHECK (
        workspace_origin IS NULL
        OR workspace_origin IN ('GREENFIELD', 'BROWNFIELD', 'ITERATION', 'MULTI_REPOSITORY')
    );
