-- Wave 3 — SSO/SAML on MAX, GitHub App install, team trial requests.
--
-- Schema only. Controller logic lives in follow-up commits within Wave 3;
-- this migration is safe to deploy ahead of those since every new column is
-- nullable with a safe default.
--
-- Design: docs/plans/persona-roadmap-2026-04-22.md §5.3 (W3.2, W3.3, W3.4).

-- ── W3.2 — SSO/SAML on the org ──────────────────────────────────────────────
-- ssoProvider: null | "GOOGLE_OIDC" | "OKTA_SAML" | "AZURE_AD_SAML"
-- ssoEnforced: when true, email+password login is rejected for this org's
-- members; they must go through the configured SSO flow.
ALTER TABLE org
    ADD COLUMN sso_provider     VARCHAR(32),
    ADD COLUMN sso_enforced     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN sso_metadata_url VARCHAR(1024),
    ADD COLUMN sso_domain       VARCHAR(255);

CREATE INDEX idx_org_sso_provider ON org(sso_provider)
    WHERE sso_provider IS NOT NULL;

-- ── W3.3 — GitHub App installation ──────────────────────────────────────────
-- githubInstallationId: the numeric id returned by the GitHub App install
-- webhook. When set, all repo operations on this org's projects should
-- prefer App tokens (org-wide, auditable) over per-user PATs.
ALTER TABLE org
    ADD COLUMN github_installation_id BIGINT,
    ADD COLUMN github_installed_at    TIMESTAMPTZ;

CREATE INDEX idx_org_github_installation ON org(github_installation_id)
    WHERE github_installation_id IS NOT NULL;

-- ── W3.4 — Team trial request queue ─────────────────────────────────────────
-- Captured from the /trial CTA on the marketing site + /enterprise page.
-- Processed manually today; can grow into a scheduled "auto-grant 5-seat
-- MAX trial + email admin" flow when volume warrants it.
CREATE TABLE team_trial_request (
    id               VARCHAR(64)  PRIMARY KEY,
    work_email       VARCHAR(255) NOT NULL,
    company_name     VARCHAR(255),
    team_size        INT,
    intended_use     TEXT,
    utm_source       VARCHAR(64),
    utm_campaign     VARCHAR(128),
    status           VARCHAR(24)  NOT NULL DEFAULT 'PENDING',  -- PENDING | CONTACTED | CONVERTED | DECLINED
    contacted_at     TIMESTAMPTZ,
    converted_org_id VARCHAR(64)  REFERENCES org(id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_team_trial_status ON team_trial_request(status, created_at DESC);
CREATE INDEX idx_team_trial_email  ON team_trial_request(work_email);
