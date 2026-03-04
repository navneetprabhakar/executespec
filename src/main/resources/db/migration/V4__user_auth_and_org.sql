-- V4__user_auth_and_org.sql
-- User authentication, org management, projects, and payment audit tables.

-- ── app_user ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_user (
    id                   VARCHAR(255)  PRIMARY KEY,
    email                VARCHAR(255)  NOT NULL UNIQUE,
    first_name           VARCHAR(100)  NOT NULL,
    last_name            VARCHAR(100)  NOT NULL,
    password_hash        VARCHAR(255)  NOT NULL,
    global_role          VARCHAR(32)   NOT NULL DEFAULT 'USER',
    -- SUPER_ADMIN | USER
    tier                 VARCHAR(32)   NOT NULL DEFAULT 'FREE',
    -- FREE | PRO | ENTERPRISE
    status               VARCHAR(32)   NOT NULL DEFAULT 'PENDING_VERIFICATION',
    -- PENDING_VERIFICATION | ACTIVE | SUSPENDED | DELETED
    must_change_password BOOLEAN       NOT NULL DEFAULT FALSE,
    email_verified       BOOLEAN       NOT NULL DEFAULT FALSE,
    org_id               VARCHAR(255),
    -- FK to org.id (nullable — free users have no org)
    personal_run_count   INTEGER       NOT NULL DEFAULT 0,
    run_count_reset_at   TIMESTAMP WITH TIME ZONE,
    last_login_at        TIMESTAMP WITH TIME ZONE,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_user_email  ON app_user (email);
CREATE INDEX IF NOT EXISTS idx_user_org_id ON app_user (org_id);
CREATE INDEX IF NOT EXISTS idx_user_status ON app_user (status);

-- ── org ───────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS org (
    id                   VARCHAR(255)  PRIMARY KEY,
    name                 VARCHAR(255)  NOT NULL,
    domain_patterns      TEXT          NOT NULL,
    -- comma-separated, e.g. "acme.com,acmecorp.com"
    tier                 VARCHAR(32)   NOT NULL DEFAULT 'PRO',
    -- PRO | ENTERPRISE
    status               VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | SUSPENDED | DELETED
    monthly_run_count    INTEGER       NOT NULL DEFAULT 0,
    run_count_reset_at   TIMESTAMP WITH TIME ZONE,
    is_payment_verified  BOOLEAN       NOT NULL DEFAULT FALSE,
    payment_ref_id       VARCHAR(255),
    plan_expires_at      TIMESTAMP WITH TIME ZONE,
    created_by_user_id   VARCHAR(255)  NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted_at           TIMESTAMP WITH TIME ZONE
);

-- ── org_member ────────────────────────────────────────────────────────────────
-- Explicit join table: a user's role WITHIN a specific org
CREATE TABLE IF NOT EXISTS org_member (
    id          VARCHAR(255)  PRIMARY KEY,
    org_id      VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    user_id     VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    org_role    VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPER',
    -- ORG_ADMIN | DEVELOPER | BILLING_ADMIN
    joined_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_org_member_user ON org_member (user_id);
CREATE INDEX IF NOT EXISTS idx_org_member_org  ON org_member (org_id);

-- ── org_invitation ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS org_invitation (
    id              VARCHAR(255)  PRIMARY KEY,
    org_id          VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    invited_email   VARCHAR(255)  NOT NULL,
    invited_by      VARCHAR(255)  NOT NULL,
    -- user_id of org admin who invited
    org_role        VARCHAR(32)   NOT NULL DEFAULT 'DEVELOPER',
    token           VARCHAR(255)  NOT NULL UNIQUE,
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    -- PENDING | ACCEPTED | REJECTED | EXPIRED | ESCALATED
    escalation_note TEXT,
    project_ids     TEXT,
    -- comma-separated project IDs to auto-assign on accept
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_invitation_token ON org_invitation (token);
CREATE INDEX IF NOT EXISTS idx_invitation_org   ON org_invitation (org_id);
CREATE INDEX IF NOT EXISTS idx_invitation_email ON org_invitation (invited_email);

-- ── project ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS project (
    id              VARCHAR(255)  PRIMARY KEY,
    org_id          VARCHAR(255)  NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    name            VARCHAR(255)  NOT NULL,
    description     TEXT,
    status          VARCHAR(32)   NOT NULL DEFAULT 'ACTIVE',
    -- ACTIVE | ARCHIVED
    created_by      VARCHAR(255)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_project_org ON project (org_id);

-- ── project_member ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS project_member (
    id              VARCHAR(255)  PRIMARY KEY,
    project_id      VARCHAR(255)  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    user_id         VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    project_role    VARCHAR(32)   NOT NULL DEFAULT 'PROJECT_DEVELOPER',
    -- PROJECT_ADMIN | PROJECT_DEVELOPER
    assigned_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_member_user    ON project_member (user_id);
CREATE INDEX IF NOT EXISTS idx_project_member_project ON project_member (project_id);

-- ── email_token ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS email_token (
    id          VARCHAR(255)  PRIMARY KEY,
    user_id     VARCHAR(255)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token       VARCHAR(255)  NOT NULL UNIQUE,
    type        VARCHAR(32)   NOT NULL,
    -- EMAIL_VERIFICATION | PASSWORD_RESET
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at     TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_email_token_token   ON email_token (token);
CREATE INDEX IF NOT EXISTS idx_email_token_user_id ON email_token (user_id);

-- ── refresh_token_blocklist ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS refresh_token_blocklist (
    jti         VARCHAR(255)  PRIMARY KEY,
    user_id     VARCHAR(255)  NOT NULL,
    blocked_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_blocklist_expires ON refresh_token_blocklist (expires_at);

-- ── payment_audit ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_audit (
    id              VARCHAR(255)  PRIMARY KEY,
    entity_type     VARCHAR(32)   NOT NULL,
    -- 'USER' | 'ORG'
    entity_id       VARCHAR(255)  NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    -- PLAN_ASSIGNED | PLAN_UPGRADED | PLAN_DOWNGRADED | PAYMENT_RECEIVED
    -- | PAYMENT_FAILED | SUBSCRIPTION_CANCELLED | MANUAL_OVERRIDE
    old_tier        VARCHAR(32),
    new_tier        VARCHAR(32),
    payment_ref_id  VARCHAR(255),
    performed_by    VARCHAR(255),
    notes           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payment_audit_entity ON payment_audit (entity_type, entity_id);

-- ── Amendments to pipeline_run ────────────────────────────────────────────────
ALTER TABLE pipeline_run
    ADD COLUMN IF NOT EXISTS created_by_user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS org_id             VARCHAR(255),
    ADD COLUMN IF NOT EXISTS project_id         VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_run_created_by ON pipeline_run (created_by_user_id);
CREATE INDEX IF NOT EXISTS idx_run_org_id     ON pipeline_run (org_id);
CREATE INDEX IF NOT EXISTS idx_run_project_id ON pipeline_run (project_id);
