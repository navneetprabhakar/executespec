-- Idempotent, restart-safe compensation for the one-run quota charged at dispatch.
-- The row is the durable intent; quota and billing counters are decremented in one transaction
-- before the row moves to APPLIED. A unique run_id prevents duplicate terminal events from
-- refunding the same run twice across JVMs.

CREATE TABLE run_quota_refund (
    run_id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    credits INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_at TIMESTAMPTZ,
    CONSTRAINT ck_run_quota_refund_credits CHECK (credits > 0),
    CONSTRAINT ck_run_quota_refund_status CHECK (status IN ('PENDING', 'APPLIED'))
);

CREATE INDEX idx_run_quota_refund_pending
    ON run_quota_refund (next_attempt_at, created_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE run_quota_refund IS
    'Durable idempotency and recovery ledger for failed-run quota compensation.';
