-- Make the terminal workspace-generation row the durable owner of the exact physical LLM
-- operation outcome. The intent is written by a database trigger on the same terminal transition,
-- so mixed-version writers cannot commit ACCEPTED/ROLLED_BACK without it.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN operation_transition_target VARCHAR(16),
    ADD COLUMN operation_transition_state VARCHAR(16),
    ADD COLUMN operation_transition_completed_at TIMESTAMPTZ;

-- Existing terminal rows become explicit receipts. A physical mismatch remains PENDING for the
-- reconciler; matching terminal operations are acknowledged without inventing a new provider call.
UPDATE workspace_generation_attempt a
   SET operation_transition_target = CASE a.state
           WHEN 'ACCEPTED' THEN 'SUCCEEDED'
           WHEN 'ROLLED_BACK' THEN 'FAILED'
       END,
       operation_transition_state = CASE
           WHEN (a.state = 'ACCEPTED' AND o.status = 'SUCCEEDED')
             OR (a.state = 'ROLLED_BACK' AND o.status = 'FAILED')
             THEN 'COMPLETED'
           ELSE 'PENDING'
       END,
       operation_transition_completed_at = CASE
           WHEN (a.state = 'ACCEPTED' AND o.status = 'SUCCEEDED')
             OR (a.state = 'ROLLED_BACK' AND o.status = 'FAILED')
             THEN COALESCE(o.completed_at, a.resolved_at, NOW())
           ELSE NULL
       END
  FROM llm_execution_operation o
 WHERE o.id = a.llm_operation_id
   AND a.state IN ('ACCEPTED','ROLLED_BACK');

ALTER TABLE workspace_generation_attempt
    ADD CONSTRAINT workspace_generation_operation_transition_chk CHECK (
        (state NOT IN ('ACCEPTED','ROLLED_BACK')
            AND operation_transition_target IS NULL
            AND operation_transition_state IS NULL
            AND operation_transition_completed_at IS NULL)
        OR (state IN ('ACCEPTED','ROLLED_BACK')
            AND operation_transition_target = CASE state
                WHEN 'ACCEPTED' THEN 'SUCCEEDED'
                WHEN 'ROLLED_BACK' THEN 'FAILED'
            END
            AND operation_transition_state IN ('PENDING','COMPLETED')
            AND ((operation_transition_state = 'PENDING'
                    AND operation_transition_completed_at IS NULL)
                OR (operation_transition_state = 'COMPLETED'
                    AND operation_transition_completed_at IS NOT NULL)))
    );

CREATE OR REPLACE FUNCTION seed_workspace_generation_operation_intent()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state NOT IN ('ACCEPTED','ROLLED_BACK')
       AND NEW.state IN ('ACCEPTED','ROLLED_BACK') THEN
        NEW.operation_transition_target := CASE NEW.state
            WHEN 'ACCEPTED' THEN 'SUCCEEDED'
            WHEN 'ROLLED_BACK' THEN 'FAILED'
        END;
        NEW.operation_transition_state := 'PENDING';
        NEW.operation_transition_completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER workspace_generation_attempt_00_operation_intent
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION seed_workspace_generation_operation_intent();

-- The mature transition guard may exempt exactly one receipt mutation at a time. It must not
-- exempt a combined task+operation rewrite that would bypass both receipt validators.
DROP TRIGGER workspace_generation_attempt_transition ON workspace_generation_attempt;

CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state NOT IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
    AND NOT (
        OLD.state IN ('ACCEPTED','ROLLED_BACK')
        AND NEW.state = OLD.state
        AND (
            (to_jsonb(NEW) - ARRAY[
                'task_transition_state', 'task_transition_completed_at',
                'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
            ]::text[])
              = (to_jsonb(OLD) - ARRAY[
                'task_transition_state', 'task_transition_completed_at',
                'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
              ]::text[])
            OR
            (to_jsonb(NEW) - ARRAY[
                'operation_transition_state', 'operation_transition_completed_at', 'updated_at'
            ]::text[])
              = (to_jsonb(OLD) - ARRAY[
                'operation_transition_state', 'operation_transition_completed_at', 'updated_at'
              ]::text[])
        )
    )
)
EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_generation_operation_transition_receipt()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state NOT IN ('ACCEPTED','ROLLED_BACK')
       OR NEW.state IS DISTINCT FROM OLD.state
       OR NEW.operation_transition_target IS DISTINCT FROM OLD.operation_transition_target THEN
        RAISE EXCEPTION 'resolved workspace generation operation-transition identity is immutable';
    END IF;

    IF OLD.operation_transition_state = 'PENDING'
       AND NEW.operation_transition_state = 'COMPLETED'
       AND OLD.operation_transition_completed_at IS NULL
       AND NEW.operation_transition_completed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'workspace generation operation-transition receipt is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_operation_transition_receipt
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state IN ('ACCEPTED','ROLLED_BACK')
    AND NEW.state = OLD.state
    AND (to_jsonb(NEW) - ARRAY[
        'operation_transition_state', 'operation_transition_completed_at', 'updated_at'
    ]::text[])
      = (to_jsonb(OLD) - ARRAY[
        'operation_transition_state', 'operation_transition_completed_at', 'updated_at'
      ]::text[])
)
EXECUTE FUNCTION enforce_workspace_generation_operation_transition_receipt();

-- A later sequential BUILD attempt cannot start while the prior terminal operation receipt is
-- pending, even if an old binary happened to terminalize the physical operation directly.
CREATE OR REPLACE FUNCTION fence_workspace_generation_operation_transition_admission()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                     'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING')
       AND EXISTS (
           SELECT 1 FROM workspace_generation_attempt prior
            WHERE prior.run_id = NEW.run_id
              AND prior.id <> NEW.id
              AND prior.state IN ('ACCEPTED','ROLLED_BACK')
              AND prior.operation_transition_state <> 'COMPLETED'
       ) THEN
        RAISE EXCEPTION 'workspace generation admission blocked by pending operation transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_attempt_operation_transition_admission
BEFORE INSERT OR UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION fence_workspace_generation_operation_transition_admission();

-- Terminal BUILD publication now depends on the durable receipt, not merely on observing the
-- current physical operation status.
CREATE OR REPLACE FUNCTION fence_build_run_terminal()
RETURNS TRIGGER AS $$
DECLARE
    unresolved BOOLEAN;
BEGIN
    IF NEW.run_mode IS DISTINCT FROM 'BUILD'
       OR NEW.status NOT IN ('COMPLETE','PARTIAL','FAILED')
       OR NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM workspace_generation_attempt a
         WHERE a.run_id = NEW.run_id
           AND (a.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                            'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING',
                            'ROLLING_BACK','RECOVERY_FAILED')
             OR a.task_transition_state = 'PENDING'
             OR (a.state IN ('ACCEPTED','ROLLED_BACK')
                 AND a.operation_transition_state <> 'COMPLETED'))
    ) OR EXISTS (
        SELECT 1
          FROM workspace_generation_attempt a
          JOIN llm_execution_operation o ON o.id = a.llm_operation_id
         WHERE a.run_id = NEW.run_id
           AND a.state IN ('ACCEPTED','ROLLED_BACK')
           AND ((a.operation_transition_target='SUCCEEDED' AND o.status <> 'SUCCEEDED')
             OR (a.operation_transition_target='FAILED' AND o.status <> 'FAILED'))
    ) OR EXISTS (
        SELECT 1 FROM run_workspace_authority w WHERE w.run_id = NEW.run_id
    ) INTO unresolved;
    IF unresolved THEN
        RAISE EXCEPTION 'terminal BUILD run retains unresolved generation state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
