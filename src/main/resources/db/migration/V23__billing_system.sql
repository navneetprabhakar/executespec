-- ============================================================
-- V23: Billing System
-- Tables: billing_plan, billing_plan_limit, billing_subscription,
--         billing_webhook_event, billing_usage
-- ============================================================

-- BILLING PLANS (seeded, admin-managed)
CREATE TABLE billing_plan (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(100) NOT NULL,
    tier                VARCHAR(20) NOT NULL,
    key_mode            VARCHAR(20) NOT NULL,
    product_access      TEXT[] NOT NULL,
    period              VARCHAR(20) NOT NULL,
    base_amount_inr     BIGINT NOT NULL DEFAULT 0,
    base_amount_usd     BIGINT NOT NULL DEFAULT 0,
    per_seat_inr        BIGINT NOT NULL DEFAULT 0,
    per_seat_usd        BIGINT NOT NULL DEFAULT 0,
    included_seats      INT NOT NULL DEFAULT 1,
    run_limit           INT NOT NULL,
    annual_discount_pct INT NOT NULL DEFAULT 0,
    is_founder_pricing  BOOLEAN NOT NULL DEFAULT FALSE,
    razorpay_plan_id    VARCHAR(50),
    stripe_price_id     VARCHAR(50),
    stripe_product_id   VARCHAR(50),
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_billing_plan_tier ON billing_plan(tier, active);

-- PLAN USAGE LIMITS
CREATE TABLE billing_plan_limit (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    billing_plan_id     UUID NOT NULL REFERENCES billing_plan(id) ON DELETE CASCADE,
    metric              VARCHAR(50) NOT NULL,
    max_value           BIGINT NOT NULL,
    UNIQUE(billing_plan_id, metric)
);

-- SUBSCRIPTIONS
CREATE TABLE billing_subscription (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 VARCHAR(255) NOT NULL REFERENCES app_user(id),
    org_id                  VARCHAR(255) REFERENCES org(id),
    billing_plan_id         UUID NOT NULL REFERENCES billing_plan(id),
    payment_gateway         VARCHAR(20) NOT NULL DEFAULT 'NONE',
    gateway_subscription_id VARCHAR(100),
    gateway_customer_id     VARCHAR(100),
    status                  VARCHAR(30) NOT NULL,
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    total_seats             INT NOT NULL DEFAULT 1,
    extra_seats             INT NOT NULL DEFAULT 0,
    cancel_at_period_end    BOOLEAN NOT NULL DEFAULT FALSE,
    cancelled_at            TIMESTAMPTZ,
    downgraded_at           TIMESTAMPTZ,
    terms_accepted_at       TIMESTAMPTZ,
    terms_version           VARCHAR(20),
    is_founder_pricing      BOOLEAN NOT NULL DEFAULT FALSE,
    founder_batch           INT,
    metadata                JSONB,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_billing_sub_active_user
    ON billing_subscription(user_id)
    WHERE status IN ('FREE', 'ACTIVE', 'PAST_DUE');
CREATE INDEX idx_billing_sub_gateway ON billing_subscription(gateway_subscription_id);
CREATE INDEX idx_billing_sub_org ON billing_subscription(org_id);

-- WEBHOOK EVENTS (audit trail)
CREATE TABLE billing_webhook_event (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    gateway             VARCHAR(20) NOT NULL,
    event_id            VARCHAR(200) NOT NULL,
    event_type          VARCHAR(80) NOT NULL,
    raw_payload         JSONB NOT NULL,
    processed           BOOLEAN NOT NULL DEFAULT FALSE,
    processed_at        TIMESTAMPTZ,
    error_message       TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_webhook_dedup ON billing_webhook_event(gateway, event_id);

-- USAGE TRACKING
CREATE TABLE billing_usage (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             VARCHAR(255) NOT NULL REFERENCES app_user(id),
    org_id              VARCHAR(255) REFERENCES org(id),
    metric              VARCHAR(50) NOT NULL,
    current_value       BIGINT NOT NULL DEFAULT 0,
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    last_updated        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, metric, period_start)
);

CREATE INDEX idx_billing_usage_lookup ON billing_usage(user_id, metric, period_end);

-- ============================================================
-- SEED: FREE PLAN
-- ============================================================
INSERT INTO billing_plan (name, tier, key_mode, product_access, period, base_amount_inr, base_amount_usd, included_seats, run_limit)
VALUES ('Free', 'FREE', 'NONE', '{"CODE_GEN","PIPELINE_GEN","PILOTSRE","IAC_GEN"}', 'NONE', 0, 0, 1, 5);

INSERT INTO billing_plan_limit (billing_plan_id, metric, max_value)
SELECT id, 'RUNS', 5 FROM billing_plan WHERE tier = 'FREE';
INSERT INTO billing_plan_limit (billing_plan_id, metric, max_value)
SELECT id, 'PROJECTS', 2 FROM billing_plan WHERE tier = 'FREE';
INSERT INTO billing_plan_limit (billing_plan_id, metric, max_value)
SELECT id, 'STORAGE_MB', 50 FROM billing_plan WHERE tier = 'FREE';
