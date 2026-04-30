-- V164 — fix TEAM tier deploy inversion.
--
-- [J-09] (audit Sprint 3, 2026-05-01) — TEAM had `can_deploy=false /
-- max_deployments=0` while the lower-priced PRO had `can_deploy=true /
-- max_deployments=5`. Inverted ladder, almost certainly inherited from
-- V141's MAX→TEAM rename + V140's MAX-was-no-deploy default, then
-- never reconciled when V144 reinstated per-seat TEAM at $49.
--
-- Live tier_config probe (2026-05-01) confirmed:
--    FREE:       can_deploy=false  max_deployments=0
--    PRO:        can_deploy=true   max_deployments=5
--    TEAM:       can_deploy=false  max_deployments=0   ← INVERTED
--    MAX:        can_deploy=true   max_deployments=-1
--    ENTERPRISE: can_deploy=true   max_deployments=-1
--
-- TEAM at $49/seat with unlimited apply-suggestions + unlimited
-- preview-starts (the genuine qualitative jump from PRO) should match
-- that pattern on deploy too. Setting:
--    TEAM:       can_deploy=true   max_deployments=-1 (unlimited)
--
-- This matches MAX/ENTERPRISE shape, distinguishes TEAM from PRO's
-- capped 5 deploys, and fixes the bug where the [F-31] deploy tier
-- gate (Sprint 2) silently 403'd TEAM admins despite their tier
-- promising deploy.
--
-- Idempotent: UPDATE only changes rows that match WHERE; safe to
-- re-apply.

UPDATE tier_config
SET can_deploy = TRUE,
    max_deployments = -1
WHERE tier = 'TEAM'
  AND can_deploy = FALSE; -- only flip when actually inverted (don't clobber a manual fix)

-- Sanity verification (logged via Flyway output, not enforced here):
-- After this migration, `SELECT can_deploy, max_deployments FROM
-- tier_config WHERE tier='TEAM'` MUST return `t, -1`.
