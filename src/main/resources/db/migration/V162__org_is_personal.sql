-- V162 — add org.is_personal flag for solo-user bootstrap orgs.
--
-- Background: AuthService.register hardcodes globalRole=USER + tier=FREE,
-- and OrgService.createOrg blocks FREE-tier from creating an org. Project
-- creation (POST /api/projects) requires ROLE_ORG_ADMIN/DEVELOPER, and the
-- sandbox provisioning path (POST /api/onboarding/pick-sample) writes
-- project.org_id from user.org_id which crashes on the schema NOT NULL
-- because solo users had no org. Result: every fresh self-registered user
-- was blocked from creating their first project.
--
-- This column lets the bootstrap path create a per-user "personal" org
-- without polluting the org-list / billing / member-invite flows that
-- only care about real (paid) orgs. Filtered out by callers via
-- WHERE is_personal = false where appropriate.
--
-- Backfill: all pre-existing org rows are paid/created-via-org-flow, so
-- they are NOT personal. Default false + NOT NULL is safe.
-- IF NOT EXISTS guards make this idempotent against partial-apply rollbacks
-- and against operators who applied the column manually before this migration
-- shipped (e.g. the hotfix author's local smoke environment).
ALTER TABLE org
    ADD COLUMN IF NOT EXISTS is_personal BOOLEAN NOT NULL DEFAULT FALSE;

-- Partial index for filtering lists of "real" orgs (admin dashboards, billing
-- screens). Personal orgs are always solo-member so member-count joins
-- don't need the index.
CREATE INDEX IF NOT EXISTS idx_org_is_personal ON org (is_personal) WHERE is_personal = TRUE;
