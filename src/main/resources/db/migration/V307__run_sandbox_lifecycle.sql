-- =====================================================================
-- V307: Durable run-sandbox lifecycle (Phase 5, dark).
--
-- A remote coding sandbox is a security and runtime domain, not a UI label.
-- Before any customer exposure it needs durable identity, an explicit state
-- machine, fenced cross-JVM ownership, isolation provenance and recovery.
--
-- This migration lands the persistence half ONLY. No runtime executes: the
-- default `RunSandboxRuntime` is the NOT_ENABLED no-op and all three flags
-- below are seeded false.
--
-- Fencing follows the V289 project-export and V299 review-job precedent:
-- `runtime_owner` + monotonic `lease_token`, so a stale JVM that lost its
-- lease cannot heartbeat, transition or publish a terminal state after a
-- takeover.
-- =====================================================================

CREATE TABLE run_sandbox (
    id UUID PRIMARY KEY,

    -- Identity. run/project/session are nullable because a sandbox may be
    -- requested from an agent session before a pipeline run exists; actor and
    -- org are never nullable because every sandbox has an accountable owner.
    run_id VARCHAR(255),
    project_id VARCHAR(255),
    session_id UUID,
    requested_by_user_id VARCHAR(255) NOT NULL,
    org_id VARCHAR(255) NOT NULL,

    -- Immutable source identity. A sandbox is bound to the exact revision and
    -- repository set it was admitted with; a later branch move cannot silently
    -- redefine what was executed.
    source_revision VARCHAR(80) NOT NULL,
    repository_set JSONB NOT NULL,

    -- Lifecycle.
    state VARCHAR(24) NOT NULL DEFAULT 'REQUESTED',

    -- Isolation provenance. Version is separate from the profile name so a
    -- hardening change is visible in historical rows.
    isolation_profile VARCHAR(40) NOT NULL,
    isolation_profile_version INTEGER NOT NULL,

    -- Fenced cross-JVM runtime ownership.
    runtime_owner VARCHAR(160),
    lease_token BIGINT NOT NULL DEFAULT 0,
    lease_expires_at TIMESTAMPTZ,
    runtime_epoch BIGINT NOT NULL DEFAULT 0,
    heartbeat_at TIMESTAMPTZ,
    runtime_handle VARCHAR(160),

    -- What was actually executed.
    image_digest VARCHAR(160),
    config_digest VARCHAR(160) NOT NULL,

    -- Policy in force for this sandbox, recorded rather than assumed.
    network_policy VARCHAR(32) NOT NULL DEFAULT 'DENY_ALL',
    secret_policy VARCHAR(32) NOT NULL DEFAULT 'NO_HOST_SECRETS',

    -- Expiry, failure classification and artifact retention.
    expires_at TIMESTAMPTZ NOT NULL,
    failure_class VARCHAR(48),
    failure_detail VARCHAR(2000),
    retention_state VARCHAR(24) NOT NULL DEFAULT 'RETAINED',
    artifact_uri VARCHAR(512),

    idempotency_key VARCHAR(160) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at TIMESTAMPTZ,
    ready_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,

    CONSTRAINT ck_run_sandbox_state CHECK (
        state IN ('REQUESTED', 'PROVISIONING', 'READY', 'CANCEL_REQUESTED',
                  'RELEASING', 'TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED')
    ),
    CONSTRAINT ck_run_sandbox_isolation CHECK (
        isolation_profile IN ('NOT_ENABLED', 'CONTAINER_STRICT_V1')
        AND isolation_profile_version >= 1
    ),
    CONSTRAINT ck_run_sandbox_network CHECK (
        network_policy IN ('DENY_ALL', 'ALLOWLIST_PROXY')
    ),
    CONSTRAINT ck_run_sandbox_secret CHECK (
        secret_policy IN ('NO_HOST_SECRETS')
    ),
    CONSTRAINT ck_run_sandbox_retention CHECK (
        retention_state IN ('RETAINED', 'PURGE_PENDING', 'PURGED')
    ),
    -- A live runtime owner exists exactly in the states where a runtime is
    -- actively responsible for the sandbox. Terminal rows never hold a lease,
    -- so reconciliation cannot mistake a finished sandbox for an orphan.
    CONSTRAINT ck_run_sandbox_lease CHECK (
        (state IN ('PROVISIONING', 'READY', 'CANCEL_REQUESTED', 'RELEASING')
            AND runtime_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state NOT IN ('PROVISIONING', 'READY', 'CANCEL_REQUESTED', 'RELEASING')
            AND runtime_owner IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_run_sandbox_terminal_at CHECK (
        (state IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED') AND terminal_at IS NOT NULL)
        OR (state NOT IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED') AND terminal_at IS NULL)
    ),
    -- Failure classification is mandatory for FAILED and forbidden elsewhere,
    -- so an unexplained failure cannot be persisted as an ordinary stop.
    CONSTRAINT ck_run_sandbox_failure_class CHECK (
        (state = 'FAILED' AND failure_class IS NOT NULL)
        OR (state <> 'FAILED' AND failure_class IS NULL)
    )
);

CREATE UNIQUE INDEX uq_run_sandbox_idempotency ON run_sandbox (idempotency_key);

-- At most one non-terminal sandbox per run, enforced by the database rather
-- than by an application read-then-write race.
CREATE UNIQUE INDEX uq_run_sandbox_active_run
    ON run_sandbox (run_id)
    WHERE run_id IS NOT NULL
      AND state NOT IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED');

CREATE INDEX idx_run_sandbox_claim
    ON run_sandbox (created_at)
    WHERE state = 'REQUESTED';

-- Startup reconciliation and orphan sweeps read this index: any non-terminal
-- row whose lease has expired is owned by a JVM that is gone.
CREATE INDEX idx_run_sandbox_stale_lease
    ON run_sandbox (lease_expires_at)
    WHERE state IN ('PROVISIONING', 'READY', 'CANCEL_REQUESTED', 'RELEASING');

CREATE INDEX idx_run_sandbox_expiry
    ON run_sandbox (expires_at)
    WHERE state NOT IN ('TERMINATED', 'CANCELLED', 'EXPIRED', 'FAILED');

CREATE INDEX idx_run_sandbox_org_created ON run_sandbox (org_id, created_at DESC);

COMMENT ON COLUMN run_sandbox.lease_token IS
    'Monotonic fencing token. A stale JVM cannot heartbeat, transition or publish a terminal state after a lease takeover increments this.';
COMMENT ON COLUMN run_sandbox.runtime_epoch IS
    'Increments once per runtime attempt. Distinguishes a retried provisioning attempt from a lease renewal.';
COMMENT ON COLUMN run_sandbox.repository_set IS
    'Immutable admitted repository identities. Credentials are excluded and re-resolved from requested_by_user_id.';
COMMENT ON COLUMN run_sandbox.config_digest IS
    'Digest of the exact resolved runtime configuration (limits, mounts, policy). Makes "what was actually enforced" auditable rather than inferred.';
COMMENT ON COLUMN run_sandbox.network_policy IS
    'DENY_ALL is the only profile implemented. ALLOWLIST_PROXY is reserved for a future controlled egress proxy and is not yet enforceable.';

-- =====================================================================
-- Three INDEPENDENT flags, all seeded false.
--
-- The plan is explicit that one flag must not gate persistence, runtime and
-- exposure together. The rollback invariants are:
--   * disabling UI exposure must not destroy lifecycle rows;
--   * disabling dispatch must still allow cancellation, cleanup and
--     reconciliation of already-created sandboxes.
-- =====================================================================
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('sandbox.lifecycle.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Phase 5 — allow creation of new run_sandbox lifecycle rows. OFF rejects new sandboxes; existing rows stay readable, cancellable and reconcilable.'),
    ('sandbox.runtime.dispatch.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Phase 5 — allow the RunSandboxRuntime dispatcher to start work. OFF still permits cancellation, teardown, orphan reconciliation and expiry.'),
    ('sandbox.runtime.container-strict-v1.enabled', 'false', 'BOOLEAN', 'PLATFORM',
     'Phase 5 — select the CONTAINER_STRICT_V1 hardened container runtime. OFF resolves to the NOT_ENABLED no-op runtime, which executes nothing.')
ON CONFLICT (flag_key) DO NOTHING;
