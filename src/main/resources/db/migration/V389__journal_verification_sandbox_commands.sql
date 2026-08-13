-- Durable physical-command truth for exact-generation verification sandboxes.
-- A higher-level verifier conclusion is not proof that a physical command ran. This journal
-- admits the command before docker exec, fences dispatch to the exact sandbox lease/runtime epoch,
-- settles it once, and stores an immutable terminal receipt.

ALTER TABLE workspace_generation_attempt
    ADD COLUMN verification_command_protocol_version SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_workspace_generation_verification_command_protocol
        CHECK (verification_command_protocol_version IN (1, 2));

COMMENT ON COLUMN workspace_generation_attempt.verification_command_protocol_version IS
    'Pinned writer contract: 1 is previous-binary compatibility; 2 requires exact immutable physical command receipts for PASSED verification.';

CREATE OR REPLACE FUNCTION reject_workspace_generation_command_protocol_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.verification_command_protocol_version IS DISTINCT FROM
       OLD.verification_command_protocol_version THEN
        RAISE EXCEPTION 'verification command protocol identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_generation_command_protocol_immutable
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION reject_workspace_generation_command_protocol_update();

CREATE TABLE sandbox_command_execution (
    id UUID PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    attempt_id UUID REFERENCES workspace_generation_attempt(id) ON DELETE CASCADE,
    sandbox_id UUID NOT NULL REFERENCES run_sandbox(id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    candidate_generation VARCHAR(64) NOT NULL,
    sandbox_purpose VARCHAR(24) NOT NULL,
    runtime_owner_digest VARCHAR(64) NOT NULL,
    lease_token BIGINT NOT NULL,
    runtime_epoch BIGINT NOT NULL,
    image_digest VARCHAR(255) NOT NULL,
    config_digest VARCHAR(80) NOT NULL,
    mount_digest VARCHAR(64) NOT NULL,
    command_class VARCHAR(64) NOT NULL,
    argv_digest VARCHAR(64) NOT NULL,
    timeout_ms BIGINT NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    disposable_snapshot_before_sha256 VARCHAR(64) NOT NULL,
    disposable_snapshot_after_sha256 VARCHAR(64),
    exit_code INTEGER,
    output_digest VARCHAR(64),
    output_truncated BOOLEAN,
    duration_ms BIGINT,
    detail_digest VARCHAR(64),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_sandbox_command_state CHECK (
        state IN ('ADMITTED', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'REFUSED',
                  'UNAVAILABLE', 'TIMED_OUT', 'INDETERMINATE')
    ),
    CONSTRAINT ck_sandbox_command_purpose CHECK (sandbox_purpose = 'VERIFICATION'),
    CONSTRAINT ck_sandbox_command_hashes CHECK (
        candidate_generation ~ '^[0-9a-f]{64}$'
        AND runtime_owner_digest ~ '^[0-9a-f]{64}$'
        AND mount_digest ~ '^[0-9a-f]{64}$'
        AND argv_digest ~ '^[0-9a-f]{64}$'
        AND disposable_snapshot_before_sha256 ~ '^[0-9a-f]{64}$'
        AND (disposable_snapshot_after_sha256 IS NULL
             OR disposable_snapshot_after_sha256 ~ '^[0-9a-f]{64}$')
        AND (output_digest IS NULL OR output_digest ~ '^[0-9a-f]{64}$')
        AND (detail_digest IS NULL OR detail_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_sandbox_command_bounds CHECK (
        lease_token > 0 AND runtime_epoch > 0 AND timeout_ms > 0
        AND (duration_ms IS NULL OR duration_ms >= 0)
    ),
    CONSTRAINT ck_sandbox_command_lifecycle CHECK (
        (state = 'ADMITTED' AND dispatched_at IS NULL AND settled_at IS NULL)
        OR (state = 'DISPATCHED' AND dispatched_at IS NOT NULL AND settled_at IS NULL)
        OR (state IN ('REFUSED', 'UNAVAILABLE') AND settled_at IS NOT NULL)
        OR (state IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'INDETERMINATE')
            AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL)
    ),
    CONSTRAINT ck_sandbox_command_terminal_result CHECK (
        state NOT IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'UNAVAILABLE', 'TIMED_OUT')
        OR (disposable_snapshot_after_sha256 IS NOT NULL
            AND output_digest IS NOT NULL
            AND output_truncated IS NOT NULL
            AND duration_ms IS NOT NULL)
    ),
    CONSTRAINT ck_sandbox_command_exit CHECK (
        (state = 'SUCCEEDED' AND exit_code = 0)
        OR (state = 'FAILED' AND exit_code IS NOT NULL AND exit_code <> 0)
        OR (state NOT IN ('SUCCEEDED', 'FAILED') AND exit_code IS NULL)
    )
);

CREATE OR REPLACE FUNCTION require_sandbox_command_admitted_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.state <> 'ADMITTED'
       OR NEW.dispatched_at IS NOT NULL
       OR NEW.settled_at IS NOT NULL
       OR NEW.disposable_snapshot_after_sha256 IS NOT NULL
       OR NEW.exit_code IS NOT NULL
       OR NEW.output_digest IS NOT NULL
       OR NEW.output_truncated IS NOT NULL
       OR NEW.duration_ms IS NOT NULL
       OR NEW.detail_digest IS NOT NULL THEN
        RAISE EXCEPTION 'sandbox command must be inserted as bare ADMITTED intent';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sandbox_command_insert_admitted_guard
BEFORE INSERT ON sandbox_command_execution
FOR EACH ROW EXECUTE FUNCTION require_sandbox_command_admitted_insert();

CREATE INDEX idx_sandbox_command_recovery
    ON sandbox_command_execution(state, dispatched_at)
    WHERE state = 'DISPATCHED';

CREATE INDEX idx_sandbox_command_attempt
    ON sandbox_command_execution(attempt_id, candidate_generation, state)
    WHERE attempt_id IS NOT NULL;

CREATE TABLE sandbox_command_receipt (
    id UUID PRIMARY KEY,
    command_id UUID NOT NULL UNIQUE
        REFERENCES sandbox_command_execution(id) ON DELETE CASCADE,
    receipt_version INTEGER NOT NULL DEFAULT 1,
    run_id VARCHAR(255) NOT NULL,
    attempt_id UUID,
    sandbox_id UUID NOT NULL,
    candidate_generation VARCHAR(64) NOT NULL,
    sandbox_purpose VARCHAR(24) NOT NULL,
    runtime_owner_digest VARCHAR(64) NOT NULL,
    lease_token BIGINT NOT NULL,
    runtime_epoch BIGINT NOT NULL,
    image_digest VARCHAR(255) NOT NULL,
    config_digest VARCHAR(80) NOT NULL,
    mount_digest VARCHAR(64) NOT NULL,
    command_class VARCHAR(64) NOT NULL,
    argv_digest VARCHAR(64) NOT NULL,
    timeout_ms BIGINT NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    exit_code INTEGER,
    output_digest VARCHAR(64),
    output_truncated BOOLEAN,
    duration_ms BIGINT,
    detail_digest VARCHAR(64),
    disposable_snapshot_before_sha256 VARCHAR(64) NOT NULL,
    disposable_snapshot_after_sha256 VARCHAR(64),
    admitted_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_sandbox_command_receipt_version CHECK (receipt_version = 1),
    CONSTRAINT ck_sandbox_command_receipt_outcome CHECK (
        outcome IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'UNAVAILABLE', 'TIMED_OUT',
                    'INDETERMINATE')
    ),
    CONSTRAINT ck_sandbox_command_receipt_purpose CHECK (sandbox_purpose = 'VERIFICATION'),
    CONSTRAINT ck_sandbox_command_receipt_time CHECK (
        (dispatched_at IS NULL OR dispatched_at >= admitted_at)
        AND settled_at >= COALESCE(dispatched_at, admitted_at)
    )
);

CREATE OR REPLACE FUNCTION enforce_sandbox_command_receipt_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    exact_match BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM sandbox_command_execution c
         WHERE c.id = NEW.command_id
           AND c.state = NEW.outcome
           AND c.run_id = NEW.run_id
           AND c.attempt_id IS NOT DISTINCT FROM NEW.attempt_id
           AND c.sandbox_id = NEW.sandbox_id
           AND c.candidate_generation = NEW.candidate_generation
           AND c.sandbox_purpose = NEW.sandbox_purpose
           AND c.runtime_owner_digest = NEW.runtime_owner_digest
           AND c.lease_token = NEW.lease_token
           AND c.runtime_epoch = NEW.runtime_epoch
           AND c.image_digest = NEW.image_digest
           AND c.config_digest = NEW.config_digest
           AND c.mount_digest = NEW.mount_digest
           AND c.command_class = NEW.command_class
           AND c.argv_digest = NEW.argv_digest
           AND c.timeout_ms = NEW.timeout_ms
           AND c.exit_code IS NOT DISTINCT FROM NEW.exit_code
           AND c.output_digest IS NOT DISTINCT FROM NEW.output_digest
           AND c.output_truncated IS NOT DISTINCT FROM NEW.output_truncated
           AND c.duration_ms IS NOT DISTINCT FROM NEW.duration_ms
           AND c.detail_digest IS NOT DISTINCT FROM NEW.detail_digest
           AND c.disposable_snapshot_before_sha256 =
               NEW.disposable_snapshot_before_sha256
           AND c.disposable_snapshot_after_sha256 IS NOT DISTINCT FROM
               NEW.disposable_snapshot_after_sha256
           AND c.admitted_at = NEW.admitted_at
           AND c.dispatched_at IS NOT DISTINCT FROM NEW.dispatched_at
           AND c.settled_at = NEW.settled_at
    ) INTO exact_match;
    IF NOT exact_match THEN
        RAISE EXCEPTION 'sandbox command receipt must exactly match terminal command state';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER sandbox_command_receipt_identity_guard
BEFORE INSERT ON sandbox_command_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_sandbox_command_receipt_identity();

CREATE OR REPLACE FUNCTION enforce_sandbox_command_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id
       OR NEW.sandbox_id IS DISTINCT FROM OLD.sandbox_id
       OR NEW.candidate_generation IS DISTINCT FROM OLD.candidate_generation
       OR NEW.sandbox_purpose IS DISTINCT FROM OLD.sandbox_purpose
       OR NEW.runtime_owner_digest IS DISTINCT FROM OLD.runtime_owner_digest
       OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
       OR NEW.runtime_epoch IS DISTINCT FROM OLD.runtime_epoch
       OR NEW.image_digest IS DISTINCT FROM OLD.image_digest
       OR NEW.config_digest IS DISTINCT FROM OLD.config_digest
       OR NEW.mount_digest IS DISTINCT FROM OLD.mount_digest
       OR NEW.command_class IS DISTINCT FROM OLD.command_class
       OR NEW.argv_digest IS DISTINCT FROM OLD.argv_digest
       OR NEW.timeout_ms IS DISTINCT FROM OLD.timeout_ms
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.disposable_snapshot_before_sha256 IS DISTINCT FROM OLD.disposable_snapshot_before_sha256
       OR NEW.admitted_at IS DISTINCT FROM OLD.admitted_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'sandbox command immutable identity cannot change';
    END IF;

    IF OLD.state = 'ADMITTED' AND NEW.state = 'DISPATCHED' THEN
        NULL;
    ELSIF OLD.state = 'ADMITTED' AND NEW.state IN ('REFUSED', 'UNAVAILABLE') THEN
        NULL;
    ELSIF OLD.state = 'DISPATCHED'
          AND NEW.state IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'UNAVAILABLE', 'TIMED_OUT',
                            'INDETERMINATE') THEN
        NULL;
    ELSE
        RAISE EXCEPTION 'illegal sandbox command transition % -> %', OLD.state, NEW.state;
    END IF;
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER sandbox_command_transition_guard
BEFORE UPDATE ON sandbox_command_execution
FOR EACH ROW EXECUTE FUNCTION enforce_sandbox_command_transition();

CREATE OR REPLACE FUNCTION enforce_sandbox_command_terminal_receipt()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    receipt_exists BOOLEAN;
BEGIN
    IF NEW.state IN ('SUCCEEDED', 'FAILED', 'REFUSED', 'UNAVAILABLE', 'TIMED_OUT',
                     'INDETERMINATE') THEN
        SELECT EXISTS (
            SELECT 1 FROM sandbox_command_receipt r
             WHERE r.command_id = NEW.id AND r.outcome = NEW.state
        ) INTO receipt_exists;
        IF NOT receipt_exists THEN
            RAISE EXCEPTION 'terminal sandbox command requires exact immutable receipt';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER sandbox_command_terminal_receipt_guard
AFTER INSERT OR UPDATE ON sandbox_command_execution
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_sandbox_command_terminal_receipt();

CREATE OR REPLACE FUNCTION reject_sandbox_command_receipt_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'sandbox command receipts are immutable';
END;
$$;

CREATE TRIGGER sandbox_command_receipt_no_update
BEFORE UPDATE ON sandbox_command_receipt
FOR EACH ROW EXECUTE FUNCTION reject_sandbox_command_receipt_mutation();

CREATE OR REPLACE FUNCTION reject_live_sandbox_command_receipt_delete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    live_run_exists BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM sandbox_command_execution c
        JOIN pipeline_run p ON p.run_id = c.run_id
        WHERE c.id = OLD.command_id
    ) INTO live_run_exists;
    IF live_run_exists THEN
        RAISE EXCEPTION 'sandbox command evidence cannot be deleted while its run exists';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER sandbox_command_receipt_no_delete
BEFORE DELETE ON sandbox_command_receipt
FOR EACH ROW EXECUTE FUNCTION reject_live_sandbox_command_receipt_delete();

ALTER TABLE workspace_generation_verification_receipt
    ADD COLUMN command_receipt_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT workspace_generation_command_receipts_array_chk
        CHECK (jsonb_typeof(command_receipt_ids) = 'array');

CREATE OR REPLACE FUNCTION enforce_workspace_generation_command_receipts()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    supplied_count INTEGER;
    matching_count INTEGER;
    command_protocol SMALLINT;
BEGIN
    SELECT verification_command_protocol_version INTO STRICT command_protocol
      FROM workspace_generation_attempt WHERE id=NEW.attempt_id;
    SELECT COUNT(*), COUNT(DISTINCT value)
      INTO supplied_count, matching_count
      FROM jsonb_array_elements_text(NEW.command_receipt_ids);
    IF supplied_count <> matching_count THEN
        RAISE EXCEPTION 'workspace verification command receipt ids must be unique';
    END IF;

    IF supplied_count > 0 THEN
        SELECT COUNT(*) INTO matching_count
          FROM jsonb_array_elements_text(NEW.command_receipt_ids) ids
          JOIN sandbox_command_receipt r ON r.id = ids.value::uuid
          JOIN sandbox_command_execution c ON c.id = r.command_id
         WHERE c.attempt_id = NEW.attempt_id
           AND c.candidate_generation = NEW.candidate_generation
           AND c.sandbox_purpose = 'VERIFICATION'
           AND r.attempt_id = NEW.attempt_id
           AND r.candidate_generation = NEW.candidate_generation;
        IF matching_count <> supplied_count THEN
            RAISE EXCEPTION 'workspace verification cites foreign or mismatched command receipt';
        END IF;
    END IF;

    IF NEW.outcome = 'PASSED' AND command_protocol >= 2 THEN
        IF supplied_count = 0 THEN
            RAISE EXCEPTION 'passing workspace verification requires physical command receipts';
        END IF;
        SELECT COUNT(*) INTO matching_count
          FROM jsonb_array_elements_text(NEW.command_receipt_ids) ids
          JOIN sandbox_command_receipt r ON r.id = ids.value::uuid
         WHERE r.outcome = 'SUCCEEDED';
        IF matching_count <> supplied_count THEN
            RAISE EXCEPTION 'passing workspace verification requires all commands to succeed';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_generation_command_receipts_guard
BEFORE INSERT ON workspace_generation_verification_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_generation_command_receipts();

COMMENT ON TABLE sandbox_command_execution IS
    'Crash-recoverable intent/state journal for one physical exact-generation verification command. DISPATCHED without an immutable receipt is unknown, never success.';
COMMENT ON TABLE sandbox_command_receipt IS
    'Immutable terminal physical-command evidence bound to sandbox lease/runtime identity, command digest, output digest, and disposable workspace snapshots.';
COMMENT ON COLUMN workspace_generation_verification_receipt.command_receipt_ids IS
    'Exact immutable physical command receipts cited by this higher-level verifier conclusion. PASSED requires a non-empty all-SUCCEEDED set.';
