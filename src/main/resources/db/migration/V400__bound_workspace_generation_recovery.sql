-- Repair the two contradictory pre-dispatch recovery shapes introduced by V397 and make
-- workspace-generation recovery bounded, backoff-aware, and durably diagnosable.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN recovery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN recovery_last_attempt_at TIMESTAMPTZ,
    ADD COLUMN recovery_next_attempt_at TIMESTAMPTZ,
    ADD COLUMN recovery_support_id UUID,
    ADD COLUMN recovery_exhausted_at TIMESTAMPTZ,
    ADD CONSTRAINT workspace_generation_recovery_schedule_chk CHECK (
        recovery_attempt_count >= 0
        AND ((recovery_attempt_count = 0
                AND recovery_last_attempt_at IS NULL
                AND recovery_support_id IS NULL)
            OR (recovery_attempt_count > 0
                AND recovery_last_attempt_at IS NOT NULL
                AND recovery_support_id IS NOT NULL))
        AND (recovery_next_attempt_at IS NULL
            OR (state = 'RECOVERY_FAILED' AND resolved_at IS NULL
                AND recovery_next_attempt_at >= recovery_last_attempt_at))
        AND (recovery_exhausted_at IS NULL
            OR (state = 'RECOVERY_FAILED' AND resolved_at IS NOT NULL
                AND recovery_next_attempt_at IS NULL
                AND recovery_exhausted_at = resolved_at))
    );

-- A pre-dispatch attempt may have no LLM identity (ADMITTED) or an immutable exact identity
-- (OPERATION_CLAIMED/AUTHORITY_ACQUIRED). Recovery must preserve whichever identity was durably
-- established; requiring it to disappear made the legal rollback transition impossible.
ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_llm_lease_chk,
    DROP CONSTRAINT workspace_generation_checkpoint_chk,
    ADD CONSTRAINT workspace_generation_llm_lease_chk CHECK (
        (state = 'ADMITTED' AND llm_operation_id IS NULL
            AND llm_attempt_number IS NULL AND llm_lease_token IS NULL)
        OR (state IN ('ROLLING_BACK','ROLLED_BACK','RECOVERY_FAILED')
            AND before_generation IS NULL
            AND ((llm_operation_id IS NULL AND llm_attempt_number IS NULL
                    AND llm_lease_token IS NULL)
                OR (llm_operation_id IS NOT NULL AND llm_attempt_number > 0
                    AND llm_lease_token IS NOT NULL)))
        OR (state <> 'ADMITTED'
            AND NOT (state IN ('ROLLING_BACK','ROLLED_BACK','RECOVERY_FAILED')
                AND before_generation IS NULL)
            AND llm_operation_id IS NOT NULL AND llm_attempt_number > 0
            AND llm_lease_token IS NOT NULL)
    ),
    ADD CONSTRAINT workspace_generation_checkpoint_chk CHECK (
        (state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL)
        OR (state IN ('ROLLING_BACK','ROLLED_BACK','RECOVERY_FAILED')
            AND before_generation IS NULL
            AND repositories_json IS NULL
            AND ignored_artifact_baseline_json IS NULL
            AND ignored_artifact_baseline_digest IS NULL
            AND before_checkpoint_kind IS NULL AND before_checkpoint_key IS NULL
            AND before_checkpoint_digest IS NULL AND before_checkpoint_completed_at IS NULL
            AND checkpoint_kind IS NULL AND checkpoint_key IS NULL
            AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
            AND checkpoint_completed_at IS NULL)
        OR (state NOT IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
            AND before_generation ~ '^[0-9a-f]{64}$'
            AND repositories_json IS NOT NULL
            AND ignored_artifact_baseline_json IS NOT NULL
            AND ignored_artifact_baseline_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_kind IN ('GCS','LOCAL_SAME_HOST','LOCAL_ARCHIVE')
            AND before_checkpoint_key IS NOT NULL
            AND before_checkpoint_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_completed_at IS NOT NULL
            AND ((checkpoint_kind IS NULL AND checkpoint_key IS NULL
                    AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
                    AND checkpoint_completed_at IS NULL)
                OR (checkpoint_kind IN ('GCS','LOCAL_SAME_HOST','LOCAL_ARCHIVE')
                    AND checkpoint_key IS NOT NULL
                    AND checkpoint_generation ~ '^[0-9a-f]{64}$'
                    AND checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND checkpoint_completed_at IS NOT NULL)))
    );

DROP INDEX uq_workspace_generation_attempt_active_run;
CREATE UNIQUE INDEX uq_workspace_generation_attempt_active_run
    ON workspace_generation_attempt(run_id)
    WHERE state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED','MUTATING',
                    'VERIFYING','VERIFIED','CHECKPOINTING','ROLLING_BACK')
       OR (state='RECOVERY_FAILED' AND resolved_at IS NULL);

DROP INDEX idx_workspace_generation_attempt_recovery;
CREATE INDEX idx_workspace_generation_attempt_recovery
    ON workspace_generation_attempt(recovery_next_attempt_at, created_at)
    WHERE state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED','MUTATING',
                    'VERIFYING','VERIFIED','CHECKPOINTING','ROLLING_BACK')
       OR (state='RECOVERY_FAILED' AND resolved_at IS NULL);

-- Exhausted RECOVERY_FAILED is a quarantined terminal attempt: rollback is explicitly unproven,
-- but its task and physical LLM operation still need durable terminal reconciliation receipts.
ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_task_transition_chk,
    ADD CONSTRAINT workspace_generation_task_transition_chk CHECK (
        (task_transition_kind IS NULL
            AND task_transition_state IS NULL
            AND task_transition_expected_status IS NULL
            AND task_transition_expected_recovery_attempt IS NULL
            AND task_transition_completed_at IS NULL
            AND task_transition_failure_reason IS NULL
            AND task_transition_failure_at IS NULL)
        OR (task_transition_kind IN ('MARK_PARTIAL','REOPEN_PENDING','MARK_COMPLETE')
            AND ((task_transition_kind='MARK_COMPLETE' AND state='ACCEPTED')
                OR (task_transition_kind IN ('MARK_PARTIAL','REOPEN_PENDING')
                    AND state='ROLLED_BACK')
                OR (task_transition_kind='MARK_PARTIAL' AND state='RECOVERY_FAILED'
                    AND resolved_at IS NOT NULL))
            AND task_transition_state IN ('PENDING','COMPLETED')
            AND task_transition_expected_status IS NOT NULL
            AND ((task_transition_state='PENDING' AND task_transition_completed_at IS NULL)
                OR (task_transition_state='COMPLETED'
                    AND task_transition_completed_at IS NOT NULL))
            AND ((task_transition_failure_reason IS NULL
                    AND task_transition_failure_at IS NULL)
                OR (task_transition_failure_reason IS NOT NULL
                    AND task_transition_failure_at IS NOT NULL)))
    ),
    DROP CONSTRAINT workspace_generation_operation_transition_chk,
    ADD CONSTRAINT workspace_generation_operation_transition_chk CHECK (
        ((state NOT IN ('ACCEPTED','ROLLED_BACK','RECOVERY_FAILED')
                OR (state='RECOVERY_FAILED' AND resolved_at IS NULL))
            AND operation_transition_target IS NULL
            AND operation_transition_state IS NULL
            AND operation_transition_completed_at IS NULL)
        OR ((state IN ('ACCEPTED','ROLLED_BACK')
                OR (state='RECOVERY_FAILED' AND resolved_at IS NOT NULL))
            AND operation_transition_target = CASE state
                WHEN 'ACCEPTED' THEN 'SUCCEEDED' ELSE 'FAILED' END
            AND operation_transition_state IN ('PENDING','COMPLETED')
            AND ((operation_transition_state='PENDING'
                    AND operation_transition_completed_at IS NULL)
                OR (operation_transition_state='COMPLETED'
                    AND operation_transition_completed_at IS NOT NULL)))
    );

CREATE OR REPLACE FUNCTION seed_workspace_generation_operation_intent()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.state NOT IN ('ACCEPTED','ROLLED_BACK')
            AND NEW.state IN ('ACCEPTED','ROLLED_BACK'))
       OR (NEW.state='RECOVERY_FAILED' AND NEW.resolved_at IS NOT NULL
            AND OLD.resolved_at IS NULL) THEN
        NEW.operation_transition_target := CASE NEW.state
            WHEN 'ACCEPTED' THEN 'SUCCEEDED' ELSE 'FAILED' END;
        NEW.operation_transition_state := 'PENDING';
        NEW.operation_transition_completed_at := NULL;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER workspace_generation_attempt_transition ON workspace_generation_attempt;
CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state NOT IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
    AND NOT (
        (OLD.state IN ('ACCEPTED','ROLLED_BACK')
            OR (OLD.state='RECOVERY_FAILED' AND OLD.resolved_at IS NOT NULL))
        AND NEW.state=OLD.state
        AND (
            (to_jsonb(NEW) - ARRAY[
                'task_transition_state','task_transition_completed_at',
                'task_transition_failure_reason','task_transition_failure_at','updated_at'
            ]::text[]) = (to_jsonb(OLD) - ARRAY[
                'task_transition_state','task_transition_completed_at',
                'task_transition_failure_reason','task_transition_failure_at','updated_at'
            ]::text[])
            OR
            (to_jsonb(NEW) - ARRAY[
                'operation_transition_state','operation_transition_completed_at','updated_at'
            ]::text[]) = (to_jsonb(OLD) - ARRAY[
                'operation_transition_state','operation_transition_completed_at','updated_at'
            ]::text[])
        )
    )
)
EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_generation_task_transition_receipt()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT (OLD.state IN ('ACCEPTED','ROLLED_BACK')
            OR (OLD.state='RECOVERY_FAILED' AND OLD.resolved_at IS NOT NULL))
       OR NEW.state IS DISTINCT FROM OLD.state
       OR NEW.task_transition_kind IS DISTINCT FROM OLD.task_transition_kind
       OR NEW.task_transition_expected_status IS DISTINCT FROM OLD.task_transition_expected_status
       OR NEW.task_transition_expected_recovery_attempt
            IS DISTINCT FROM OLD.task_transition_expected_recovery_attempt THEN
        RAISE EXCEPTION 'resolved workspace generation task-transition identity is immutable';
    END IF;
    IF OLD.task_transition_state='PENDING' AND NEW.task_transition_state='COMPLETED' THEN
        IF NEW.task_transition_completed_at IS NULL
           OR NEW.task_transition_failure_reason IS DISTINCT FROM OLD.task_transition_failure_reason
           OR NEW.task_transition_failure_at IS DISTINCT FROM OLD.task_transition_failure_at THEN
            RAISE EXCEPTION 'workspace generation task-transition completion receipt is invalid';
        END IF;
    ELSIF OLD.task_transition_state='PENDING' AND NEW.task_transition_state='PENDING' THEN
        IF NEW.task_transition_completed_at IS NOT NULL
           OR ((NEW.task_transition_failure_reason IS NULL)
               <> (NEW.task_transition_failure_at IS NULL)) THEN
            RAISE EXCEPTION 'workspace generation task-transition failure receipt is invalid';
        END IF;
    ELSE
        RAISE EXCEPTION 'workspace generation task-transition receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER workspace_generation_task_transition_receipt ON workspace_generation_attempt;
CREATE TRIGGER workspace_generation_task_transition_receipt
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    (OLD.state IN ('ACCEPTED','ROLLED_BACK')
        OR (OLD.state='RECOVERY_FAILED' AND OLD.resolved_at IS NOT NULL))
    AND NEW.state=OLD.state
    AND (to_jsonb(NEW) - ARRAY[
        'task_transition_state','task_transition_completed_at',
        'task_transition_failure_reason','task_transition_failure_at','updated_at'
    ]::text[]) = (to_jsonb(OLD) - ARRAY[
        'task_transition_state','task_transition_completed_at',
        'task_transition_failure_reason','task_transition_failure_at','updated_at'
    ]::text[])
)
EXECUTE FUNCTION enforce_workspace_generation_task_transition_receipt();

CREATE OR REPLACE FUNCTION enforce_workspace_generation_operation_transition_receipt()
RETURNS TRIGGER AS $$
BEGIN
    IF NOT (OLD.state IN ('ACCEPTED','ROLLED_BACK')
            OR (OLD.state='RECOVERY_FAILED' AND OLD.resolved_at IS NOT NULL))
       OR NEW.state IS DISTINCT FROM OLD.state
       OR NEW.operation_transition_target IS DISTINCT FROM OLD.operation_transition_target THEN
        RAISE EXCEPTION 'resolved workspace generation operation-transition identity is immutable';
    END IF;
    IF OLD.operation_transition_state='PENDING'
       AND NEW.operation_transition_state='COMPLETED'
       AND OLD.operation_transition_completed_at IS NULL
       AND NEW.operation_transition_completed_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'workspace generation operation-transition receipt is immutable';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER workspace_generation_operation_transition_receipt ON workspace_generation_attempt;
CREATE TRIGGER workspace_generation_operation_transition_receipt
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    (OLD.state IN ('ACCEPTED','ROLLED_BACK')
        OR (OLD.state='RECOVERY_FAILED' AND OLD.resolved_at IS NOT NULL))
    AND NEW.state=OLD.state
    AND (to_jsonb(NEW) - ARRAY[
        'operation_transition_state','operation_transition_completed_at','updated_at'
    ]::text[]) = (to_jsonb(OLD) - ARRAY[
        'operation_transition_state','operation_transition_completed_at','updated_at'
    ]::text[])
)
EXECUTE FUNCTION enforce_workspace_generation_operation_transition_receipt();

CREATE OR REPLACE FUNCTION fence_workspace_generation_operation_transition_admission()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                     'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING')
       AND EXISTS (
           SELECT 1 FROM workspace_generation_attempt prior
            WHERE prior.run_id=NEW.run_id AND prior.id<>NEW.id
              AND (prior.state IN ('ACCEPTED','ROLLED_BACK')
                OR (prior.state='RECOVERY_FAILED' AND prior.resolved_at IS NOT NULL))
              AND prior.operation_transition_state<>'COMPLETED'
       ) THEN
        RAISE EXCEPTION 'workspace generation admission blocked by pending operation transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION fence_build_run_terminal()
RETURNS TRIGGER AS $$
DECLARE unresolved BOOLEAN;
BEGIN
    IF NEW.run_mode IS DISTINCT FROM 'BUILD'
       OR NEW.status NOT IN ('COMPLETE','PARTIAL','FAILED')
       OR NEW.status IS NOT DISTINCT FROM OLD.status THEN RETURN NEW; END IF;
    SELECT EXISTS (
        SELECT 1 FROM workspace_generation_attempt a
         WHERE a.run_id=NEW.run_id
           AND (a.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                            'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING','ROLLING_BACK')
             OR (a.state='RECOVERY_FAILED' AND a.resolved_at IS NULL)
             OR a.task_transition_state='PENDING'
             OR ((a.state IN ('ACCEPTED','ROLLED_BACK')
                    OR (a.state='RECOVERY_FAILED' AND a.resolved_at IS NOT NULL))
                 AND a.operation_transition_state<>'COMPLETED'))
    ) OR EXISTS (
        SELECT 1 FROM workspace_generation_attempt a
        JOIN llm_execution_operation o ON o.id=a.llm_operation_id
         WHERE a.run_id=NEW.run_id
           AND (a.state IN ('ACCEPTED','ROLLED_BACK')
                OR (a.state='RECOVERY_FAILED' AND a.resolved_at IS NOT NULL))
           AND ((a.operation_transition_target='SUCCEEDED' AND o.status<>'SUCCEEDED')
             OR (a.operation_transition_target='FAILED' AND o.status<>'FAILED'))
    ) OR EXISTS (
        SELECT 1 FROM run_workspace_authority w WHERE w.run_id=NEW.run_id
    ) INTO unresolved;
    IF unresolved THEN
        RAISE EXCEPTION 'terminal BUILD run retains unresolved generation state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
