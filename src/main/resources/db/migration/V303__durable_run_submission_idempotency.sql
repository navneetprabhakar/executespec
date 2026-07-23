-- Durable, cross-JVM atomic idempotency for run submission (V303).
--
-- Supersedes the Redis lookup-then-record dedupe in RunIdempotencyService, which performed a
-- SEPARATE read and write and therefore admitted simultaneous duplicate creation: two truly
-- parallel POST /api/runs calls carrying the same Idempotency-Key could both miss the lookup and
-- create two runs plus two quota reservations.
--
-- The atomicity here comes from uq_run_submission_idempotency_actor_key. Exactly one caller can
-- INSERT the (actor, key) row; every other caller collides and is resolved deterministically
-- against the persisted state machine rather than a best-effort cache read. PostgreSQL is the
-- authority precisely because the previous Redis design was fail-open and non-durable.

CREATE TABLE run_submission_idempotency (
    id UUID PRIMARY KEY,
    actor_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    run_id VARCHAR(64),
    lease_owner VARCHAR(160),
    fencing_token BIGINT NOT NULL DEFAULT 1,
    lease_expires_at TIMESTAMPTZ,
    failure_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_run_idem_state CHECK (
        state IN ('CLAIMED', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED_FINAL')
    ),
    CONSTRAINT ck_run_idem_completed_has_run CHECK (
        state <> 'COMPLETED' OR run_id IS NOT NULL
    ),
    CONSTRAINT ck_run_idem_claim_has_lease CHECK (
        (state = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state <> 'CLAIMED' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_run_idem_fencing CHECK (fencing_token > 0)
);

-- The claim primitive. Without this unique index the INSERT .. ON CONFLICT claim is not atomic.
CREATE UNIQUE INDEX uq_run_submission_idempotency_actor_key
    ON run_submission_idempotency (actor_id, idempotency_key);

-- Recovery scan for claims whose winning JVM died before publishing a terminal state.
CREATE INDEX idx_run_submission_idempotency_stale_lease
    ON run_submission_idempotency (lease_expires_at)
    WHERE state = 'CLAIMED';

-- Retention sweep; records stay readable across rollback versions until they expire.
CREATE INDEX idx_run_submission_idempotency_expiry
    ON run_submission_idempotency (expires_at);

COMMENT ON TABLE run_submission_idempotency IS
    'Durable atomic claim/state machine for POST /api/runs. Exactly one run and one quota reservation per (actor_id, idempotency_key).';
COMMENT ON COLUMN run_submission_idempotency.request_hash IS
    'SHA-256 of the canonical submission fingerprint. A reused key with a different body is a conflict and must never silently return the first run.';
COMMENT ON COLUMN run_submission_idempotency.lease_expires_at IS
    'Dead-winner recovery bound. A CLAIMED row whose lease has elapsed is stealable, so a retry can never deadlock forever on a crashed claimant.';
COMMENT ON COLUMN run_submission_idempotency.fencing_token IS
    'Monotonic per-claim token. A superseded claimant cannot publish COMPLETED over the JVM that legitimately stole the claim.';
COMMENT ON COLUMN run_submission_idempotency.state IS
    'CLAIMED (in flight) | COMPLETED (run created) | FAILED_RETRYABLE (stealable) | FAILED_FINAL (deterministic client rejection; not stealable).';
