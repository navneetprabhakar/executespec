-- A workspace generation is the unit of mutation authority for BUILD tasks and corrections.
-- Acceptance is deliberately stronger than worker completion: the candidate tree must have an
-- immutable passing verification receipt and a completed checkpoint for the exact same digest.
CREATE TABLE workspace_generation_attempt (
    id UUID PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    task_id VARCHAR(255) NOT NULL,
    task_index INTEGER NOT NULL,
    attempt_number INTEGER NOT NULL,
    attempt_kind VARCHAR(24) NOT NULL,
    state VARCHAR(24) NOT NULL,
    workspace_root TEXT NOT NULL,
    before_generation VARCHAR(64) NOT NULL,
    candidate_generation VARCHAR(64),
    repositories_json JSONB NOT NULL,
    ignored_artifact_baseline_json JSONB NOT NULL,
    ignored_artifact_baseline_digest VARCHAR(64) NOT NULL,
    authority_digest VARCHAR(64) NOT NULL,
    task_contract_digest VARCHAR(64) NOT NULL,
    workspace_lease_token UUID NOT NULL,
    llm_operation_id VARCHAR(64)
        REFERENCES llm_execution_operation(id) DEFERRABLE INITIALLY DEFERRED,
    llm_attempt_number INTEGER,
    llm_lease_token UUID,
    before_checkpoint_kind VARCHAR(24) NOT NULL,
    before_checkpoint_key TEXT NOT NULL,
    before_checkpoint_digest VARCHAR(64) NOT NULL,
    before_checkpoint_completed_at TIMESTAMPTZ NOT NULL,
    checkpoint_kind VARCHAR(24),
    checkpoint_key TEXT,
    checkpoint_generation VARCHAR(64),
    checkpoint_digest VARCHAR(64),
    checkpoint_completed_at TIMESTAMPTZ,
    rejected_diff_digest VARCHAR(64),
    rejected_diff_json JSONB,
    failure_code VARCHAR(64),
    failure_reason TEXT,
    recovery_lease_token UUID,
    recovery_started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT workspace_generation_attempt_number_chk CHECK (attempt_number > 0),
    CONSTRAINT workspace_generation_task_index_chk CHECK (task_index >= 0),
    CONSTRAINT workspace_generation_attempt_kind_chk CHECK (
        attempt_kind IN ('BUILD_TASK', 'CORRECTION')
    ),
    CONSTRAINT workspace_generation_attempt_state_chk CHECK (
        state IN (
            'PREPARED', 'MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING',
            'ACCEPTED', 'ROLLING_BACK', 'ROLLED_BACK', 'RECOVERY_FAILED'
        )
    ),
    CONSTRAINT workspace_generation_attempt_digest_chk CHECK (
        before_generation ~ '^[0-9a-f]{64}$'
        AND (candidate_generation IS NULL OR candidate_generation ~ '^[0-9a-f]{64}$')
        AND authority_digest ~ '^[0-9a-f]{64}$'
        AND ignored_artifact_baseline_digest ~ '^[0-9a-f]{64}$'
        AND task_contract_digest ~ '^[0-9a-f]{64}$'
        AND before_checkpoint_digest ~ '^[0-9a-f]{64}$'
        AND (checkpoint_generation IS NULL OR checkpoint_generation ~ '^[0-9a-f]{64}$')
        AND (checkpoint_digest IS NULL OR checkpoint_digest ~ '^[0-9a-f]{64}$')
        AND (rejected_diff_digest IS NULL OR rejected_diff_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT workspace_generation_llm_lease_chk CHECK (
        (llm_operation_id IS NULL AND llm_attempt_number IS NULL AND llm_lease_token IS NULL)
        OR (llm_operation_id IS NOT NULL AND llm_attempt_number > 0 AND llm_lease_token IS NOT NULL)
    ),
    CONSTRAINT workspace_generation_llm_operation_id_chk CHECK (
        llm_operation_id IS NULL OR length(llm_operation_id) BETWEEN 1 AND 64
    ),
    CONSTRAINT workspace_generation_checkpoint_chk CHECK (
        before_checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST')
        AND (
            (checkpoint_kind IS NULL AND checkpoint_key IS NULL
                AND checkpoint_generation IS NULL AND checkpoint_digest IS NULL
                AND checkpoint_completed_at IS NULL)
            OR (checkpoint_kind IN ('GCS', 'LOCAL_SAME_HOST') AND checkpoint_key IS NOT NULL
                AND checkpoint_generation ~ '^[0-9a-f]{64}$'
                AND checkpoint_digest ~ '^[0-9a-f]{64}$'
                AND checkpoint_completed_at IS NOT NULL)
        )
    ),
    CONSTRAINT workspace_generation_recovery_lease_chk CHECK (
        (recovery_lease_token IS NULL AND recovery_started_at IS NULL)
        OR (recovery_lease_token IS NOT NULL AND recovery_started_at IS NOT NULL)
    ),
    CONSTRAINT workspace_generation_attempt_unique UNIQUE (run_id, task_id, attempt_number)
);

CREATE TABLE workspace_generation_verification_receipt (
    id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    receipt_version INTEGER NOT NULL DEFAULT 1,
    candidate_generation VARCHAR(64) NOT NULL,
    task_contract_digest VARCHAR(64) NOT NULL,
    verifier_kind VARCHAR(32) NOT NULL,
    command_source VARCHAR(32) NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    build_passed BOOLEAN NOT NULL,
    tests_executed BOOLEAN NOT NULL,
    tests_passed INTEGER NOT NULL DEFAULT 0,
    tests_failed INTEGER NOT NULL DEFAULT 0,
    tests_errors INTEGER NOT NULL DEFAULT 0,
    tests_skipped INTEGER NOT NULL DEFAULT 0,
    evidence_json JSONB NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT workspace_generation_receipt_version_chk CHECK (receipt_version = 1),
    CONSTRAINT workspace_generation_receipt_kind_chk CHECK (
        verifier_kind IN ('BUILD_AND_TEST', 'BUILD_ONLY', 'TEST_ONLY')
    ),
    CONSTRAINT workspace_generation_receipt_source_chk CHECK (
        command_source IN ('SERVER_RESOLVED_CONTRACT')
    ),
    CONSTRAINT workspace_generation_receipt_outcome_chk CHECK (
        outcome IN ('PASSED', 'FAILED', 'UNAVAILABLE')
    ),
    CONSTRAINT workspace_generation_receipt_digest_chk CHECK (
        candidate_generation ~ '^[0-9a-f]{64}$'
        AND task_contract_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'
        AND evidence_digest ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT workspace_generation_receipt_counts_chk CHECK (
        tests_passed >= 0 AND tests_failed >= 0 AND tests_errors >= 0 AND tests_skipped >= 0
    ),
    CONSTRAINT workspace_generation_receipt_semantics_chk CHECK (
        ((verifier_kind = 'BUILD_ONLY' AND tests_executed = FALSE)
            OR (verifier_kind IN ('BUILD_AND_TEST', 'TEST_ONLY') AND tests_executed = TRUE))
        AND (tests_executed = TRUE
            OR (tests_passed = 0 AND tests_failed = 0 AND tests_errors = 0
                AND tests_skipped = 0))
        AND (outcome <> 'PASSED'
            OR (build_passed = TRUE AND tests_failed = 0 AND tests_errors = 0
                AND (tests_executed = FALSE OR tests_passed > 0)))
    ),
    CONSTRAINT workspace_generation_receipt_time_chk CHECK (completed_at >= started_at),
    CONSTRAINT workspace_generation_receipt_unique UNIQUE (attempt_id, evidence_digest)
);

-- Exactly one unresolved mutation transaction may exist for a run across JVMs.
CREATE UNIQUE INDEX uq_workspace_generation_attempt_active_run
    ON workspace_generation_attempt(run_id)
    WHERE state IN (
        'PREPARED', 'MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING',
        'ROLLING_BACK', 'RECOVERY_FAILED'
    );

CREATE INDEX idx_workspace_generation_attempt_recovery
    ON workspace_generation_attempt(state, updated_at)
    WHERE state IN (
        'PREPARED', 'MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING',
        'ROLLING_BACK', 'RECOVERY_FAILED'
    );

CREATE INDEX idx_workspace_generation_receipt_attempt
    ON workspace_generation_verification_receipt(attempt_id, outcome);

ALTER TABLE pipeline_run
    ADD COLUMN accepted_workspace_generation VARCHAR(64),
    ADD COLUMN accepted_task_index INTEGER,
    ADD COLUMN accepted_generation_attempt_id UUID
        REFERENCES workspace_generation_attempt(id) DEFERRABLE INITIALLY DEFERRED,
    ADD COLUMN accepted_checkpoint_kind VARCHAR(24),
    ADD COLUMN accepted_checkpoint_key TEXT,
    ADD COLUMN accepted_checkpoint_digest VARCHAR(64),
    ADD COLUMN accepted_checkpoint_completed_at TIMESTAMPTZ,
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
                    AND accepted_checkpoint_kind IN ('LOCAL_SAME_HOST', 'GCS')
                    AND accepted_checkpoint_key IS NOT NULL
                    AND accepted_checkpoint_digest ~ '^[0-9a-f]{64}$'
                    AND accepted_checkpoint_completed_at IS NOT NULL)
            ))
    );

-- New corrections can bind to the generic generation protocol while historical V369 rows remain
-- recoverable through their original contract during the forward migration.
ALTER TABLE correction_attempt
    ADD COLUMN workspace_generation_attempt_id UUID UNIQUE
        REFERENCES workspace_generation_attempt(id) DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE run_workspace_authority
    DROP CONSTRAINT run_workspace_authority_type_chk,
    ADD CONSTRAINT run_workspace_authority_type_chk CHECK (
        authority_type IN ('BUILD_GENERATION', 'CORRECTION', 'RECOVERY', 'PUBLICATION')
    );

-- Verification receipts are evidence. They may be inserted, but never rewritten or deleted while
-- their parent run exists (the run retention cascade remains the deletion authority).
CREATE OR REPLACE FUNCTION reject_workspace_generation_receipt_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'workspace generation verification receipts are immutable';
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION reject_live_workspace_generation_evidence_delete()
RETURNS TRIGGER AS $$
DECLARE
    live_run_exists BOOLEAN;
BEGIN
    IF TG_TABLE_NAME = 'workspace_generation_attempt' THEN
        SELECT EXISTS (SELECT 1 FROM pipeline_run p WHERE p.run_id = OLD.run_id)
          INTO live_run_exists;
    ELSE
        SELECT EXISTS (
            SELECT 1
              FROM workspace_generation_attempt a
              JOIN pipeline_run p ON p.run_id = a.run_id
             WHERE a.id = OLD.attempt_id
        ) INTO live_run_exists;
    END IF;
    IF live_run_exists THEN
        RAISE EXCEPTION 'workspace generation evidence cannot be deleted while its run exists';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_receipt_no_update
BEFORE UPDATE ON workspace_generation_verification_receipt
FOR EACH ROW EXECUTE FUNCTION reject_workspace_generation_receipt_update();

CREATE TRIGGER workspace_generation_receipt_no_delete
BEFORE DELETE ON workspace_generation_verification_receipt
FOR EACH ROW EXECUTE FUNCTION reject_live_workspace_generation_evidence_delete();

CREATE TRIGGER workspace_generation_attempt_no_delete
BEFORE DELETE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION reject_live_workspace_generation_evidence_delete();

-- Keep state transitions and acceptance proof valid even if a future caller bypasses the Java
-- lifecycle service. ACCEPTED requires a passing receipt and checkpoint for the exact candidate.
CREATE OR REPLACE FUNCTION enforce_workspace_generation_attempt_transition()
RETURNS TRIGGER AS $$
DECLARE
    receipt_exists BOOLEAN;
    llm_lease_exists BOOLEAN;
    workspace_lease_exists BOOLEAN;
    run_is_working BOOLEAN;
BEGIN
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.task_id IS DISTINCT FROM OLD.task_id
       OR NEW.task_index IS DISTINCT FROM OLD.task_index
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number
       OR NEW.attempt_kind IS DISTINCT FROM OLD.attempt_kind
       OR NEW.workspace_root IS DISTINCT FROM OLD.workspace_root
       OR NEW.before_generation IS DISTINCT FROM OLD.before_generation
       OR NEW.repositories_json IS DISTINCT FROM OLD.repositories_json
       OR NEW.ignored_artifact_baseline_json IS DISTINCT FROM OLD.ignored_artifact_baseline_json
       OR NEW.ignored_artifact_baseline_digest IS DISTINCT FROM OLD.ignored_artifact_baseline_digest
       OR NEW.authority_digest IS DISTINCT FROM OLD.authority_digest
       OR NEW.task_contract_digest IS DISTINCT FROM OLD.task_contract_digest
       OR NEW.workspace_lease_token IS DISTINCT FROM OLD.workspace_lease_token
       OR NEW.before_checkpoint_kind IS DISTINCT FROM OLD.before_checkpoint_kind
       OR NEW.before_checkpoint_key IS DISTINCT FROM OLD.before_checkpoint_key
       OR NEW.before_checkpoint_digest IS DISTINCT FROM OLD.before_checkpoint_digest
       OR NEW.before_checkpoint_completed_at IS DISTINCT FROM OLD.before_checkpoint_completed_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'workspace generation immutable identity cannot change';
    END IF;

    IF OLD.checkpoint_kind IS NOT NULL AND (
        NEW.checkpoint_kind IS DISTINCT FROM OLD.checkpoint_kind
        OR NEW.checkpoint_key IS DISTINCT FROM OLD.checkpoint_key
        OR NEW.checkpoint_generation IS DISTINCT FROM OLD.checkpoint_generation
        OR NEW.checkpoint_digest IS DISTINCT FROM OLD.checkpoint_digest
        OR NEW.checkpoint_completed_at IS DISTINCT FROM OLD.checkpoint_completed_at
    ) THEN
        RAISE EXCEPTION 'workspace generation checkpoint receipt is immutable';
    END IF;

    IF OLD.checkpoint_kind IS NULL AND NEW.checkpoint_kind IS NOT NULL
       AND (OLD.state <> 'CHECKPOINTING' OR NEW.state <> 'CHECKPOINTING') THEN
        RAISE EXCEPTION 'workspace generation checkpoint must complete while checkpointing';
    END IF;

    IF OLD.llm_operation_id IS NOT NULL AND (
        NEW.llm_operation_id IS DISTINCT FROM OLD.llm_operation_id
        OR NEW.llm_attempt_number IS DISTINCT FROM OLD.llm_attempt_number
        OR NEW.llm_lease_token IS DISTINCT FROM OLD.llm_lease_token
    ) THEN
        RAISE EXCEPTION 'workspace generation LLM attempt binding is immutable';
    END IF;

    IF OLD.llm_operation_id IS NULL AND NEW.llm_operation_id IS NOT NULL THEN
        IF OLD.state <> 'MUTATING' OR NEW.state <> 'MUTATING' THEN
            RAISE EXCEPTION 'workspace generation LLM attempt must bind while mutating';
        END IF;
        SELECT EXISTS (
            SELECT 1
              FROM llm_execution_operation o
              JOIN llm_execution_context c ON c.id = o.llm_execution_context_id
             WHERE o.id = NEW.llm_operation_id
               AND o.status = 'RUNNING'
               AND o.operation_kind = 'RUN_BUILD_TASK'
               AND o.operation_key =
                   'pipeline-run:' || NEW.run_id || ':build-task:' || NEW.task_id
               AND o.attempt = NEW.llm_attempt_number
               AND o.lease_token = NEW.llm_lease_token
               AND o.lease_expires_at >= NOW()
               AND c.context_type = 'PIPELINE_RUN'
               AND c.source_id = NEW.run_id
        ) INTO llm_lease_exists;
        IF NOT llm_lease_exists THEN
            RAISE EXCEPTION 'workspace generation LLM attempt lease is not current';
        END IF;
    END IF;

    IF NEW.state IN ('MUTATING', 'VERIFYING', 'VERIFIED', 'CHECKPOINTING', 'ACCEPTED') THEN
        SELECT EXISTS (
            SELECT 1 FROM pipeline_run p
             WHERE p.run_id = NEW.run_id AND p.status = 'WORKING'
        ) INTO run_is_working;
        IF NOT run_is_working THEN
            RAISE EXCEPTION 'workspace generation run is not working';
        END IF;
        SELECT EXISTS (
            SELECT 1
              FROM run_workspace_authority w
             WHERE w.run_id = NEW.run_id
               AND w.authority_type = 'BUILD_GENERATION'
               AND w.owner_token = NEW.workspace_lease_token
               AND w.lease_expires_at >= NOW()
        ) INTO workspace_lease_exists;
        IF NOT workspace_lease_exists THEN
            RAISE EXCEPTION 'workspace generation lease is not current';
        END IF;
    END IF;

    -- Binding once is insufficient: the provider operation can expire or terminate after its last
    -- tool call. Every candidate-bearing transition repeats the exact operation/run/task/attempt
    -- fence so stale worker bytes cannot enter verification, checkpointing, or acceptance.
    IF NEW.state IN ('VERIFYING', 'VERIFIED', 'CHECKPOINTING', 'ACCEPTED')
       AND NEW.llm_operation_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1
              FROM llm_execution_operation o
              JOIN llm_execution_context c ON c.id = o.llm_execution_context_id
             WHERE o.id = NEW.llm_operation_id
               AND o.status = 'RUNNING'
               AND o.operation_kind = 'RUN_BUILD_TASK'
               AND o.operation_key =
                   'pipeline-run:' || NEW.run_id || ':build-task:' || NEW.task_id
               AND o.attempt = NEW.llm_attempt_number
               AND o.lease_token = NEW.llm_lease_token
               AND o.lease_expires_at >= NOW()
               AND c.context_type = 'PIPELINE_RUN'
               AND c.source_id = NEW.run_id
        ) INTO llm_lease_exists;
        IF NOT llm_lease_exists THEN
            RAISE EXCEPTION 'workspace generation LLM attempt lease is not current';
        END IF;
    END IF;

    IF OLD.candidate_generation IS NOT NULL
       AND NEW.candidate_generation IS DISTINCT FROM OLD.candidate_generation THEN
        RAISE EXCEPTION 'workspace generation candidate digest is immutable';
    END IF;

    IF OLD.recovery_lease_token IS NULL AND NEW.recovery_lease_token IS NOT NULL THEN
        IF NEW.state <> 'ROLLING_BACK' OR NOT EXISTS (
            SELECT 1 FROM run_workspace_authority w
             WHERE w.run_id = NEW.run_id
               AND w.authority_type = 'RECOVERY'
               AND w.owner_token = NEW.recovery_lease_token
               AND w.lease_expires_at >= NOW()
        ) THEN
            RAISE EXCEPTION 'workspace generation recovery lease is not current';
        END IF;
    ELSIF OLD.recovery_lease_token IS NOT NULL
       AND NEW.recovery_lease_token IS DISTINCT FROM OLD.recovery_lease_token THEN
        IF OLD.state NOT IN ('RECOVERY_FAILED', 'ROLLING_BACK')
           OR NEW.state <> 'ROLLING_BACK' OR NOT EXISTS (
            SELECT 1 FROM run_workspace_authority w
             WHERE w.run_id = NEW.run_id
               AND w.authority_type = 'RECOVERY'
               AND w.owner_token = NEW.recovery_lease_token
               AND w.lease_expires_at >= NOW()
        ) THEN
            RAISE EXCEPTION 'workspace generation recovery lease replacement is not current';
        END IF;
    END IF;

    IF NEW.state <> OLD.state THEN
        IF OLD.state = 'MUTATING'
           AND NEW.state <> 'ROLLING_BACK'
           AND NEW.llm_operation_id IS NULL THEN
            RAISE EXCEPTION 'workspace generation cannot leave mutating without an exact LLM attempt binding';
        END IF;
        IF NOT (
            (OLD.state = 'PREPARED' AND NEW.state IN ('MUTATING', 'ROLLING_BACK'))
            OR (OLD.state = 'MUTATING' AND NEW.state IN ('VERIFYING', 'ROLLING_BACK'))
            OR (OLD.state = 'VERIFYING' AND NEW.state IN ('VERIFIED', 'ROLLING_BACK'))
            OR (OLD.state = 'VERIFIED' AND NEW.state IN ('CHECKPOINTING', 'ROLLING_BACK'))
            OR (OLD.state = 'CHECKPOINTING' AND NEW.state IN ('ACCEPTED', 'ROLLING_BACK'))
            OR (OLD.state = 'ROLLING_BACK' AND NEW.state IN ('ROLLED_BACK', 'RECOVERY_FAILED'))
            OR (OLD.state = 'RECOVERY_FAILED' AND NEW.state = 'ROLLING_BACK')
        ) THEN
            RAISE EXCEPTION 'illegal workspace generation transition: % -> %', OLD.state, NEW.state;
        END IF;
    END IF;

    IF NEW.state IN ('VERIFIED', 'CHECKPOINTING', 'ACCEPTED') THEN
        SELECT EXISTS (
            SELECT 1
              FROM workspace_generation_verification_receipt r
             WHERE r.attempt_id = NEW.id
               AND r.outcome = 'PASSED'
               AND r.build_passed = TRUE
               AND r.candidate_generation = NEW.candidate_generation
               AND r.task_contract_digest = NEW.task_contract_digest
        ) INTO receipt_exists;
        IF NOT receipt_exists THEN
            RAISE EXCEPTION 'workspace generation lacks an exact passing verification receipt';
        END IF;
    END IF;

    IF NEW.state = 'ACCEPTED' AND (
        NEW.candidate_generation IS NULL
        OR NEW.checkpoint_generation IS DISTINCT FROM NEW.candidate_generation
        OR NEW.checkpoint_key IS NULL
        OR NEW.checkpoint_digest IS NULL
        OR NEW.checkpoint_completed_at IS NULL
    ) THEN
        RAISE EXCEPTION 'accepted workspace generation lacks an exact completed checkpoint';
    END IF;

    IF OLD.state IN ('ACCEPTED', 'ROLLED_BACK') THEN
        RAISE EXCEPTION 'resolved workspace generation attempts are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

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
            RAISE EXCEPTION 'accepted workspace generation frontier is immutable at a task index';
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
           AND a.task_index = NEW.accepted_task_index
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

CREATE TRIGGER pipeline_run_accepted_generation_frontier
BEFORE INSERT OR UPDATE OF accepted_workspace_generation, accepted_task_index,
    accepted_generation_attempt_id, accepted_checkpoint_kind, accepted_checkpoint_key,
    accepted_checkpoint_digest, accepted_checkpoint_completed_at ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION enforce_pipeline_run_accepted_generation_frontier();
