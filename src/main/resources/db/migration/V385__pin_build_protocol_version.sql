-- Expand-only protocol pinning. Historical and PATCH rows remain NULL until Phase 0A disposition;
-- candidate BUILD admission always writes a supported value in the initial run INSERT. Previous
-- binaries can finish their existing NULL-version work against this schema, but cannot create a
-- SHARED_KERNEL_V2 attempt because that identity must come from its parent run.
ALTER TABLE pipeline_run
    ADD COLUMN build_protocol_version VARCHAR(32),
    ADD CONSTRAINT pipeline_run_build_protocol_version_chk CHECK (
        build_protocol_version IS NULL
        OR build_protocol_version IN ('LEGACY_V1','SHARED_KERNEL_V2','LEGACY_UNKNOWN')
    );

ALTER TABLE workspace_generation_attempt
    ADD COLUMN build_protocol_version VARCHAR(32),
    ADD CONSTRAINT workspace_generation_build_protocol_version_chk CHECK (
        build_protocol_version IS NULL
        OR build_protocol_version IN ('LEGACY_V1','SHARED_KERNEL_V2','LEGACY_UNKNOWN')
    );

CREATE OR REPLACE FUNCTION enforce_pipeline_run_build_protocol_version()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND NEW.build_protocol_version IS DISTINCT FROM OLD.build_protocol_version THEN
        RAISE EXCEPTION 'pipeline run BUILD protocol version is immutable';
    END IF;
    IF NEW.build_protocol_version IS NOT NULL AND NEW.run_mode IS DISTINCT FROM 'BUILD' THEN
        RAISE EXCEPTION 'non-BUILD run cannot carry a BUILD protocol version';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_run_build_protocol_version_immutable
BEFORE INSERT OR UPDATE OF build_protocol_version,run_mode ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION enforce_pipeline_run_build_protocol_version();

CREATE OR REPLACE FUNCTION bind_workspace_generation_build_protocol_version()
RETURNS TRIGGER AS $$
DECLARE
    parent_version VARCHAR(32);
BEGIN
    SELECT p.build_protocol_version
      INTO parent_version
      FROM pipeline_run p
     WHERE p.run_id = NEW.run_id;

    IF TG_OP = 'INSERT' THEN
        IF parent_version IS NOT NULL
           AND NEW.build_protocol_version IS DISTINCT FROM parent_version THEN
            RAISE EXCEPTION 'workspace generation BUILD protocol does not match parent run';
        END IF;
        IF parent_version IS NULL AND NEW.build_protocol_version IS NOT NULL THEN
            RAISE EXCEPTION 'workspace generation cannot invent a BUILD protocol version';
        END IF;
    ELSE
        IF NEW.build_protocol_version IS DISTINCT FROM OLD.build_protocol_version THEN
            RAISE EXCEPTION 'workspace generation BUILD protocol version is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_build_protocol_version_binding
BEFORE INSERT OR UPDATE OF build_protocol_version,run_id,state
ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION bind_workspace_generation_build_protocol_version();

COMMENT ON COLUMN pipeline_run.build_protocol_version IS
    'Immutable per-run BUILD protocol selected at initial admission; NULL is historical/PATCH and requires an explicit later migration for disposition';
COMMENT ON COLUMN workspace_generation_attempt.build_protocol_version IS
    'Exact protocol copied from the parent BUILD run when the physical attempt is admitted';
