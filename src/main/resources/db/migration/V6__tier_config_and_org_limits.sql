-- ── V6: Database-driven tier configuration & per-org run-limit overrides ──────

-- Global tier configuration — editable by super admin at runtime
CREATE TABLE tier_config (
    tier                VARCHAR(50)  PRIMARY KEY,
    monthly_run_limit   INT          NOT NULL,
    is_fixed            BOOLEAN      NOT NULL DEFAULT FALSE,
    description         VARCHAR(255)
);

-- Seed default values
INSERT INTO tier_config (tier, monthly_run_limit, is_fixed, description) VALUES
    ('FREE',       5,    TRUE,  'Fixed limit for free self-serve users'),
    ('PRO',        50,   FALSE, 'Default for team/org tier; overrideable per org'),
    ('ENTERPRISE', 5000, FALSE, 'Fallback limit when org API keys are not provided');

-- Per-org overrides: null = use tier default from tier_config
ALTER TABLE org
    ADD COLUMN monthly_run_limit_override INT     DEFAULT NULL,
    ADD COLUMN api_keys_provided          BOOLEAN NOT NULL DEFAULT FALSE;
