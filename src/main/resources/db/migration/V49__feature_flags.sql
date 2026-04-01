-- Feature flags table for runtime configuration
CREATE TABLE feature_flag (
    id              BIGSERIAL       PRIMARY KEY,
    flag_key        VARCHAR(128)    NOT NULL UNIQUE,
    flag_value      VARCHAR(512)    NOT NULL,
    value_type      VARCHAR(20)     NOT NULL DEFAULT 'BOOLEAN',
    scope           VARCHAR(20)     NOT NULL DEFAULT 'PLATFORM',
    description     VARCHAR(512),
    updated_by      VARCHAR(128),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_feature_flag_key ON feature_flag (flag_key);

-- Seed data
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description) VALUES
    ('integrations.jira.enabled',       'false',    'BOOLEAN',    'PLATFORM', 'Enable JIRA integration for context extraction'),
    ('integrations.slack.enabled',      'false',    'BOOLEAN',    'PLATFORM', 'Enable Slack notification integration'),
    ('integrations.confluence.enabled', 'false',    'BOOLEAN',    'PLATFORM', 'Enable Confluence context extraction'),
    ('preview.mode',                    'docker',   'STRING',     'PLATFORM', 'Preview mode: docker or cloud-run'),
    ('validator.per-task-level',        'TYPE',     'STRING',     'PLATFORM', 'Validation level for per-task checks (TYPE, LINT, FULL)'),
    ('recovery.enabled',               'true',     'BOOLEAN',    'PLATFORM', 'Enable automatic run recovery on transient failures'),
    ('billing.stripe.enabled',         'false',    'BOOLEAN',    'PLATFORM', 'Enable Stripe payment integration'),
    ('billing.razorpay.enabled',       'true',     'BOOLEAN',    'PLATFORM', 'Enable Razorpay payment integration'),
    ('pii.sanitizer.enabled',          'false',    'BOOLEAN',    'PLATFORM', 'Enable PII sanitization in LLM prompts'),
    ('sandbox.network-isolation',      'DISABLED', 'STRING',     'PLATFORM', 'Network isolation mode for sandbox: DISABLED, PARTIAL, FULL'),
    ('submissions.paused',             'false',    'BOOLEAN',    'PLATFORM', 'Pause all new run submissions (maintenance mode)');
