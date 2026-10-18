-- A correction replaces an exact accepted prefix; initial task admission stays contiguous.
-- These nullable fields leave immutable historical attempts unchanged.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN base_frontier_attempt_id UUID REFERENCES workspace_generation_attempt(id),
    ADD COLUMN frontier_task_index INTEGER,
    ADD COLUMN base_frontier_generation VARCHAR(64),
    ADD CONSTRAINT workspace_correction_frontier_shape CHECK (
        (base_frontier_attempt_id IS NULL AND frontier_task_index IS NULL AND base_frontier_generation IS NULL)
        OR (attempt_kind = 'CORRECTION' AND base_frontier_attempt_id IS NOT NULL
            AND frontier_task_index >= task_index AND base_frontier_generation ~ '^[0-9a-f]{64}$')
    );

CREATE OR REPLACE FUNCTION fence_workspace_correction_frontier()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.base_frontier_attempt_id IS DISTINCT FROM OLD.base_frontier_attempt_id
        OR NEW.frontier_task_index IS DISTINCT FROM OLD.frontier_task_index
        OR NEW.base_frontier_generation IS DISTINCT FROM OLD.base_frontier_generation
    ) THEN
        RAISE EXCEPTION 'workspace correction frontier binding is immutable';
    END IF;
    IF NEW.attempt_kind = 'CORRECTION' AND (
        NEW.base_frontier_attempt_id IS NULL OR NEW.frontier_task_index IS NULL
        OR NEW.base_frontier_generation IS NULL
    ) THEN RAISE EXCEPTION 'workspace correction requires an exact accepted prefix'; END IF;
    IF NEW.attempt_kind = 'CORRECTION' AND (
        NEW.state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED','PREPARED','MUTATING','VERIFYING','VERIFIED','CHECKPOINTING')
        OR (NEW.state = 'ACCEPTED' AND OLD.state IS DISTINCT FROM 'ACCEPTED')
    ) AND NOT EXISTS (
        SELECT 1 FROM pipeline_run p
        WHERE p.run_id = NEW.run_id AND p.status = 'WORKING'
          AND p.accepted_generation_attempt_id = NEW.base_frontier_attempt_id
          AND p.accepted_task_index = NEW.frontier_task_index
          AND p.accepted_workspace_generation = NEW.base_frontier_generation
    ) THEN RAISE EXCEPTION 'workspace correction accepted prefix changed'; END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_correction_frontier_binding
BEFORE INSERT OR UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION fence_workspace_correction_frontier();

CREATE OR REPLACE FUNCTION fence_workspace_generation_attempt_admission()
RETURNS TRIGGER AS $$
DECLARE
    admitted BOOLEAN;
    operation_admitted BOOLEAN;
    pending_transition BOOLEAN;
    terminal_operation_mismatch BOOLEAN;
BEGIN
    IF NEW.attempt_kind NOT IN ('BUILD_TASK', 'CORRECTION') THEN
        RAISE EXCEPTION 'workspace generation attempt kind is unsupported';
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

CREATE OR REPLACE FUNCTION enforce_pipeline_run_accepted_generation_frontier()
RETURNS TRIGGER AS $$
DECLARE
    v_checkpoint_kind VARCHAR(32);
    v_checkpoint_key TEXT;
    v_checkpoint_digest VARCHAR(64);
    v_checkpoint_completed_at TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.accepted_task_index IS NULL
       AND NEW.accepted_task_index IS NOT NULL
       AND NEW.accepted_task_index <> -1 THEN
        RAISE EXCEPTION 'accepted workspace generation frontier must seed the baseline first';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.accepted_task_index IS NOT NULL THEN
        IF NEW.accepted_task_index IS NULL THEN
            RAISE EXCEPTION 'accepted workspace generation frontier cannot be cleared';
        END IF;
        IF NEW.accepted_task_index < OLD.accepted_task_index THEN
            RAISE EXCEPTION 'accepted workspace generation frontier cannot move backwards';
        END IF;
        IF NEW.accepted_task_index > OLD.accepted_task_index + 1 THEN
            RAISE EXCEPTION 'accepted workspace generation frontier cannot skip task indexes';
        END IF;
        IF NEW.accepted_task_index = OLD.accepted_task_index
           AND (NEW.accepted_workspace_generation IS DISTINCT FROM OLD.accepted_workspace_generation
                OR NEW.accepted_generation_attempt_id IS DISTINCT FROM
                   OLD.accepted_generation_attempt_id
                OR NEW.accepted_checkpoint_kind IS DISTINCT FROM OLD.accepted_checkpoint_kind
                OR NEW.accepted_checkpoint_key IS DISTINCT FROM OLD.accepted_checkpoint_key
                OR NEW.accepted_checkpoint_digest IS DISTINCT FROM OLD.accepted_checkpoint_digest
                OR NEW.accepted_checkpoint_completed_at IS DISTINCT FROM
                   OLD.accepted_checkpoint_completed_at) THEN
            IF NOT EXISTS (
                SELECT 1 FROM workspace_generation_attempt a
                WHERE a.id = NEW.accepted_generation_attempt_id AND a.run_id = NEW.run_id
                  AND a.attempt_kind = 'CORRECTION' AND a.state = 'ACCEPTED'
                  AND a.base_frontier_attempt_id = OLD.accepted_generation_attempt_id
                  AND a.frontier_task_index = OLD.accepted_task_index
                  AND a.base_frontier_generation = OLD.accepted_workspace_generation
                  AND a.before_generation = OLD.accepted_workspace_generation
                  AND a.candidate_generation = NEW.accepted_workspace_generation
            ) THEN
                RAISE EXCEPTION 'accepted workspace generation frontier is immutable without exact correction proof';
            END IF;
        END IF;
    END IF;
    IF NEW.accepted_generation_attempt_id IS NOT NULL THEN
        SELECT a.checkpoint_kind, a.checkpoint_key, a.checkpoint_digest,
               a.checkpoint_completed_at
          INTO v_checkpoint_kind, v_checkpoint_key, v_checkpoint_digest,
               v_checkpoint_completed_at
          FROM workspace_generation_attempt a
         WHERE a.id = NEW.accepted_generation_attempt_id
           AND a.run_id = NEW.run_id
           AND a.state = 'ACCEPTED'
           AND COALESCE(a.frontier_task_index, a.task_index) = NEW.accepted_task_index
           AND a.candidate_generation = NEW.accepted_workspace_generation;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'accepted workspace generation frontier lacks exact attempt proof';
        END IF;
        IF NEW.accepted_checkpoint_kind IS DISTINCT FROM v_checkpoint_kind
           OR NEW.accepted_checkpoint_key IS DISTINCT FROM v_checkpoint_key
           OR NEW.accepted_checkpoint_digest IS DISTINCT FROM v_checkpoint_digest
           OR NEW.accepted_checkpoint_completed_at IS DISTINCT FROM
              v_checkpoint_completed_at THEN
            RAISE EXCEPTION 'accepted workspace generation frontier lacks exact checkpoint receipt';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
