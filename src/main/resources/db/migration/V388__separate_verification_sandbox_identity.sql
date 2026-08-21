-- Phase 3: a verifier must never reuse or remount the canonical run sandbox.
ALTER TABLE run_sandbox
    ADD COLUMN purpose VARCHAR(24) NOT NULL DEFAULT 'RUN';

ALTER TABLE run_sandbox
    ADD CONSTRAINT ck_run_sandbox_purpose
        CHECK (purpose IN ('RUN', 'VERIFICATION'));

DROP INDEX uq_run_sandbox_active_run;

CREATE UNIQUE INDEX uq_run_sandbox_active_run_purpose
    ON run_sandbox (run_id, purpose)
    WHERE run_id IS NOT NULL
      AND state NOT IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED');

COMMENT ON COLUMN run_sandbox.purpose IS
    'Immutable mount identity. RUN derives agent.workspace/<runId>; VERIFICATION derives sandbox.workspace-root/<sandboxId>/workspace. The two must never be remounted or reused as each other.';

CREATE TABLE run_sandbox_workspace_cleanup (
    sandbox_id UUID PRIMARY KEY REFERENCES run_sandbox(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    claimed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_run_sandbox_workspace_cleanup_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_run_sandbox_workspace_cleanup_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_run_sandbox_workspace_cleanup_completed CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status <> 'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE OR REPLACE FUNCTION enqueue_verification_sandbox_workspace_cleanup()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.purpose = 'VERIFICATION' THEN
        INSERT INTO run_sandbox_workspace_cleanup(sandbox_id) VALUES (NEW.id);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER run_sandbox_verification_workspace_cleanup_enqueue
AFTER INSERT ON run_sandbox
FOR EACH ROW EXECUTE FUNCTION enqueue_verification_sandbox_workspace_cleanup();

COMMENT ON TABLE run_sandbox_workspace_cleanup IS
    'Crash-recoverable deletion intent for sandbox-owned verification workspaces. Rows become claimable only after the physical sandbox is terminal.';
