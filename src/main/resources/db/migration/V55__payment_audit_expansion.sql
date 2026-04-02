-- V55: Expand payment_audit with gateway tracking, and create webhook_event_log
-- for idempotent webhook processing.

-- ── Expand payment_audit ─────────────────────────────────────────────────────
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS gateway_provider VARCHAR(20);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS gateway_event_id VARCHAR(255);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS gateway_payment_id VARCHAR(255);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS currency VARCHAR(8);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS amount_minor BIGINT;
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS metadata JSONB;
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(255);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS user_id VARCHAR(255);
ALTER TABLE payment_audit ADD COLUMN IF NOT EXISTS org_id VARCHAR(255);

-- Additional indexes for payment_audit
CREATE INDEX IF NOT EXISTS idx_payment_audit_event_type ON payment_audit (event_type);
CREATE INDEX IF NOT EXISTS idx_payment_audit_gateway ON payment_audit (gateway_provider);
CREATE INDEX IF NOT EXISTS idx_payment_audit_created ON payment_audit (created_at);
CREATE INDEX IF NOT EXISTS idx_payment_audit_idempotency ON payment_audit (idempotency_key);
CREATE INDEX IF NOT EXISTS idx_payment_audit_user ON payment_audit (user_id);
CREATE INDEX IF NOT EXISTS idx_payment_audit_org ON payment_audit (org_id);

-- ── webhook_event_log ────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS webhook_event_log (
    id                BIGSERIAL       PRIMARY KEY,
    gateway           VARCHAR(20)     NOT NULL,
    event_id          VARCHAR(255)    NOT NULL,
    event_type        VARCHAR(128)    NOT NULL,
    payload_hash      VARCHAR(64),
    raw_payload       TEXT,
    processing_status VARCHAR(32)     NOT NULL DEFAULT 'RECEIVED',
    -- RECEIVED, PROCESSED, IGNORED (duplicate), FAILED
    error_message     TEXT,
    attempts          INT             NOT NULL DEFAULT 1,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    processed_at      TIMESTAMP WITH TIME ZONE,
    UNIQUE (gateway, event_id)
);

CREATE INDEX IF NOT EXISTS idx_webhook_event_gateway ON webhook_event_log (gateway);
CREATE INDEX IF NOT EXISTS idx_webhook_event_type ON webhook_event_log (event_type);
CREATE INDEX IF NOT EXISTS idx_webhook_event_status ON webhook_event_log (processing_status);
CREATE INDEX IF NOT EXISTS idx_webhook_event_created ON webhook_event_log (created_at);
