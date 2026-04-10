-- V68: SSE event log (replay on reconnect) + pipeline run instance tracking
-- Part of Issue #3: Distributed SSE + encrypted Redis RunKeyStore

-- Event log for SSE replay across instances and on reconnect.
-- Events are written on every emit(); clients reconnecting with Last-Event-ID
-- can request replay from a given sequence number.
CREATE TABLE IF NOT EXISTS event_log (
    id             BIGSERIAL PRIMARY KEY,
    run_id         UUID          NOT NULL,
    event_type     VARCHAR(50)   NOT NULL,
    payload        JSONB         NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- Index for efficient replay queries (run_id + id range scan)
CREATE INDEX IF NOT EXISTS idx_event_log_run_id_id ON event_log (run_id, id);

-- Auto-purge old events after 24 hours via a partial index hint.
-- Actual deletion is handled by DataRetentionService (no pg_cron required).

-- Instance tracking: lets any instance detect a dead peer and recover its runs.
-- executing_instance  = hostname/pod name that owns this run's pipeline thread
-- execution_heartbeat = last time the owning instance wrote a heartbeat
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS executing_instance   VARCHAR(128),
    ADD COLUMN IF NOT EXISTS execution_heartbeat  TIMESTAMPTZ;

COMMENT ON COLUMN pipeline_run.executing_instance  IS 'Hostname of the instance currently executing this run';
COMMENT ON COLUMN pipeline_run.execution_heartbeat IS 'Last heartbeat from the executing instance (updated every 30s)';
