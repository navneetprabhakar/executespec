-- V198 (2026-05-16) — per-org "external sharing" feature flag.
--
-- Adds a boolean to the `org` table that gates cross-org project invites
-- (the V175 ProjectInvite surface). When false (default), only same-org
-- members can be added to a project; the "External invite" UI on the
-- Project Members page is hidden. When true, SUPER_ADMIN or ORG_ADMIN of
-- the inviting org can invite any email (gmail, outlook, etc.) as an
-- external PROJECT_VIEWER / PROJECT_DEVELOPER on a single project.
--
-- Why per-org, not a global feature flag: each org needs independent
-- control (compliance / DPA reasons). Some orgs explicitly want to
-- block external sharing while keeping internal collaboration on.
--
-- Default false so existing tenants are opt-in. SUPER_ADMIN may flip
-- this per-org via the existing Org Settings surface; the org's own
-- ORG_ADMIN may flip it via the same surface (auth.security.OrgGuard).
ALTER TABLE org
  ADD COLUMN IF NOT EXISTS external_sharing_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN org.external_sharing_enabled IS
  'V198 — when true, SUPER_ADMIN/ORG_ADMIN of this org may invite users from any email domain as cross-org project members. When false (default), invites are restricted to members of this org.';
