CREATE TABLE IF NOT EXISTS managed_ai_usage_period (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    org_id VARCHAR(64),
    tier VARCHAR(32) NOT NULL,
    period_start TIMESTAMPTZ NOT NULL,
    period_end TIMESTAMPTZ NOT NULL,
    included_ai_credits BIGINT NOT NULL DEFAULT 0,
    ai_credits_used BIGINT NOT NULL DEFAULT 0,
    raw_cogs_cap_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    raw_cogs_used_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    frontier_raw_cap_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    frontier_raw_used_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    value_mode_after_raw_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    cheap_only_after_raw_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    hard_stop_after_raw_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    routing_state VARCHAR(32) NOT NULL DEFAULT 'FULL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_managed_ai_usage_period_user_period
    ON managed_ai_usage_period(user_id, period_start, period_end);

CREATE INDEX IF NOT EXISTS ix_managed_ai_usage_period_org
    ON managed_ai_usage_period(org_id, period_start DESC);

CREATE TABLE IF NOT EXISTS managed_ai_usage_event (
    id VARCHAR(64) PRIMARY KEY,
    period_id VARCHAR(64) NOT NULL REFERENCES managed_ai_usage_period(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    org_id VARCHAR(64),
    run_id VARCHAR(96),
    agent_session_id VARCHAR(96),
    source_type VARCHAR(32) NOT NULL,
    provider VARCHAR(32),
    model VARCHAR(128),
    model_class VARCHAR(32),
    agent_role VARCHAR(80),
    step_id VARCHAR(120),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    cache_read_tokens BIGINT NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT NOT NULL DEFAULT 0,
    reasoning_tokens BIGINT NOT NULL DEFAULT 0,
    raw_cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
    retail_multiplier DOUBLE PRECISION NOT NULL DEFAULT 1,
    ai_credits_charged BIGINT NOT NULL DEFAULT 0,
    routing_state_before VARCHAR(32) NOT NULL,
    routing_state_after VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_managed_ai_usage_event_period
    ON managed_ai_usage_event(period_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_managed_ai_usage_event_run
    ON managed_ai_usage_event(run_id);

CREATE TABLE IF NOT EXISTS managed_ai_state_notification (
    id VARCHAR(64) PRIMARY KEY,
    period_id VARCHAR(64) NOT NULL REFERENCES managed_ai_usage_period(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    org_id VARCHAR(64),
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_managed_ai_state_notification_once
    ON managed_ai_state_notification(period_id, to_state, channel);
