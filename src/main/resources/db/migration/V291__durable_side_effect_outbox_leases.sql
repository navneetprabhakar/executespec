-- Cross-JVM-safe delivery claims for the saga side-effect outbox.
--
-- Delivery is deliberately at-least-once. A claim is committed before its external handler runs,
-- stale PROCESSING rows are reclaimable after lease expiry, and every state transition is fenced
-- by the monotonically increasing lease_token. Only handlers with a versioned idempotency contract
-- may be newly enqueued by the application.

ALTER TABLE side_effect_outbox
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN handler_contract_version VARCHAR(32),
    ADD COLUMN lease_owner VARCHAR(160),
    ADD COLUMN lease_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN heartbeat_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_started_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_completed_at TIMESTAMPTZ,
    ADD COLUMN last_attempt_evidence JSONB,
    ADD COLUMN terminal_evidence JSONB;

UPDATE side_effect_outbox
SET idempotency_key = 'side-effect:' || id::text,
    handler_contract_version = CASE
        WHEN effect_type IN ('GCS_ARCHIVE', 'COMPENSATE_GITHUB_BRANCH') THEN 'v1'
        ELSE 'legacy-unverified'
    END;

-- Pre-V291 PROCESSING rows had no durable owner and could never be reclaimed. Put them back on the
-- due queue; the first V291 claimant receives a new fencing token before invoking a handler.
UPDATE side_effect_outbox
SET status = 'PENDING',
    next_retry_at = LEAST(next_retry_at, NOW()),
    last_error = COALESCE(last_error, 'Recovered unowned pre-V291 PROCESSING row')
WHERE status = 'PROCESSING';

-- Preserve an honest upgrade record for rows that were already terminal before V291 introduced
-- structured evidence. This does not invent handler proof; it labels the source as legacy state.
UPDATE side_effect_outbox
SET terminal_evidence = jsonb_build_object(
        'outcome', status,
        'source', 'PRE_V291_TERMINAL_STATE',
        'attempt', attempt,
        'lastError', last_error),
    last_attempt_completed_at = COALESCE(completed_at, created_at)
WHERE status IN ('COMPLETED', 'FAILED');

ALTER TABLE side_effect_outbox
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN handler_contract_version SET NOT NULL,
    ALTER COLUMN handler_contract_version SET DEFAULT 'v1',
    ADD CONSTRAINT ck_side_effect_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    ADD CONSTRAINT ck_side_effect_outbox_attempt
        CHECK (attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts),
    ADD CONSTRAINT ck_side_effect_outbox_lease
        CHECK (
            (status = 'PROCESSING'
                AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL)
            OR
            (status <> 'PROCESSING'
                AND lease_owner IS NULL
                AND lease_expires_at IS NULL)
        ),
    ADD CONSTRAINT ck_side_effect_outbox_terminal_evidence
        CHECK (status NOT IN ('COMPLETED', 'FAILED') OR terminal_evidence IS NOT NULL);

CREATE UNIQUE INDEX uq_side_effect_outbox_idempotency
    ON side_effect_outbox (idempotency_key);

DROP INDEX IF EXISTS idx_side_effect_outbox_pending;

CREATE INDEX idx_side_effect_outbox_pending
    ON side_effect_outbox (next_retry_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_side_effect_outbox_stale_lease
    ON side_effect_outbox (lease_expires_at, created_at)
    WHERE status = 'PROCESSING';

COMMENT ON COLUMN side_effect_outbox.idempotency_key IS
    'Stable handler key reused by every retry of this at-least-once delivery';
COMMENT ON COLUMN side_effect_outbox.handler_contract_version IS
    'Pinned idempotency/replay contract version for the selected handler';
COMMENT ON COLUMN side_effect_outbox.lease_token IS
    'Monotonic fencing token; stale owners cannot heartbeat or persist an outcome';
COMMENT ON COLUMN side_effect_outbox.terminal_evidence IS
    'Bounded, secret-free durable evidence for COMPLETED or FAILED delivery';
