-- Exact publication artifacts for BUILD protocol v2.
-- Product-generation equality excludes .git by design, so a push is not proven until the exact
-- parent/tree/commit object is prepared under a distinct authority and durably bound to the
-- accepted checkpoint plus validation evidence.

CREATE TABLE workspace_publication_preparation (
    id UUID PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES pipeline_run(run_id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    accepted_generation VARCHAR(64) NOT NULL,
    accepted_attempt_id UUID REFERENCES workspace_generation_attempt(id) ON DELETE RESTRICT,
    accepted_checkpoint_kind VARCHAR(24) NOT NULL,
    accepted_checkpoint_key TEXT NOT NULL,
    accepted_checkpoint_digest VARCHAR(64) NOT NULL,
    accepted_checkpoint_completed_at TIMESTAMPTZ NOT NULL,
    validation_report_id VARCHAR(255) NOT NULL REFERENCES validation_report(id) ON DELETE RESTRICT,
    validation_evidence_json TEXT NOT NULL,
    validation_evidence_digest VARCHAR(64) NOT NULL,
    validation_report_updated_at TIMESTAMPTZ NOT NULL,
    workspace_authority_owner UUID NOT NULL,
    expected_repository_count INTEGER NOT NULL,
    failure_code VARCHAR(80),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    preparing_at TIMESTAMPTZ,
    prepared_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_workspace_publication_preparation_state CHECK (
        state IN ('ADMITTED', 'PREPARING', 'PREPARED', 'FAILED', 'INDETERMINATE')
    ),
    CONSTRAINT ck_workspace_publication_preparation_hashes CHECK (
        accepted_generation ~ '^[0-9a-f]{64}$'
        AND accepted_checkpoint_digest ~ '^[0-9a-f]{64}$'
        AND validation_evidence_digest ~ '^[0-9a-f]{64}$'
        AND encode(sha256(convert_to(validation_evidence_json, 'UTF8')), 'hex')
            = validation_evidence_digest
    ),
    CONSTRAINT ck_workspace_publication_preparation_count CHECK (
        expected_repository_count > 0
    ),
    CONSTRAINT ck_workspace_publication_preparation_lifecycle CHECK (
        (state = 'ADMITTED' AND preparing_at IS NULL AND prepared_at IS NULL AND failed_at IS NULL)
        OR (state = 'PREPARING' AND preparing_at IS NOT NULL
            AND prepared_at IS NULL AND failed_at IS NULL)
        OR (state = 'PREPARED' AND preparing_at IS NOT NULL
            AND prepared_at IS NOT NULL AND failed_at IS NULL)
        OR (state IN ('FAILED', 'INDETERMINATE') AND failed_at IS NOT NULL)
    ),
    CONSTRAINT uq_workspace_publication_preparation_evidence UNIQUE (
        run_id, accepted_generation, validation_evidence_digest
    )
);

CREATE TABLE workspace_publication_repository_receipt (
    id UUID PRIMARY KEY,
    preparation_id UUID NOT NULL
        REFERENCES workspace_publication_preparation(id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    repository_identity VARCHAR(512) NOT NULL,
    repository_identity_digest VARCHAR(64) NOT NULL,
    relative_worktree_path VARCHAR(512) NOT NULL,
    publication_branch VARCHAR(255) NOT NULL,
    provision_required BOOLEAN NOT NULL,
    commit_message TEXT NOT NULL,
    commit_message_digest VARCHAR(64) NOT NULL,
    committed_at TIMESTAMPTZ NOT NULL,
    parent_commit_id VARCHAR(64),
    tree_id VARCHAR(64),
    commit_id VARCHAR(64),
    has_changes BOOLEAN,
    failure_code VARCHAR(80),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    prepared_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_workspace_publication_repository_state CHECK (
        state IN ('ADMITTED', 'PREPARED', 'FAILED', 'INDETERMINATE')
    ),
    CONSTRAINT ck_workspace_publication_repository_identity CHECK (
        repository_identity_digest ~ '^[0-9a-f]{64}$'
        AND commit_message_digest ~ '^[0-9a-f]{64}$'
        AND encode(sha256(convert_to(repository_identity, 'UTF8')), 'hex')
            = repository_identity_digest
        AND encode(sha256(convert_to(commit_message, 'UTF8')), 'hex')
            = commit_message_digest
        AND relative_worktree_path !~ '(^/|(^|/)\.\.(/|$))'
        AND relative_worktree_path <> ''
        AND publication_branch <> ''
    ),
    CONSTRAINT ck_workspace_publication_repository_result CHECK (
        (state = 'ADMITTED' AND parent_commit_id IS NULL AND tree_id IS NULL
            AND commit_id IS NULL AND has_changes IS NULL
            AND prepared_at IS NULL AND failed_at IS NULL)
        OR (state = 'PREPARED'
            AND parent_commit_id ~ '^[0-9a-f]{40,64}$'
            AND tree_id ~ '^[0-9a-f]{40,64}$'
            AND commit_id ~ '^[0-9a-f]{40,64}$'
            AND has_changes IS NOT NULL AND prepared_at IS NOT NULL AND failed_at IS NULL)
        OR (state IN ('FAILED', 'INDETERMINATE') AND failed_at IS NOT NULL)
    ),
    CONSTRAINT uq_workspace_publication_repository UNIQUE (
        preparation_id, repository_identity_digest
    )
);

CREATE TABLE workspace_publication_attempt (
    id UUID PRIMARY KEY,
    preparation_id UUID NOT NULL
        REFERENCES workspace_publication_preparation(id) ON DELETE RESTRICT,
    repository_receipt_id UUID NOT NULL
        REFERENCES workspace_publication_repository_receipt(id) ON DELETE RESTRICT,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    repository_identity_digest VARCHAR(64) NOT NULL,
    publication_branch VARCHAR(255) NOT NULL,
    prepared_commit_id VARCHAR(64) NOT NULL,
    remote_identity_digest VARCHAR(64) NOT NULL,
    expected_remote_sha VARCHAR(64),
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    provider_receipt_digest VARCHAR(64),
    failure_code VARCHAR(80),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_workspace_publication_attempt_state CHECK (
        state IN ('ADMITTED', 'DISPATCHED', 'PUBLISHED', 'FAILED', 'INDETERMINATE')
    ),
    CONSTRAINT ck_workspace_publication_attempt_hashes CHECK (
        repository_identity_digest ~ '^[0-9a-f]{64}$'
        AND prepared_commit_id ~ '^[0-9a-f]{40,64}$'
        AND remote_identity_digest ~ '^[0-9a-f]{64}$'
        AND (expected_remote_sha IS NULL OR expected_remote_sha ~ '^[0-9a-f]{40,64}$')
        AND (provider_receipt_digest IS NULL OR provider_receipt_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_workspace_publication_attempt_lifecycle CHECK (
        (state = 'ADMITTED' AND dispatched_at IS NULL AND settled_at IS NULL)
        OR (state = 'DISPATCHED' AND dispatched_at IS NOT NULL AND settled_at IS NULL)
        OR (state IN ('PUBLISHED', 'FAILED', 'INDETERMINATE')
            AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL)
    )
);

CREATE TABLE workspace_publication_repository_provision (
    id UUID PRIMARY KEY,
    preparation_id UUID NOT NULL
        REFERENCES workspace_publication_preparation(id) ON DELETE RESTRICT,
    repository_receipt_id UUID NOT NULL
        REFERENCES workspace_publication_repository_receipt(id) ON DELETE RESTRICT,
    state VARCHAR(24) NOT NULL DEFAULT 'ADMITTED',
    provider VARCHAR(24) NOT NULL,
    target_repository_identity VARCHAR(512) NOT NULL,
    target_repository_identity_digest VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    remote_url TEXT,
    remote_identity_digest VARCHAR(64),
    failure_code VARCHAR(80),
    admitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at TIMESTAMPTZ,
    settled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_workspace_publication_provision_state CHECK (
        state IN ('ADMITTED', 'DISPATCHED', 'PROVISIONED', 'FAILED', 'INDETERMINATE')
    ),
    CONSTRAINT ck_workspace_publication_provision_hashes CHECK (
        target_repository_identity_digest ~ '^[0-9a-f]{64}$'
        AND (remote_identity_digest IS NULL OR remote_identity_digest ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_workspace_publication_provision_lifecycle CHECK (
        (state = 'ADMITTED' AND dispatched_at IS NULL AND settled_at IS NULL
            AND remote_url IS NULL AND remote_identity_digest IS NULL)
        OR (state = 'DISPATCHED' AND dispatched_at IS NOT NULL AND settled_at IS NULL
            AND remote_url IS NULL AND remote_identity_digest IS NULL)
        OR (state = 'PROVISIONED' AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL
            AND remote_url IS NOT NULL AND remote_identity_digest IS NOT NULL)
        OR (state IN ('FAILED','INDETERMINATE') AND settled_at IS NOT NULL)
    ),
    CONSTRAINT uq_workspace_publication_repository_provision UNIQUE (repository_receipt_id)
);

CREATE UNIQUE INDEX uq_workspace_publication_active_per_run
    ON workspace_publication_preparation(run_id)
    WHERE state IN ('ADMITTED', 'PREPARING');

CREATE UNIQUE INDEX uq_workspace_publication_attempt_active_repo
    ON workspace_publication_attempt(repository_receipt_id)
    WHERE state IN ('ADMITTED', 'DISPATCHED');

CREATE INDEX idx_workspace_publication_preparation_recovery
    ON workspace_publication_preparation(state, updated_at)
    WHERE state IN ('ADMITTED', 'PREPARING');

CREATE INDEX idx_workspace_publication_attempt_recovery
    ON workspace_publication_attempt(state, updated_at)
    WHERE state = 'DISPATCHED';

CREATE INDEX idx_workspace_publication_provision_recovery
    ON workspace_publication_repository_provision(state, updated_at)
    WHERE state = 'DISPATCHED';

CREATE OR REPLACE FUNCTION enforce_workspace_publication_preparation_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    r pipeline_run%ROWTYPE;
    v validation_report%ROWTYPE;
BEGIN
    IF NEW.state <> 'ADMITTED' OR NEW.preparing_at IS NOT NULL
       OR NEW.prepared_at IS NOT NULL OR NEW.failed_at IS NOT NULL THEN
        RAISE EXCEPTION 'publication preparation must be inserted as bare ADMITTED intent';
    END IF;
    SELECT * INTO r FROM pipeline_run WHERE run_id = NEW.run_id FOR SHARE;
    IF NOT FOUND
       OR r.run_mode <> 'BUILD'
       OR r.build_protocol_version <> 'SHARED_KERNEL_V2'
       OR r.accepted_workspace_generation IS DISTINCT FROM NEW.accepted_generation
       OR r.accepted_generation_attempt_id IS DISTINCT FROM NEW.accepted_attempt_id
       OR r.accepted_checkpoint_kind IS DISTINCT FROM NEW.accepted_checkpoint_kind
       OR r.accepted_checkpoint_key IS DISTINCT FROM NEW.accepted_checkpoint_key
       OR r.accepted_checkpoint_digest IS DISTINCT FROM NEW.accepted_checkpoint_digest
       OR r.accepted_checkpoint_completed_at IS DISTINCT FROM NEW.accepted_checkpoint_completed_at THEN
        RAISE EXCEPTION 'publication preparation does not match the accepted BUILD frontier';
    END IF;
    SELECT * INTO v FROM validation_report WHERE id = NEW.validation_report_id FOR SHARE;
    IF NOT FOUND OR v.run_id <> NEW.run_id OR v.status <> 'COMPLETE'
       OR v.workspace_generation IS DISTINCT FROM NEW.accepted_generation
       OR v.updated_at IS DISTINCT FROM NEW.validation_report_updated_at THEN
        RAISE EXCEPTION 'publication preparation does not match completed validation evidence';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_preparation_insert_guard
BEFORE INSERT ON workspace_publication_preparation
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_preparation_insert();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_preparation_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    prepared_repositories INTEGER;
BEGIN
    IF NEW.run_id IS DISTINCT FROM OLD.run_id
       OR NEW.accepted_generation IS DISTINCT FROM OLD.accepted_generation
       OR NEW.accepted_attempt_id IS DISTINCT FROM OLD.accepted_attempt_id
       OR NEW.accepted_checkpoint_kind IS DISTINCT FROM OLD.accepted_checkpoint_kind
       OR NEW.accepted_checkpoint_key IS DISTINCT FROM OLD.accepted_checkpoint_key
       OR NEW.accepted_checkpoint_digest IS DISTINCT FROM OLD.accepted_checkpoint_digest
       OR NEW.accepted_checkpoint_completed_at IS DISTINCT FROM OLD.accepted_checkpoint_completed_at
       OR NEW.validation_report_id IS DISTINCT FROM OLD.validation_report_id
       OR NEW.validation_evidence_json IS DISTINCT FROM OLD.validation_evidence_json
       OR NEW.validation_evidence_digest IS DISTINCT FROM OLD.validation_evidence_digest
       OR NEW.validation_report_updated_at IS DISTINCT FROM OLD.validation_report_updated_at
       OR NEW.workspace_authority_owner IS DISTINCT FROM OLD.workspace_authority_owner
       OR NEW.expected_repository_count IS DISTINCT FROM OLD.expected_repository_count
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'publication preparation identity is immutable';
    END IF;
    IF OLD.state IN ('PREPARED', 'FAILED', 'INDETERMINATE') THEN
        RAISE EXCEPTION 'terminal publication preparation is immutable';
    END IF;
    IF NOT ((OLD.state = 'ADMITTED' AND NEW.state IN ('PREPARING', 'FAILED', 'INDETERMINATE'))
        OR (OLD.state = 'PREPARING' AND NEW.state IN ('PREPARED', 'FAILED', 'INDETERMINATE'))) THEN
        RAISE EXCEPTION 'illegal publication preparation transition % -> %', OLD.state, NEW.state;
    END IF;
    IF NEW.state = 'PREPARED' THEN
        SELECT COUNT(*) INTO prepared_repositories
          FROM workspace_publication_repository_receipt
         WHERE preparation_id = NEW.id AND state = 'PREPARED';
        IF prepared_repositories <> NEW.expected_repository_count THEN
            RAISE EXCEPTION 'publication preparation repository set is incomplete';
        END IF;
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_preparation_transition_guard
BEFORE UPDATE ON workspace_publication_preparation
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_preparation_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_repository_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preparation_id IS DISTINCT FROM OLD.preparation_id
       OR NEW.repository_identity IS DISTINCT FROM OLD.repository_identity
       OR NEW.repository_identity_digest IS DISTINCT FROM OLD.repository_identity_digest
       OR NEW.relative_worktree_path IS DISTINCT FROM OLD.relative_worktree_path
       OR NEW.publication_branch IS DISTINCT FROM OLD.publication_branch
       OR NEW.provision_required IS DISTINCT FROM OLD.provision_required
       OR NEW.commit_message IS DISTINCT FROM OLD.commit_message
       OR NEW.commit_message_digest IS DISTINCT FROM OLD.commit_message_digest
       OR NEW.committed_at IS DISTINCT FROM OLD.committed_at
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'publication repository identity is immutable';
    END IF;
    IF OLD.state <> 'ADMITTED' OR NEW.state NOT IN ('PREPARED', 'FAILED', 'INDETERMINATE') THEN
        RAISE EXCEPTION 'illegal publication repository transition % -> %', OLD.state, NEW.state;
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_workspace_publication_repository_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p_state VARCHAR(24);
BEGIN
    IF NEW.state <> 'ADMITTED' OR NEW.parent_commit_id IS NOT NULL OR NEW.tree_id IS NOT NULL
       OR NEW.commit_id IS NOT NULL OR NEW.has_changes IS NOT NULL
       OR NEW.prepared_at IS NOT NULL OR NEW.failed_at IS NOT NULL THEN
        RAISE EXCEPTION 'publication repository must be inserted as bare ADMITTED intent';
    END IF;
    SELECT state INTO p_state FROM workspace_publication_preparation
     WHERE id=NEW.preparation_id FOR SHARE;
    IF p_state <> 'ADMITTED' THEN
        RAISE EXCEPTION 'publication repository admission requires ADMITTED preparation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_repository_insert_guard
BEFORE INSERT ON workspace_publication_repository_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_repository_insert();

CREATE TRIGGER workspace_publication_repository_transition_guard
BEFORE UPDATE ON workspace_publication_repository_receipt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_repository_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_attempt_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p_state VARCHAR(24);
    rr workspace_publication_repository_receipt%ROWTYPE;
BEGIN
    IF NEW.state <> 'ADMITTED' OR NEW.dispatched_at IS NOT NULL OR NEW.settled_at IS NOT NULL THEN
        RAISE EXCEPTION 'publication attempt must be inserted as bare ADMITTED intent';
    END IF;
    SELECT state INTO p_state FROM workspace_publication_preparation
     WHERE id = NEW.preparation_id FOR SHARE;
    SELECT * INTO rr FROM workspace_publication_repository_receipt
     WHERE id = NEW.repository_receipt_id FOR SHARE;
    IF p_state <> 'PREPARED' OR NOT FOUND OR rr.preparation_id <> NEW.preparation_id
       OR rr.state <> 'PREPARED'
       OR rr.repository_identity_digest <> NEW.repository_identity_digest
       OR rr.publication_branch <> NEW.publication_branch
       OR rr.commit_id <> NEW.prepared_commit_id THEN
        RAISE EXCEPTION 'publication attempt does not match a prepared repository receipt';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_attempt_insert_guard
BEFORE INSERT ON workspace_publication_attempt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_attempt_insert();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_attempt_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preparation_id IS DISTINCT FROM OLD.preparation_id
       OR NEW.repository_receipt_id IS DISTINCT FROM OLD.repository_receipt_id
       OR NEW.repository_identity_digest IS DISTINCT FROM OLD.repository_identity_digest
       OR NEW.publication_branch IS DISTINCT FROM OLD.publication_branch
       OR NEW.prepared_commit_id IS DISTINCT FROM OLD.prepared_commit_id
       OR NEW.remote_identity_digest IS DISTINCT FROM OLD.remote_identity_digest
       OR NEW.expected_remote_sha IS DISTINCT FROM OLD.expected_remote_sha
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'publication attempt identity is immutable';
    END IF;
    IF OLD.state IN ('PUBLISHED', 'FAILED', 'INDETERMINATE') THEN
        RAISE EXCEPTION 'terminal publication attempt is immutable';
    END IF;
    IF NOT ((OLD.state = 'ADMITTED' AND NEW.state IN ('DISPATCHED', 'FAILED', 'INDETERMINATE'))
        OR (OLD.state = 'DISPATCHED' AND NEW.state IN ('PUBLISHED', 'FAILED', 'INDETERMINATE'))) THEN
        RAISE EXCEPTION 'illegal publication attempt transition % -> %', OLD.state, NEW.state;
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_attempt_transition_guard
BEFORE UPDATE ON workspace_publication_attempt
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_attempt_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_provision_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    p_state VARCHAR(24);
    rr workspace_publication_repository_receipt%ROWTYPE;
BEGIN
    IF NEW.state <> 'ADMITTED' OR NEW.dispatched_at IS NOT NULL OR NEW.settled_at IS NOT NULL
       OR NEW.remote_url IS NOT NULL OR NEW.remote_identity_digest IS NOT NULL THEN
        RAISE EXCEPTION 'repository provision must be inserted as bare ADMITTED intent';
    END IF;
    SELECT state INTO p_state FROM workspace_publication_preparation
     WHERE id=NEW.preparation_id FOR SHARE;
    SELECT * INTO rr FROM workspace_publication_repository_receipt
     WHERE id=NEW.repository_receipt_id FOR SHARE;
    IF p_state <> 'PREPARED' OR NOT FOUND OR rr.preparation_id <> NEW.preparation_id
       OR rr.state <> 'PREPARED' OR NOT rr.provision_required
       OR rr.repository_identity <> NEW.target_repository_identity
       OR rr.repository_identity_digest <> NEW.target_repository_identity_digest THEN
        RAISE EXCEPTION 'repository provision does not match prepared target identity';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_provision_insert_guard
BEFORE INSERT ON workspace_publication_repository_provision
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_provision_insert();

CREATE OR REPLACE FUNCTION enforce_workspace_publication_provision_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preparation_id IS DISTINCT FROM OLD.preparation_id
       OR NEW.repository_receipt_id IS DISTINCT FROM OLD.repository_receipt_id
       OR NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.target_repository_identity IS DISTINCT FROM OLD.target_repository_identity
       OR NEW.target_repository_identity_digest IS DISTINCT FROM OLD.target_repository_identity_digest
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'repository provision identity is immutable';
    END IF;
    IF OLD.state IN ('PROVISIONED','FAILED','INDETERMINATE') THEN
        RAISE EXCEPTION 'terminal repository provision is immutable';
    END IF;
    IF NOT ((OLD.state='ADMITTED' AND NEW.state IN ('DISPATCHED','FAILED','INDETERMINATE'))
        OR (OLD.state='DISPATCHED' AND NEW.state IN ('PROVISIONED','FAILED','INDETERMINATE'))) THEN
        RAISE EXCEPTION 'illegal repository provision transition % -> %', OLD.state, NEW.state;
    END IF;
    IF NEW.state='PROVISIONED' AND NEW.remote_identity_digest
        IS DISTINCT FROM NEW.target_repository_identity_digest THEN
        RAISE EXCEPTION 'provisioned remote does not match prepared target identity';
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER workspace_publication_provision_transition_guard
BEFORE UPDATE ON workspace_publication_repository_provision
FOR EACH ROW EXECUTE FUNCTION enforce_workspace_publication_provision_transition();

CREATE OR REPLACE FUNCTION reject_workspace_publication_delete()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'publication protocol rows are append-only while the run exists';
END;
$$;

CREATE TRIGGER workspace_publication_preparation_delete_guard
BEFORE DELETE ON workspace_publication_preparation
FOR EACH ROW
WHEN (pg_trigger_depth() = 0)
EXECUTE FUNCTION reject_workspace_publication_delete();

CREATE TRIGGER workspace_publication_repository_delete_guard
BEFORE DELETE ON workspace_publication_repository_receipt
FOR EACH ROW
WHEN (pg_trigger_depth() = 0)
EXECUTE FUNCTION reject_workspace_publication_delete();

CREATE TRIGGER workspace_publication_attempt_delete_guard
BEFORE DELETE ON workspace_publication_attempt
FOR EACH ROW
WHEN (pg_trigger_depth() = 0)
EXECUTE FUNCTION reject_workspace_publication_delete();

CREATE TRIGGER workspace_publication_provision_delete_guard
BEFORE DELETE ON workspace_publication_repository_provision
FOR EACH ROW
WHEN (pg_trigger_depth() = 0)
EXECUTE FUNCTION reject_workspace_publication_delete();
