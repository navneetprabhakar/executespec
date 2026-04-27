-- M-032 + M-033 + M-039 (Wave 3) — three governance levers in one
-- migration since they all add cap-style columns.
--
-- M-033 — per-project monthly spend cap (USD). NULL = no cap.
-- Enforced by TierEnforcementService at plan-approval time:
-- if (current month spend on this project + new run estimate) > cap,
-- reject with a structured 402 + a "your project cap is X, ask the
-- org admin to raise it" hint.
--
-- M-032 — per-org plan-approval policy. require_admin_approval_above
-- sets a USD threshold; runs whose buffered estimate exceeds it can
-- only be approved by ORG_ADMIN/BILLING_ADMIN/SUPER_ADMIN. NULL = no
-- elevated approval required (any submitter can approve their own).
--
-- M-039 — per-org GitHub repo allowlist. Comma-separated list of
-- allowed owners (e.g. "acme-corp,acme-research"). Empty/NULL = open.
-- Enforced by ProjectRepoService at link/create time + by the
-- multi-repo flow on Launch.
--
-- All three columns nullable so existing rows keep working unchanged.
--
-- Reverse migration:
--   ALTER TABLE project DROP COLUMN IF EXISTS spend_cap_usd_monthly;
--   ALTER TABLE org DROP COLUMN IF EXISTS require_admin_approval_above_usd;
--   ALTER TABLE org DROP COLUMN IF EXISTS allowed_github_owners;

ALTER TABLE project
    ADD COLUMN IF NOT EXISTS spend_cap_usd_monthly NUMERIC(10, 2);

ALTER TABLE org
    ADD COLUMN IF NOT EXISTS require_admin_approval_above_usd NUMERIC(10, 2),
    ADD COLUMN IF NOT EXISTS allowed_github_owners VARCHAR(1024);

COMMENT ON COLUMN project.spend_cap_usd_monthly IS
    'M-033: per-project monthly spend cap in USD. NULL = no cap.';
COMMENT ON COLUMN org.require_admin_approval_above_usd IS
    'M-032: per-org USD threshold above which only ORG_ADMIN/BILLING_ADMIN/SUPER_ADMIN can approve a run plan.';
COMMENT ON COLUMN org.allowed_github_owners IS
    'M-039: per-org allowlist of GitHub owners (comma-separated). NULL/empty = open.';
