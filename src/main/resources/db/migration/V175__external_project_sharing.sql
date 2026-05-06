-- =====================================================================
-- V175 — external project sharing.
--
-- Adds two complementary surfaces for sharing project visibility with
-- people outside the project's organisation:
--
-- 1. **Cross-org project members** — external collaborators get a
--    {@code ProjectMember} row with {@code is_external = true}; the
--    {@link ProjectAccessGuard} now consults this table when org-id
--    doesn't match. The role can be PROJECT_ADMIN, PROJECT_DEVELOPER,
--    or the new PROJECT_VIEWER (read-only on every project surface).
--
-- 2. **Project invites** — pending invitations addressed by email so
--    the recipient can accept before they have an account on the
--    platform. {@code project_invite} carries a unique token, role,
--    expires_at, and accepted_at. On accept the row is consumed and
--    a {@code ProjectMember} is created.
--
-- 3. **Public share links** — signed read-only tokens that grant
--    access to a specific project surface (overview / run / code
--    review / pr review / validation report) without an account.
--    Tier-gated; FREE limited to 3 active links, paid tiers
--    unlimited.
--
-- All 3 changes are idempotent + additive.
-- =====================================================================

-- ── 1. project_member.is_external ─────────────────────────────────────
-- Mark cross-org members so the UI can badge them differently and so
-- the org-billing rollup can skip them when computing seat usage.
ALTER TABLE project_member
  ADD COLUMN IF NOT EXISTS is_external BOOLEAN NOT NULL DEFAULT FALSE;

-- v2.6.4 — add PROJECT_VIEWER role (read-only). Existing rows untouched;
-- the application allows the new role via a string check, no DB-level
-- check constraint to keep this additive without rewriting old DBs.
COMMENT ON COLUMN project_member.project_role IS
  'PROJECT_ADMIN | PROJECT_DEVELOPER | PROJECT_VIEWER';

-- ── 2. project_invite ─────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS project_invite (
  id                  UUID PRIMARY KEY,
  project_id          VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  email               VARCHAR(255) NOT NULL,
  project_role        VARCHAR(32)  NOT NULL DEFAULT 'PROJECT_VIEWER',
  -- PROJECT_ADMIN | PROJECT_DEVELOPER | PROJECT_VIEWER
  token               VARCHAR(64)  NOT NULL UNIQUE,
  invited_by_user_id  VARCHAR(255) NOT NULL REFERENCES app_user(id),
  invited_org_id      VARCHAR(255), -- captured at invite time so accept can validate even if the org rotates
  status              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  -- PENDING | ACCEPTED | REVOKED | EXPIRED
  expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
  accepted_at         TIMESTAMP WITH TIME ZONE,
  accepted_by_user_id VARCHAR(255) REFERENCES app_user(id),
  revoked_at          TIMESTAMP WITH TIME ZONE,
  revoked_by_user_id  VARCHAR(255) REFERENCES app_user(id),
  created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_project_invite_status CHECK (
    status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')
  )
);

CREATE INDEX IF NOT EXISTS idx_project_invite_project ON project_invite(project_id);
CREATE INDEX IF NOT EXISTS idx_project_invite_token   ON project_invite(token);
CREATE INDEX IF NOT EXISTS idx_project_invite_email_status
  ON project_invite(LOWER(email), status);

-- Partial index — only PENDING is interesting for "list outstanding invites".
CREATE INDEX IF NOT EXISTS idx_project_invite_pending
  ON project_invite(project_id, expires_at)
  WHERE status = 'PENDING';

-- ── 3. project_share_link ─────────────────────────────────────────────
-- Public, signed read-only links to a specific project surface. The
-- token is a high-entropy random string passed in the URL. Once revoked
-- or expired the GET returns 410 Gone.
CREATE TABLE IF NOT EXISTS project_share_link (
  id                 UUID PRIMARY KEY,
  project_id         VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
  token              VARCHAR(64)  NOT NULL UNIQUE,
  surface            VARCHAR(32)  NOT NULL,
  -- PROJECT_OVERVIEW | RUN_DETAIL | CODE_REVIEW | PR_REVIEW | VALIDATION_REPORT | ARCHITECTURE_DOC
  scope_id           VARCHAR(255),
  -- For surfaces that need a sub-id (run id, scan id, review id, etc).
  -- Null for surfaces that scope to the project as a whole.
  label              VARCHAR(255),
  -- Optional human-readable label so the dashboard can render
  -- "Read-only validation report — Q4 demo for Acme".
  created_by_user_id VARCHAR(255) NOT NULL REFERENCES app_user(id),
  expires_at         TIMESTAMP WITH TIME ZONE,
  -- null = no expiry; admin-revocation only.
  revoked_at         TIMESTAMP WITH TIME ZONE,
  revoked_by_user_id VARCHAR(255) REFERENCES app_user(id),
  views              BIGINT       NOT NULL DEFAULT 0,
  last_viewed_at     TIMESTAMP WITH TIME ZONE,
  created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_project_share_link_surface CHECK (
    surface IN (
      'PROJECT_OVERVIEW',
      'RUN_DETAIL',
      'CODE_REVIEW',
      'PR_REVIEW',
      'VALIDATION_REPORT',
      'ARCHITECTURE_DOC'
    )
  )
);

CREATE INDEX IF NOT EXISTS idx_project_share_link_project
  ON project_share_link(project_id);
CREATE INDEX IF NOT EXISTS idx_project_share_link_token
  ON project_share_link(token);

-- Partial index — only active (non-revoked, non-expired) links matter for
-- the "how many do I have left this month" tier-quota lookup.
CREATE INDEX IF NOT EXISTS idx_project_share_link_active
  ON project_share_link(project_id, created_at)
  WHERE revoked_at IS NULL;

-- ── 4. tier_config: per-tier active share-link cap ───────────────────
-- FREE 3 active links, PRO 25, TEAM/MAX/ENTERPRISE unlimited (-1). The
-- column is additive + the existing assertTierAndQuota chain in
-- ProjectShareLinkService consults it the same way other tier gates
-- do.
ALTER TABLE tier_config
  ADD COLUMN IF NOT EXISTS share_link_active_cap INT NOT NULL DEFAULT 0;

UPDATE tier_config SET share_link_active_cap = 3   WHERE tier = 'FREE';
UPDATE tier_config SET share_link_active_cap = 25  WHERE tier = 'PRO';
UPDATE tier_config SET share_link_active_cap = -1  WHERE tier IN ('TEAM', 'MAX', 'ENTERPRISE');
