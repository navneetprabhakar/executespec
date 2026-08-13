-- Explicit Phase 0A disposition. V385 deliberately left historical BUILD protocol NULL so the
-- candidate could not guess from the live rollout flag. This forward migration labels that history
-- LEGACY_UNKNOWN and makes its non-executable disposition durable without changing product status,
-- generation state, task state, operation state, or publication evidence.
ALTER TABLE pipeline_run
    ADD COLUMN build_protocol_disposition VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD CONSTRAINT pipeline_run_build_protocol_disposition_chk CHECK (
        build_protocol_disposition IN (
            'AUTO_RECOVERABLE','QUARANTINED_CONFLICT','MISSING_EVIDENCE',
            'OPERATOR_RESOLVED','NOT_APPLICABLE'
        )
    );

ALTER TABLE workspace_generation_attempt
    ADD COLUMN historical_disposition VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD CONSTRAINT workspace_generation_historical_disposition_chk CHECK (
        historical_disposition IN (
            'AUTO_RECOVERABLE','QUARANTINED_CONFLICT','MISSING_EVIDENCE',
            'OPERATOR_RESOLVED','NOT_APPLICABLE'
        )
    );

-- Only this migration may turn V385's historical NULL into explicit UNKNOWN. Ordinary application
-- updates remain immutable before and after this bounded statement.
DROP TRIGGER pipeline_run_build_protocol_version_immutable ON pipeline_run;
DROP TRIGGER workspace_generation_build_protocol_version_binding ON workspace_generation_attempt;
ALTER TABLE workspace_generation_attempt DISABLE TRIGGER workspace_generation_attempt_transition;

UPDATE pipeline_run p
   SET build_protocol_version = 'LEGACY_UNKNOWN',
       build_protocol_disposition = CASE
           WHEN EXISTS (SELECT 1 FROM workspace_generation_attempt a WHERE a.run_id = p.run_id)
               THEN 'MISSING_EVIDENCE'
           ELSE 'NOT_APPLICABLE'
       END
 WHERE p.run_mode = 'BUILD'
   AND p.build_protocol_version IS NULL;

UPDATE workspace_generation_attempt a
   SET build_protocol_version = 'LEGACY_UNKNOWN',
       historical_disposition = CASE
           WHEN a.state = 'RECOVERY_FAILED' THEN 'QUARANTINED_CONFLICT'
           WHEN a.state = 'ACCEPTED' AND EXISTS (
               SELECT 1 FROM llm_execution_operation o
                WHERE o.id = a.llm_operation_id AND o.status <> 'SUCCEEDED'
           ) THEN 'QUARANTINED_CONFLICT'
           WHEN a.state = 'ROLLED_BACK' AND EXISTS (
               SELECT 1 FROM llm_execution_operation o
                WHERE o.id = a.llm_operation_id AND o.status = 'SUCCEEDED'
           ) THEN 'QUARANTINED_CONFLICT'
           ELSE 'MISSING_EVIDENCE'
       END
 WHERE a.build_protocol_version IS NULL;

-- Promote the parent disposition when any child contains a proven terminal contradiction. The
-- first run update above intentionally happened before attempt classification so the attempt rows
-- could still be selected solely from their original evidence.
UPDATE pipeline_run p
   SET build_protocol_disposition = CASE
       WHEN EXISTS (
           SELECT 1 FROM workspace_generation_attempt a
            WHERE a.run_id = p.run_id
              AND a.historical_disposition = 'QUARANTINED_CONFLICT'
       ) THEN 'QUARANTINED_CONFLICT'
       WHEN EXISTS (SELECT 1 FROM workspace_generation_attempt a WHERE a.run_id = p.run_id)
           THEN 'MISSING_EVIDENCE'
       ELSE 'NOT_APPLICABLE'
   END
 WHERE p.run_mode = 'BUILD'
   AND p.build_protocol_version = 'LEGACY_UNKNOWN';

ALTER TABLE workspace_generation_attempt ENABLE TRIGGER workspace_generation_attempt_transition;

CREATE OR REPLACE FUNCTION enforce_pipeline_run_build_protocol_version()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.run_mode = 'BUILD' AND NEW.build_protocol_version IS NULL THEN
            NEW.build_protocol_disposition := 'MISSING_EVIDENCE';
        ELSIF NEW.build_protocol_version IN ('LEGACY_V1','SHARED_KERNEL_V2') THEN
            NEW.build_protocol_disposition := 'NOT_APPLICABLE';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE'
       AND (NEW.build_protocol_version IS DISTINCT FROM OLD.build_protocol_version
            OR NEW.build_protocol_disposition IS DISTINCT FROM OLD.build_protocol_disposition) THEN
        RAISE EXCEPTION 'pipeline run BUILD protocol identity/disposition is immutable';
    END IF;
    IF NEW.build_protocol_version IS NOT NULL AND NEW.run_mode IS DISTINCT FROM 'BUILD' THEN
        RAISE EXCEPTION 'non-BUILD run cannot carry a BUILD protocol version';
    END IF;
    IF NEW.build_protocol_version = 'LEGACY_UNKNOWN'
       AND NEW.build_protocol_disposition NOT IN ('MISSING_EVIDENCE','QUARANTINED_CONFLICT',
                                                   'OPERATOR_RESOLVED','NOT_APPLICABLE') THEN
        RAISE EXCEPTION 'unknown BUILD protocol has invalid disposition';
    END IF;
    IF NEW.build_protocol_version IN ('LEGACY_V1','SHARED_KERNEL_V2')
       AND NEW.build_protocol_disposition <> 'NOT_APPLICABLE' THEN
        RAISE EXCEPTION 'supported BUILD protocol cannot carry historical disposition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pipeline_run_build_protocol_version_immutable
BEFORE INSERT OR UPDATE OF build_protocol_version,build_protocol_disposition,run_mode ON pipeline_run
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
        IF parent_version IS NULL AND NEW.build_protocol_version IS NULL THEN
            NEW.historical_disposition := 'MISSING_EVIDENCE';
        END IF;
        IF parent_version IS NOT NULL
           AND NEW.build_protocol_version IS DISTINCT FROM parent_version THEN
            RAISE EXCEPTION 'workspace generation BUILD protocol does not match parent run';
        END IF;
        IF parent_version IS NULL AND NEW.build_protocol_version IS NOT NULL THEN
            RAISE EXCEPTION 'workspace generation cannot invent a BUILD protocol version';
        END IF;
        IF NEW.build_protocol_version IN ('LEGACY_V1','SHARED_KERNEL_V2')
           AND NEW.historical_disposition <> 'NOT_APPLICABLE' THEN
            RAISE EXCEPTION 'supported generation protocol cannot carry historical disposition';
        END IF;
    ELSE
        IF NEW.build_protocol_version IS DISTINCT FROM OLD.build_protocol_version
           OR NEW.historical_disposition IS DISTINCT FROM OLD.historical_disposition THEN
            RAISE EXCEPTION 'workspace generation BUILD protocol identity/disposition is immutable';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_build_protocol_version_binding
BEFORE INSERT OR UPDATE OF build_protocol_version,historical_disposition,run_id,state
ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION bind_workspace_generation_build_protocol_version();

COMMENT ON COLUMN pipeline_run.build_protocol_disposition IS
    'Immutable Phase 0A disposition; historical unknown rows remain non-executable and non-publishable';
COMMENT ON COLUMN workspace_generation_attempt.historical_disposition IS
    'Immutable initial historical classification; operator adjudication must use an append-only receipt';
