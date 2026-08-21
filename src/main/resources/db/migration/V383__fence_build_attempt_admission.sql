-- Reconcile rows created by the V380-era normal rollback path, which intentionally left a
-- retryable QUEUED operation after the physical workspace attempt was already terminal.
UPDATE llm_execution_operation o
   SET status=CASE WHEN a.state='ACCEPTED' THEN 'SUCCEEDED' ELSE 'FAILED' END,
       completed_at=COALESCE(o.completed_at,NOW()),updated_at=NOW(),
       last_error_code=CASE WHEN a.state='ROLLED_BACK'
                            THEN 'WORKSPACE_GENERATION_ROLLED_BACK'
                            ELSE o.last_error_code END,
       last_error_redacted=CASE WHEN a.state='ROLLED_BACK'
           THEN 'Durable workspace-generation evidence rejected this physical attempt'
           ELSE o.last_error_redacted END,
       lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,
       not_reclaim_before=NULL,next_attempt_at=NULL,version=version+1
  FROM workspace_generation_attempt a
 WHERE o.id=a.llm_operation_id
   AND a.state IN ('ACCEPTED','ROLLED_BACK')
   AND o.attempt=a.llm_attempt_number
   AND o.status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
   AND (o.status='QUEUED' OR o.lease_token=a.llm_lease_token)
   AND ((a.state='ACCEPTED' AND o.status<>'SUCCEEDED')
     OR (a.state='ROLLED_BACK' AND o.status<>'FAILED'));

-- Make pre-dispatch admission, operation claim, and authority acquisition durable. Product-tree
-- checkpoint fields become mandatory only at PREPARED; earlier states have not dispatched a
-- provider or product mutation and are recoverable from the accepted frontier.
ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_attempt_state_chk,
    DROP CONSTRAINT workspace_generation_attempt_digest_chk,
    DROP CONSTRAINT workspace_generation_llm_lease_chk,
    DROP CONSTRAINT workspace_generation_checkpoint_chk,
    ALTER COLUMN before_generation DROP NOT NULL,
    ALTER COLUMN repositories_json DROP NOT NULL,
    ALTER COLUMN ignored_artifact_baseline_json DROP NOT NULL,
    ALTER COLUMN ignored_artifact_baseline_digest DROP NOT NULL,
    ALTER COLUMN workspace_lease_token DROP NOT NULL,
    ALTER COLUMN before_checkpoint_kind DROP NOT NULL,
    ALTER COLUMN before_checkpoint_key DROP NOT NULL,
    ALTER COLUMN before_checkpoint_digest DROP NOT NULL,
    ALTER COLUMN before_checkpoint_completed_at DROP NOT NULL;

ALTER TABLE workspace_generation_attempt
    ADD CONSTRAINT workspace_generation_attempt_state_chk CHECK (
        state IN (
            'ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED',
            'PREPARED', 'MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING',
            'ACCEPTED', 'ROLLING_BACK', 'ROLLED_BACK', 'RECOVERY_FAILED'
        )
    ),
    ADD CONSTRAINT workspace_generation_attempt_digest_chk CHECK (
        (before_generation IS NULL OR before_generation ~ '^[0-9a-f]{64}$')
        AND (candidate_generation IS NULL OR candidate_generation ~ '^[0-9a-f]{64}$')
        AND authority_digest ~ '^[0-9a-f]{64}$'
        AND (ignored_artifact_baseline_digest IS NULL
             OR ignored_artifact_baseline_digest ~ '^[0-9a-f]{64}$')
        AND task_contract_digest ~ '^[0-9a-f]{64}$'
        AND (before_checkpoint_digest IS NULL
             OR before_checkpoint_digest ~ '^[0-9a-f]{64}$')
        AND (checkpoint_generation IS NULL OR checkpoint_generation ~ '^[0-9a-f]{64}$')
        AND (checkpoint_digest IS NULL OR checkpoint_digest ~ '^[0-9a-f]{64}$')
        AND (rejected_diff_digest IS NULL OR rejected_diff_digest ~ '^[0-9a-f]{64}$')
    ),
    ADD CONSTRAINT workspace_generation_llm_lease_chk CHECK (
        (state = 'ADMITTED' AND llm_operation_id IS NULL
            AND llm_attempt_number IS NULL AND llm_lease_token IS NULL)
        OR (state <> 'ADMITTED' AND llm_operation_id IS NOT NULL
            AND llm_attempt_number > 0 AND llm_lease_token IS NOT NULL)
    ),
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
            AND before_checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST')
            AND before_checkpoint_key IS NOT NULL
            AND before_checkpoint_digest ~ '^[0-9a-f]{64}$'
            AND before_checkpoint_completed_at IS NOT NULL
            AND (
                (checkpoint_kind IS NULL AND checkpoint_key IS NULL
                    AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
                    AND checkpoint_completed_at IS NULL)
                OR (checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST')
                    AND checkpoint_key IS NOT NULL
                    AND checkpoint_generation ~ '^[0-9a-f]{64}$'
                    AND checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND checkpoint_completed_at IS NOT NULL)
            ))
    );

DROP INDEX uq_workspace_generation_attempt_active_run;
CREATE UNIQUE INDEX uq_workspace_generation_attempt_active_run
    ON workspace_generation_attempt(run_id)
    WHERE state IN (
        'ADMITTED', 'OPERATION_CLAIMED', 'AUTHORITY_ACQUIRED',
        'PREPARED', 'MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING',
        'ROLLING_BACK', 'RECOVERY_FAILED'
    );

-- V374's transition trigger treated PREPARED as the insert state and therefore made the new
-- durable pre-dispatch states unreachable. Keep the mature V374 guard for PREPARED onward and use
-- a narrow guard for the only legal pre-dispatch transitions.
DROP TRIGGER workspace_generation_attempt_transition ON workspace_generation_attempt;

CREATE OR REPLACE FUNCTION enforce_workspace_generation_predispatch_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.task_id IS DISTINCT FROM OLD.task_id
       OR NEW.task_index IS DISTINCT FROM OLD.task_index
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number
       OR NEW.attempt_kind IS DISTINCT FROM OLD.attempt_kind
       OR NEW.workspace_root IS DISTINCT FROM OLD.workspace_root
       OR NEW.authority_digest IS DISTINCT FROM OLD.authority_digest
       OR NEW.task_contract_digest IS DISTINCT FROM OLD.task_contract_digest
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'workspace generation immutable identity cannot change';
    END IF;

    IF NEW.state IN ('MUTATING','VERIFYING','VERIFIED','CHECKPOINTING','ACCEPTED')
       AND NEW.llm_operation_id IS NULL THEN
        RAISE EXCEPTION 'workspace generation cannot leave pre-dispatch without an exact LLM attempt binding';
    END IF;

    IF OLD.state = 'ADMITTED' AND NEW.state = 'OPERATION_CLAIMED' THEN
        IF OLD.llm_operation_id IS NOT NULL
           OR NEW.llm_operation_id IS NULL
           OR NEW.llm_attempt_number IS NULL
           OR NEW.llm_lease_token IS NULL THEN
            RAISE EXCEPTION 'workspace generation operation claim is incomplete';
        END IF;
    ELSIF OLD.state = 'OPERATION_CLAIMED' AND NEW.state = 'AUTHORITY_ACQUIRED' THEN
        IF NEW.llm_operation_id IS DISTINCT FROM OLD.llm_operation_id
           OR NEW.llm_attempt_number IS DISTINCT FROM OLD.llm_attempt_number
           OR NEW.llm_lease_token IS DISTINCT FROM OLD.llm_lease_token
           OR OLD.workspace_lease_token IS NOT NULL
           OR NEW.workspace_lease_token IS NULL THEN
            RAISE EXCEPTION 'workspace generation authority acquisition changed operation identity';
        END IF;
    ELSIF OLD.state = 'AUTHORITY_ACQUIRED' AND NEW.state = 'PREPARED' THEN
        IF NEW.llm_operation_id IS DISTINCT FROM OLD.llm_operation_id
           OR NEW.llm_attempt_number IS DISTINCT FROM OLD.llm_attempt_number
           OR NEW.llm_lease_token IS DISTINCT FROM OLD.llm_lease_token
           OR NEW.workspace_lease_token IS DISTINCT FROM OLD.workspace_lease_token
           OR NEW.before_generation IS NULL
           OR NEW.repositories_json IS NULL
           OR NEW.ignored_artifact_baseline_json IS NULL
           OR NEW.ignored_artifact_baseline_digest IS NULL
           OR NEW.before_checkpoint_kind IS NULL
           OR NEW.before_checkpoint_key IS NULL
           OR NEW.before_checkpoint_digest IS NULL
           OR NEW.before_checkpoint_completed_at IS NULL THEN
            RAISE EXCEPTION 'workspace generation preparation evidence is incomplete';
        END IF;
    ELSIF OLD.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
          AND NEW.state = 'ROLLING_BACK' THEN
        IF NEW.recovery_lease_token IS NULL OR NOT EXISTS (
            SELECT 1 FROM run_workspace_authority w
             WHERE w.run_id=NEW.run_id AND w.authority_type='RECOVERY'
               AND w.owner_token=NEW.recovery_lease_token AND w.lease_expires_at>=NOW()
        ) THEN
            RAISE EXCEPTION 'workspace generation recovery lease is not current';
        END IF;
    ELSIF NEW.state IS DISTINCT FROM OLD.state THEN
        RAISE EXCEPTION 'illegal workspace generation transition: % -> %', OLD.state, NEW.state;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_attempt_predispatch_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (OLD.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED'))
EXECUTE FUNCTION enforce_workspace_generation_predispatch_transition();

CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state NOT IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
    AND NOT (
        OLD.state = 'ROLLED_BACK'
        AND NEW.state = 'ROLLED_BACK'
        AND (to_jsonb(NEW) - ARRAY[
            'task_transition_state', 'task_transition_completed_at',
            'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
        ]::text[])
          = (to_jsonb(OLD) - ARRAY[
            'task_transition_state', 'task_transition_completed_at',
            'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
          ]::text[])
    )
)
EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

-- A PREPARED BUILD attempt is an admitted mutation capability. Fence admission in PostgreSQL so
-- cancellation, an expired/replaced workspace authority, a pending task transition, or an
-- unreconciled physical LLM operation cannot be bypassed by Java ordering or a future caller.
CREATE OR REPLACE FUNCTION fence_workspace_generation_attempt_admission()
RETURNS TRIGGER AS $$
DECLARE
    admitted BOOLEAN;
    operation_admitted BOOLEAN;
    pending_transition BOOLEAN;
    terminal_operation_mismatch BOOLEAN;
BEGIN
    IF NEW.attempt_kind <> 'BUILD_TASK' THEN
        RAISE EXCEPTION 'workspace generation attempt must be BUILD_TASK';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.state <> 'ADMITTED' THEN
        RAISE EXCEPTION 'workspace generation attempt must enter through ADMITTED';
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM pipeline_run p
         WHERE p.run_id = NEW.run_id
           AND p.status = 'WORKING'
    ) INTO admitted;
    IF NEW.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                     'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING','ACCEPTED')
       AND NOT admitted THEN
        RAISE EXCEPTION 'workspace generation admission requires WORKING run';
    END IF;

    IF NEW.state IN ('OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                     'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING')
       OR (NEW.state = 'ACCEPTED'
           AND (TG_OP = 'INSERT' OR OLD.state IS DISTINCT FROM 'ACCEPTED')) THEN
        SELECT EXISTS (
            SELECT 1
              FROM llm_execution_operation o
             WHERE o.id = NEW.llm_operation_id
               AND o.attempt = NEW.llm_attempt_number
               AND o.lease_token = NEW.llm_lease_token
               AND o.status = 'RUNNING'
               AND o.lease_expires_at >= NOW()
        ) INTO operation_admitted;
        IF NOT operation_admitted THEN
            RAISE EXCEPTION 'workspace generation lacks exact RUNNING LLM operation';
        END IF;
    END IF;

    IF NEW.state IN ('AUTHORITY_ACQUIRED','PREPARED','MUTATING','VERIFYING',
                     'VERIFIED','CHECKPOINTING')
       OR (NEW.state = 'ACCEPTED'
           AND (TG_OP = 'INSERT' OR OLD.state IS DISTINCT FROM 'ACCEPTED')) THEN
        SELECT EXISTS (
            SELECT 1 FROM run_workspace_authority w
             WHERE w.run_id = NEW.run_id
               AND w.authority_type = 'BUILD_GENERATION'
               AND w.owner_token = NEW.workspace_lease_token
               AND w.lease_expires_at >= NOW()
        ) INTO admitted;
        IF NOT admitted THEN
            RAISE EXCEPTION 'workspace generation lacks exact workspace authority';
        END IF;
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM workspace_generation_attempt prior
         WHERE prior.run_id = NEW.run_id
           AND prior.task_id = NEW.task_id
           AND prior.id <> NEW.id
           AND prior.task_transition_state = 'PENDING'
    ) INTO pending_transition;
    IF pending_transition THEN
        RAISE EXCEPTION 'workspace generation admission blocked by pending task transition';
    END IF;

    IF NEW.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED',
                     'MUTATING','VERIFYING','VERIFIED','CHECKPOINTING') THEN
        SELECT EXISTS (
            SELECT 1
              FROM workspace_generation_attempt prior
              JOIN llm_execution_operation o ON o.id = prior.llm_operation_id
             WHERE prior.run_id = NEW.run_id
               AND prior.task_id = NEW.task_id
               AND prior.id <> NEW.id
               AND prior.state IN ('ACCEPTED', 'ROLLED_BACK')
               AND ((prior.state = 'ACCEPTED' AND o.status <> 'SUCCEEDED')
                 OR (prior.state = 'ROLLED_BACK' AND o.status <> 'FAILED'))
        ) INTO terminal_operation_mismatch;
        IF terminal_operation_mismatch THEN
            RAISE EXCEPTION 'workspace generation admission blocked by unreconciled LLM operation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_attempt_admission
BEFORE INSERT OR UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION fence_workspace_generation_attempt_admission();

-- A BUILD task cannot publish COMPLETE before the exact accepted generation exists. PATCH and
-- historical non-BUILD task rows are intentionally outside this trigger.
CREATE OR REPLACE FUNCTION fence_build_task_complete()
RETURNS TRIGGER AS $$
DECLARE
    build_run BOOLEAN;
    accepted BOOLEAN;
BEGIN
    IF NEW.status <> 'COMPLETE' OR NEW.status IS NOT DISTINCT FROM OLD.status THEN
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM pipeline_run p
         WHERE p.run_id = NEW.run_id AND p.run_mode = 'BUILD'
    ) INTO build_run;
    IF NOT build_run THEN RETURN NEW; END IF;
    SELECT EXISTS (
        SELECT 1
          FROM pipeline_run p
          JOIN workspace_generation_attempt a
            ON a.id = p.accepted_generation_attempt_id
         WHERE p.run_id = NEW.run_id
           AND a.run_id = p.run_id
           AND a.state = 'ACCEPTED'
           AND NEW.task_id = CONCAT(SUBSTRING(NEW.run_id, 1, 8), '_', a.task_id)
           AND p.accepted_workspace_generation = a.candidate_generation
           AND p.accepted_checkpoint_digest = a.checkpoint_digest
    ) INTO accepted;
    IF NOT accepted THEN
        RAISE EXCEPTION 'BUILD task COMPLETE requires exact accepted workspace generation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER build_task_complete_fence
BEFORE UPDATE OF status ON agent_task
FOR EACH ROW EXECUTE FUNCTION fence_build_task_complete();

-- Terminal BUILD run publication is impossible while any generation component is unresolved.
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
             OR a.task_transition_state = 'PENDING')
    ) OR EXISTS (
        SELECT 1
          FROM workspace_generation_attempt a
          JOIN llm_execution_operation o ON o.id = a.llm_operation_id
         WHERE a.run_id = NEW.run_id
           AND a.state IN ('ACCEPTED','ROLLED_BACK')
           AND ((a.state='ACCEPTED' AND o.status <> 'SUCCEEDED')
             OR (a.state='ROLLED_BACK' AND o.status <> 'FAILED'))
    ) OR EXISTS (
        SELECT 1 FROM run_workspace_authority w WHERE w.run_id = NEW.run_id
    ) INTO unresolved;
    IF unresolved THEN
        RAISE EXCEPTION 'terminal BUILD run retains unresolved generation state';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER build_run_terminal_fence
BEFORE UPDATE OF status ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION fence_build_run_terminal();

CREATE INDEX idx_workspace_generation_terminal_operation_reconciliation
    ON workspace_generation_attempt(run_id, task_id, resolved_at)
    WHERE state IN ('ACCEPTED', 'ROLLED_BACK') AND llm_operation_id IS NOT NULL;

DROP INDEX IF EXISTS idx_workspace_generation_attempt_recovery;
CREATE INDEX idx_workspace_generation_attempt_recovery
    ON workspace_generation_attempt(state, created_at)
    WHERE state IN (
        'ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED','MUTATING','VERIFYING',
        'VERIFIED','CHECKPOINTING','ROLLING_BACK','RECOVERY_FAILED'
    );
