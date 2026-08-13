-- Bind new workspace-generation checkpoint receipts to the physical artifact identity returned by
-- their storage backend. Nullable columns preserve historical V374-V393 rows; protocol-v2 writers
-- populate all fields and lifecycle admission fails closed when any field is absent.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN before_checkpoint_storage_generation VARCHAR(128),
    ADD COLUMN before_checkpoint_size_bytes BIGINT,
    ADD COLUMN before_checkpoint_crc32c VARCHAR(64),
    ADD COLUMN before_checkpoint_etag VARCHAR(512),
    ADD COLUMN checkpoint_storage_generation VARCHAR(128),
    ADD COLUMN checkpoint_size_bytes BIGINT,
    ADD COLUMN checkpoint_crc32c VARCHAR(64),
    ADD COLUMN checkpoint_etag VARCHAR(512);

ALTER TABLE workspace_generation_attempt
    ADD CONSTRAINT workspace_generation_checkpoint_storage_size_chk CHECK (
        (before_checkpoint_size_bytes IS NULL OR before_checkpoint_size_bytes > 0)
        AND (checkpoint_size_bytes IS NULL OR checkpoint_size_bytes > 0)
    );

COMMENT ON COLUMN workspace_generation_attempt.before_checkpoint_storage_generation IS
    'Physical object/file generation returned by the checkpoint backend for new protocol-v2 writes.';
COMMENT ON COLUMN workspace_generation_attempt.checkpoint_storage_generation IS
    'Physical object/file generation returned by the checkpoint backend for new protocol-v2 writes.';

ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_checkpoint_chk,
    ADD CONSTRAINT workspace_generation_checkpoint_chk CHECK (
        (state IN ('ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED')
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL)
        OR (state = 'ROLLED_BACK' AND before_generation IS NULL
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL)
        OR (state NOT IN ('ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED')
            AND before_generation ~ '^[0-9a-f]{64}$'
            AND repositories_json IS NOT NULL
            AND ignored_artifact_baseline_json IS NOT NULL
            AND ignored_artifact_baseline_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST', 'LOCAL_ARCHIVE')
            AND before_checkpoint_key IS NOT NULL
            AND before_checkpoint_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_completed_at IS NOT NULL
            AND (
                (checkpoint_kind IS NULL AND checkpoint_key IS NULL
                    AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
                    AND checkpoint_completed_at IS NULL)
                OR (checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST', 'LOCAL_ARCHIVE')
                    AND checkpoint_key IS NOT NULL
                    AND checkpoint_generation ~ '^[0-9a-f]{64}$'
                    AND checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND checkpoint_completed_at IS NOT NULL)
            ))
    );

ALTER TABLE pipeline_run
    DROP CONSTRAINT pipeline_run_accepted_generation_chk,
    ADD CONSTRAINT pipeline_run_accepted_generation_chk CHECK (
        (accepted_workspace_generation IS NULL AND accepted_task_index IS NULL
            AND accepted_generation_attempt_id IS NULL
            AND accepted_checkpoint_kind IS NULL AND accepted_checkpoint_key IS NULL
            AND accepted_checkpoint_digest IS NULL
            AND accepted_checkpoint_completed_at IS NULL)
        OR (accepted_workspace_generation ~ '^[0-9a-f]{64}$'
            AND (
                (accepted_task_index = -1 AND accepted_generation_attempt_id IS NULL
                    AND accepted_checkpoint_kind IS NULL AND accepted_checkpoint_key IS NULL
                    AND accepted_checkpoint_digest IS NULL
                    AND accepted_checkpoint_completed_at IS NULL)
                OR (accepted_task_index >= 0 AND accepted_generation_attempt_id IS NOT NULL
                    AND accepted_checkpoint_kind IN ('LOCAL_SAME_HOST', 'LOCAL_ARCHIVE', 'GCS')
                    AND accepted_checkpoint_key IS NOT NULL
                    AND accepted_checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND accepted_checkpoint_completed_at IS NOT NULL)
            ))
    );

CREATE OR REPLACE FUNCTION fence_workspace_checkpoint_storage_identity()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state = 'AUTHORITY_ACQUIRED' AND NEW.state = 'PREPARED' THEN
        IF NEW.before_checkpoint_storage_generation IS NULL
           OR NEW.before_checkpoint_size_bytes IS NULL
           OR NEW.before_checkpoint_crc32c IS NULL
           OR NEW.before_checkpoint_etag IS NULL THEN
            RAISE EXCEPTION 'workspace generation before-checkpoint storage identity is incomplete';
        END IF;
    END IF;
    IF OLD.state = 'CHECKPOINTING' AND NEW.state = 'CHECKPOINTING'
       AND OLD.checkpoint_kind IS NULL AND NEW.checkpoint_kind IS NOT NULL THEN
        IF NEW.checkpoint_storage_generation IS NULL
           OR NEW.checkpoint_size_bytes IS NULL
           OR NEW.checkpoint_crc32c IS NULL
           OR NEW.checkpoint_etag IS NULL THEN
            RAISE EXCEPTION 'workspace generation checkpoint storage identity is incomplete';
        END IF;
    END IF;
    IF OLD.before_checkpoint_storage_generation IS NOT NULL AND (
        NEW.before_checkpoint_storage_generation IS DISTINCT FROM OLD.before_checkpoint_storage_generation
        OR NEW.before_checkpoint_size_bytes IS DISTINCT FROM OLD.before_checkpoint_size_bytes
        OR NEW.before_checkpoint_crc32c IS DISTINCT FROM OLD.before_checkpoint_crc32c
        OR NEW.before_checkpoint_etag IS DISTINCT FROM OLD.before_checkpoint_etag
    ) THEN
        RAISE EXCEPTION 'workspace generation before-checkpoint storage identity is immutable';
    END IF;
    IF OLD.checkpoint_storage_generation IS NOT NULL AND (
        NEW.checkpoint_storage_generation IS DISTINCT FROM OLD.checkpoint_storage_generation
        OR NEW.checkpoint_size_bytes IS DISTINCT FROM OLD.checkpoint_size_bytes
        OR NEW.checkpoint_crc32c IS DISTINCT FROM OLD.checkpoint_crc32c
        OR NEW.checkpoint_etag IS DISTINCT FROM OLD.checkpoint_etag
    ) THEN
        RAISE EXCEPTION 'workspace generation checkpoint storage identity is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_checkpoint_storage_identity
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION fence_workspace_checkpoint_storage_identity();
