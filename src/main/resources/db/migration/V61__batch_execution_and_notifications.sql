-- ═══════════════════════════════════════════════════════════════════════════
-- V61: Batch execution support + notification system
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Batch fields on pipeline_run ─────────────────────────────────────────
ALTER TABLE pipeline_run ADD COLUMN approved_execution_mode VARCHAR(10) DEFAULT 'REALTIME';
ALTER TABLE pipeline_run ADD COLUMN batch_id VARCHAR(255);
ALTER TABLE pipeline_run ADD COLUMN batch_provider VARCHAR(20);
ALTER TABLE pipeline_run ADD COLUMN batch_progress_completed INTEGER;
ALTER TABLE pipeline_run ADD COLUMN batch_progress_total INTEGER;
ALTER TABLE pipeline_run ADD COLUMN batch_submitted_at TIMESTAMP;
ALTER TABLE pipeline_run ADD COLUMN batch_credits INTEGER;
ALTER TABLE pipeline_run ADD COLUMN batch_available BOOLEAN;
ALTER TABLE pipeline_run ADD COLUMN credit_savings INTEGER;

-- ── Cost log batch columns ───────────────────────────────────────────────
ALTER TABLE run_cost_log ADD COLUMN execution_mode VARCHAR(10) NOT NULL DEFAULT 'REALTIME';
ALTER TABLE run_cost_log ADD COLUMN batch_discount DOUBLE PRECISION DEFAULT 0.00;
ALTER TABLE run_cost_log ADD COLUMN batch_validation_fail_rate DOUBLE PRECISION;
CREATE INDEX idx_run_cost_mode ON run_cost_log(execution_mode);

-- ── Notification table ───────────────────────────────────────────────────
CREATE TABLE notification (
    id              VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id         VARCHAR(255) NOT NULL,
    type            VARCHAR(30)  NOT NULL,
    title           VARCHAR(200) NOT NULL,
    body            TEXT,
    link            VARCHAR(500),
    read            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_user_read    ON notification(user_id, read);
CREATE INDEX idx_notification_user_created ON notification(user_id, created_at DESC);
