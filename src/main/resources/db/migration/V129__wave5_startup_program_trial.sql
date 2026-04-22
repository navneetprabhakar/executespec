-- Wave 5 — startup program + 14-day MAX trial + 2-teammate free org.
--
-- Schema only. Services + frontend commits follow inside the Wave 5 window.
-- Full design: docs/plans/wave-5-startup-and-polish-plan.md.

-- ── W5.1 — Startup program ──────────────────────────────────────────────────
-- Verified startups < 20 employees get 50% off MAX for 12 months. Verification
-- flow captures company size + requests Crunchbase/LinkedIn URL for manual
-- review. Auto-approval for domains in a curated allowlist (future).

CREATE TABLE startup_program_application (
    id                 VARCHAR(64)  PRIMARY KEY,
    user_id            VARCHAR(64)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    company_name       VARCHAR(255) NOT NULL,
    company_size       INT,
    website_url        VARCHAR(512),
    linkedin_url       VARCHAR(512),
    status             VARCHAR(24)  NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|DECLINED
    decided_by         VARCHAR(64),
    decided_at         TIMESTAMPTZ,
    decline_reason     TEXT,
    discount_expires_at TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_spa_status_created ON startup_program_application(status, created_at DESC);
CREATE UNIQUE INDEX ux_spa_one_active_per_user
    ON startup_program_application(user_id) WHERE status IN ('PENDING', 'APPROVED');

-- ── W5.9 — 14-day MAX trial on signup (app_user column) ────────────────────
-- trial_tier: null when not on trial; "MAX" during the 14-day trial window.
-- When trial_ends_at passes, PeriodEndDowngradeScheduler flips the user back
-- to their paid tier (or FREE).

ALTER TABLE app_user
    ADD COLUMN trial_tier          VARCHAR(16),
    ADD COLUMN trial_started_at    TIMESTAMPTZ,
    ADD COLUMN trial_ends_at       TIMESTAMPTZ;

CREATE INDEX idx_app_user_trial_ends
    ON app_user(trial_ends_at) WHERE trial_ends_at IS NOT NULL;

-- ── W5.10 — 2-teammate invites on FREE orgs ────────────────────────────────
-- tier_config.max_org_members governs invites today. FREE has no org path;
-- we add a boolean flag to app_user for "this user can invite up to 2
-- teammates into a free-tier org" — the actual org creation happens through
-- a new /api/org/free-team endpoint that auto-creates an org with tier=FREE.

ALTER TABLE app_user
    ADD COLUMN can_invite_teammates BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing FREE users opt in via a CTA — column defaults false so tokens,
-- quota, and invite flow stay identical for users who don't opt in.
