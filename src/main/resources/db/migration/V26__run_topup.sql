-- ============================================================
-- V26: Run Top-Up Credits
-- Admin-configurable packages for purchasing extra runs, plus
-- run_topup table tracking both purchased and admin-granted
-- bonus runs.
-- ============================================================

-- Admin-configurable top-up packages
CREATE TABLE topup_package (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(100) NOT NULL,
    run_count         INT NOT NULL,
    amount_inr        BIGINT NOT NULL,
    amount_usd        BIGINT NOT NULL DEFAULT 0,
    active            BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order        INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed default packages
INSERT INTO topup_package (name, run_count, amount_inr, amount_usd, sort_order) VALUES
    ('10 Runs', 10, 49900, 599, 1),
    ('25 Runs', 25, 99900, 1199, 2),
    ('50 Runs', 50, 179900, 1999, 3);

-- Run top-up credits (purchased or admin-granted)
CREATE TABLE run_topup (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            VARCHAR(255) REFERENCES app_user(id),
    org_id             VARCHAR(255) REFERENCES org(id),
    quantity           INT NOT NULL,
    remaining          INT NOT NULL,
    source             VARCHAR(20) NOT NULL,
    gateway_order_id   VARCHAR(100),
    gateway_payment_id VARCHAR(100),
    amount_paid_inr    BIGINT DEFAULT 0,
    granted_by         VARCHAR(255) REFERENCES app_user(id),
    grant_reason       TEXT,
    expires_at         TIMESTAMPTZ,
    status             VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_topup_owner CHECK (user_id IS NOT NULL OR org_id IS NOT NULL)
);

-- Partial indexes for fast active lookups
CREATE INDEX idx_run_topup_user_active ON run_topup(user_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_run_topup_org_active  ON run_topup(org_id)  WHERE status = 'ACTIVE';
CREATE INDEX idx_run_topup_order       ON run_topup(gateway_order_id) WHERE gateway_order_id IS NOT NULL;
