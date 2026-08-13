-- Close both external-publication crash windows without guessing remote success.
-- ADMITTED means no external supplier can have run because dispatch is committed first; recovery
-- may therefore fail that physical attempt and permit a new numbered attempt. DISPATCHED means a
-- remote side effect may have happened and is permanently INDETERMINATE unless provider evidence
-- is reconciled by a future explicit protocol.

ALTER TABLE workspace_publication_attempt
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;

ALTER TABLE workspace_publication_repository_provision
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1;

ALTER TABLE workspace_publication_attempt
    ADD CONSTRAINT ck_workspace_publication_attempt_number CHECK (attempt_number > 0);

ALTER TABLE workspace_publication_repository_provision
    ADD CONSTRAINT ck_workspace_publication_provision_attempt_number CHECK (attempt_number > 0);

ALTER TABLE workspace_publication_attempt
    DROP CONSTRAINT ck_workspace_publication_attempt_lifecycle;

ALTER TABLE workspace_publication_attempt
    ADD CONSTRAINT ck_workspace_publication_attempt_lifecycle CHECK (
        (state = 'ADMITTED' AND dispatched_at IS NULL AND settled_at IS NULL)
        OR (state = 'DISPATCHED' AND dispatched_at IS NOT NULL AND settled_at IS NULL)
        OR (state IN ('PUBLISHED', 'INDETERMINATE')
            AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL)
        OR (state = 'FAILED' AND dispatched_at IS NULL AND settled_at IS NOT NULL)
    );

ALTER TABLE workspace_publication_repository_provision
    DROP CONSTRAINT ck_workspace_publication_provision_lifecycle;

ALTER TABLE workspace_publication_repository_provision
    ADD CONSTRAINT ck_workspace_publication_provision_lifecycle CHECK (
        (state = 'ADMITTED' AND dispatched_at IS NULL AND settled_at IS NULL
            AND remote_url IS NULL AND remote_identity_digest IS NULL)
        OR (state = 'DISPATCHED' AND dispatched_at IS NOT NULL AND settled_at IS NULL
            AND remote_url IS NULL AND remote_identity_digest IS NULL)
        OR (state = 'PROVISIONED' AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL
            AND remote_url IS NOT NULL AND remote_identity_digest IS NOT NULL)
        OR (state = 'INDETERMINATE' AND dispatched_at IS NOT NULL AND settled_at IS NOT NULL)
        OR (state = 'FAILED' AND dispatched_at IS NULL AND settled_at IS NOT NULL)
    );

ALTER TABLE workspace_publication_repository_provision
    DROP CONSTRAINT uq_workspace_publication_repository_provision;

CREATE UNIQUE INDEX uq_workspace_publication_attempt_sequence
    ON workspace_publication_attempt(repository_receipt_id, attempt_number);

CREATE UNIQUE INDEX uq_workspace_publication_provision_sequence
    ON workspace_publication_repository_provision(repository_receipt_id, attempt_number);

CREATE UNIQUE INDEX uq_workspace_publication_provision_active_repo
    ON workspace_publication_repository_provision(repository_receipt_id)
    WHERE state IN ('ADMITTED', 'DISPATCHED');

DROP INDEX idx_workspace_publication_attempt_recovery;
CREATE INDEX idx_workspace_publication_attempt_recovery
    ON workspace_publication_attempt(state, COALESCE(dispatched_at, admitted_at))
    WHERE state IN ('ADMITTED', 'DISPATCHED');

DROP INDEX idx_workspace_publication_provision_recovery;
CREATE INDEX idx_workspace_publication_provision_recovery
    ON workspace_publication_repository_provision(
        state, COALESCE(dispatched_at, admitted_at))
    WHERE state IN ('ADMITTED', 'DISPATCHED');

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
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'publication attempt identity is immutable';
    END IF;
    IF OLD.state IN ('PUBLISHED', 'FAILED', 'INDETERMINATE') THEN
        RAISE EXCEPTION 'terminal publication attempt is immutable';
    END IF;
    IF NOT ((OLD.state = 'ADMITTED' AND NEW.state IN ('DISPATCHED', 'FAILED', 'INDETERMINATE'))
        OR (OLD.state = 'DISPATCHED' AND NEW.state IN ('PUBLISHED', 'INDETERMINATE'))) THEN
        RAISE EXCEPTION 'illegal publication attempt transition % -> %', OLD.state, NEW.state;
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_workspace_publication_provision_transition()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.preparation_id IS DISTINCT FROM OLD.preparation_id
       OR NEW.repository_receipt_id IS DISTINCT FROM OLD.repository_receipt_id
       OR NEW.provider IS DISTINCT FROM OLD.provider
       OR NEW.target_repository_identity IS DISTINCT FROM OLD.target_repository_identity
       OR NEW.target_repository_identity_digest IS DISTINCT FROM OLD.target_repository_identity_digest
       OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
       OR NEW.attempt_number IS DISTINCT FROM OLD.attempt_number
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'repository provision identity is immutable';
    END IF;
    IF OLD.state IN ('PROVISIONED', 'FAILED', 'INDETERMINATE') THEN
        RAISE EXCEPTION 'terminal repository provision is immutable';
    END IF;
    IF NOT ((OLD.state = 'ADMITTED' AND NEW.state IN ('DISPATCHED', 'FAILED', 'INDETERMINATE'))
        OR (OLD.state = 'DISPATCHED' AND NEW.state IN ('PROVISIONED', 'INDETERMINATE'))) THEN
        RAISE EXCEPTION 'illegal repository provision transition % -> %', OLD.state, NEW.state;
    END IF;
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;
