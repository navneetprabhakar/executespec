-- Phase 0A operator adjudication is append-only. It never rewrites the historical run, task,
-- generation, operation, checkpoint, or publication rows whose causality is incomplete.
CREATE TABLE workspace_generation_historical_adjudication (
    id UUID PRIMARY KEY,
    receipt_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    stage VARCHAR(16) NOT NULL,
    record_kind VARCHAR(16) NOT NULL,
    record_id VARCHAR(255) NOT NULL,
    run_id VARCHAR(255) NOT NULL,
    action VARCHAR(40) NOT NULL,
    source_disposition VARCHAR(32) NOT NULL,
    effective_disposition VARCHAR(32) NOT NULL,
    retention_hold BOOLEAN NOT NULL,
    before_state_bytes TEXT NOT NULL,
    before_state_sha256 VARCHAR(64) NOT NULL,
    proposed_change_bytes TEXT NOT NULL,
    proposed_change_sha256 VARCHAR(64) NOT NULL,
    dry_run_id UUID,
    actor_user_id VARCHAR(255) NOT NULL,
    operator_reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT workspace_generation_historical_adjudication_stage_chk
        CHECK (stage IN ('DRY_RUN','APPLIED')),
    CONSTRAINT workspace_generation_historical_adjudication_kind_chk
        CHECK (record_kind IN ('RUN','ATTEMPT')),
    CONSTRAINT workspace_generation_historical_adjudication_action_chk
        CHECK (action IN (
            'KEEP_QUARANTINED','CLOSE_AS_NON_EXECUTABLE','RELEASE_FOR_DELETION'
        )),
    CONSTRAINT wg_historical_adjudication_source_disposition_chk
        CHECK (source_disposition IN ('QUARANTINED_CONFLICT','MISSING_EVIDENCE','NOT_APPLICABLE')),
    CONSTRAINT wg_historical_adjudication_effective_disposition_chk
        CHECK (effective_disposition IN (
            'QUARANTINED_CONFLICT','MISSING_EVIDENCE','OPERATOR_RESOLVED','NOT_APPLICABLE'
        )),
    CONSTRAINT workspace_generation_historical_adjudication_digest_chk CHECK (
        before_state_sha256 ~ '^[0-9a-f]{64}$'
        AND proposed_change_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT workspace_generation_historical_adjudication_reason_chk
        CHECK (length(operator_reason) BETWEEN 30 AND 2000),
    CONSTRAINT workspace_generation_historical_adjudication_stage_binding_chk CHECK (
        (stage = 'DRY_RUN' AND dry_run_id IS NULL)
        OR (stage = 'APPLIED' AND dry_run_id IS NOT NULL)
    ),
    CONSTRAINT workspace_generation_historical_adjudication_action_binding_chk CHECK (
        (action = 'KEEP_QUARANTINED'
            AND effective_disposition = source_disposition AND retention_hold = TRUE)
        OR (action = 'CLOSE_AS_NON_EXECUTABLE'
            AND effective_disposition = 'OPERATOR_RESOLVED' AND retention_hold = TRUE)
        OR (action = 'RELEASE_FOR_DELETION'
            AND effective_disposition = source_disposition AND retention_hold = FALSE)
    ),
    CONSTRAINT workspace_generation_historical_adjudication_dry_run_fk
        FOREIGN KEY (dry_run_id)
        REFERENCES workspace_generation_historical_adjudication(id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_workspace_generation_historical_adjudication_apply
    ON workspace_generation_historical_adjudication(dry_run_id)
    WHERE stage = 'APPLIED';

CREATE INDEX idx_workspace_generation_historical_adjudication_record
    ON workspace_generation_historical_adjudication(record_kind, record_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_historical_adjudication_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'workspace generation historical adjudication is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_historical_adjudication_append_only
BEFORE UPDATE OR DELETE ON workspace_generation_historical_adjudication
FOR EACH ROW EXECUTE FUNCTION reject_historical_adjudication_mutation();

CREATE OR REPLACE FUNCTION bind_workspace_generation_historical_adjudication_apply()
RETURNS TRIGGER AS $$
DECLARE
    dry workspace_generation_historical_adjudication%ROWTYPE;
BEGIN
    IF NEW.stage = 'DRY_RUN' THEN
        RETURN NEW;
    END IF;

    SELECT * INTO dry
      FROM workspace_generation_historical_adjudication
     WHERE id = NEW.dry_run_id
       AND stage = 'DRY_RUN'
     FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'historical adjudication apply lacks dry-run receipt';
    END IF;
    IF NEW.record_kind IS DISTINCT FROM dry.record_kind
       OR NEW.record_id IS DISTINCT FROM dry.record_id
       OR NEW.run_id IS DISTINCT FROM dry.run_id
       OR NEW.action IS DISTINCT FROM dry.action
       OR NEW.source_disposition IS DISTINCT FROM dry.source_disposition
       OR NEW.effective_disposition IS DISTINCT FROM dry.effective_disposition
       OR NEW.retention_hold IS DISTINCT FROM dry.retention_hold
       OR NEW.before_state_bytes IS DISTINCT FROM dry.before_state_bytes
       OR NEW.before_state_sha256 IS DISTINCT FROM dry.before_state_sha256
       OR NEW.proposed_change_bytes IS DISTINCT FROM dry.proposed_change_bytes
       OR NEW.proposed_change_sha256 IS DISTINCT FROM dry.proposed_change_sha256 THEN
        RAISE EXCEPTION 'historical adjudication apply does not match dry-run receipt';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_historical_adjudication_apply_binding
BEFORE INSERT ON workspace_generation_historical_adjudication
FOR EACH ROW EXECUTE FUNCTION bind_workspace_generation_historical_adjudication_apply();

-- Quarantine is meaningless if the ordinary tier/GDPR/project-cascade sweep can erase its source
-- evidence. A release is a distinct, append-only operator decision; CLOSE_AS_NON_EXECUTABLE does
-- not silently release retention and RELEASE_FOR_DELETION does not manufacture a disposition.
CREATE OR REPLACE FUNCTION enforce_legacy_build_history_retention_hold()
RETURNS TRIGGER AS $$
DECLARE
    released BOOLEAN;
    released_before_state TEXT;
    current_before_state TEXT;
BEGIN
    IF OLD.run_mode <> 'BUILD'
       OR COALESCE(OLD.build_protocol_version, 'LEGACY_UNKNOWN') <> 'LEGACY_UNKNOWN' THEN
        RETURN OLD;
    END IF;

    SELECT COALESCE((
        SELECT NOT a.retention_hold
          FROM workspace_generation_historical_adjudication a
         WHERE a.stage = 'APPLIED'
           AND a.record_kind = 'RUN'
           AND a.record_id = OLD.run_id
           AND a.run_id = OLD.run_id
         ORDER BY a.receipt_sequence DESC
         LIMIT 1
    ), FALSE), (
        SELECT a.before_state_bytes
          FROM workspace_generation_historical_adjudication a
         WHERE a.stage = 'APPLIED'
           AND a.record_kind = 'RUN'
           AND a.record_id = OLD.run_id
           AND a.run_id = OLD.run_id
         ORDER BY a.receipt_sequence DESC
         LIMIT 1
    ) INTO released, released_before_state;

    IF NOT released THEN
        RAISE EXCEPTION
            'legacy BUILD history is under retention hold; append an applied release receipt first';
    END IF;

    current_before_state := jsonb_build_object(
        'recordKind', 'RUN', 'runId', OLD.run_id, 'runMode', OLD.run_mode,
        'protocolVersion', COALESCE(OLD.build_protocol_version, 'LEGACY_UNKNOWN'),
        'sourceDisposition', OLD.build_protocol_disposition, 'productState', OLD.status,
        'statusReason', OLD.status_reason,
        'acceptedGeneration', OLD.accepted_workspace_generation,
        'acceptedTaskIndex', OLD.accepted_task_index,
        'acceptedCheckpointKind', OLD.accepted_checkpoint_kind,
        'acceptedCheckpointDigest', OLD.accepted_checkpoint_digest,
        'workspaceReferenceRecorded',
            (NULLIF(OLD.workspace_path, '') IS NOT NULL
                OR NULLIF(OLD.gcs_workspace_key, '') IS NOT NULL),
        'checkpointReferenceRecorded',
            (NULLIF(OLD.accepted_checkpoint_key, '') IS NOT NULL
                AND NULLIF(OLD.accepted_checkpoint_digest, '') IS NOT NULL)
    )::text;
    IF current_before_state IS DISTINCT FROM released_before_state THEN
        RAISE EXCEPTION
            'legacy BUILD history changed after deletion release; create a fresh dry-run receipt';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_run_legacy_build_history_retention_hold
BEFORE DELETE ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION enforce_legacy_build_history_retention_hold();

COMMENT ON TABLE workspace_generation_historical_adjudication IS
    'Append-only Phase 0A dry-run/apply receipts; source BUILD history is never rewritten and legacy deletion requires an explicit release';
