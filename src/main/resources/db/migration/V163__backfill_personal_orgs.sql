-- V163 — backfill personal orgs for users registered before V162.
--
-- [F-10] (audit Sprint 1 PR-2, 2026-04-30) — the V162 personal-org
-- bootstrap fires inside `AuthService.register()` and the OAuth
-- new-user branch. Pre-V162 users + any user seeded by tests / SQL /
-- direct-DB / migrations / hotfix paths still have `app_user.org_id =
-- NULL`. Live API probes during the v2.5.11 audit confirmed: the four
-- `freshux-*` test accounts (FREE/PRO/TEAM/ENTERPRISE) all return
-- `currentOrgMembers=0` with `maxOrgMembers=-1` because they have no
-- org. Org-gated endpoints (audit, integrations, deploy console list,
-- exec-summary, team management, governance reports) silently 403 for
-- these users — the audit's Q-NEW item that all org-gated paths assume
-- bootstrap fired.
--
-- This migration backfills every ACTIVE + email_verified user whose
-- `org_id IS NULL`:
--   1. Insert a personal `org` row (`is_personal=TRUE`, tier mirrors
--      the user's tier, `created_by_user_id = user.id`).
--   2. Stamp `app_user.org_id` pointing at the new org.
--   3. Insert `org_member` row with `org_role = 'ORG_ADMIN'` so the
--      user passes the project-create role gate.
--
-- Skipped:
--   • `status != 'ACTIVE'` (suspended / deleted) — no point bootstrapping.
--   • `email_verified = FALSE` — these users haven't completed signup
--     anyway; if they verify later, F-29 path now auto-sessions and the
--     register-time bootstrap (V162) fires for new registrations from
--     here forward.
--
-- Re-runnable: the WHERE clause filters out users that already have an
-- org_id, so a re-application is a no-op on a clean DB.
DO $$
DECLARE
    u RECORD;
    new_org_id VARCHAR;
    display_name VARCHAR;
BEGIN
    FOR u IN
        SELECT id, email, first_name, tier
        FROM app_user
        WHERE org_id IS NULL
          AND status = 'ACTIVE'
          AND email_verified = TRUE
    LOOP
        new_org_id := gen_random_uuid()::text;
        display_name := COALESCE(NULLIF(TRIM(u.first_name), ''), split_part(u.email, '@', 1)) || '''s workspace';

        -- 1. Personal org. Mirrors AuthService.OrgService.doBootstrapPersonal:
        --    domain_patterns='' (personal orgs do not enforce domains),
        --    is_personal=TRUE so admin/billing list filters skip it,
        --    tier mirrors the user's current tier (FREE for new signups).
        INSERT INTO org (
            id, name, domain_patterns, tier, status, monthly_run_count,
            is_payment_verified, created_by_user_id, created_at, updated_at,
            api_keys_provided, key_source_mode, domain_enforcement_mode,
            block_common_domains, seat_count, is_personal
        )
        VALUES (
            new_org_id, display_name, '', u.tier, 'ACTIVE', 0,
            FALSE, u.id, NOW(), NOW(),
            FALSE, 'HYBRID', 'OPEN',
            FALSE, 1, TRUE
        );

        -- 2. Stamp the user's org_id so subsequent calls resolve through
        --    orgMemberPermissions instead of falling through to
        --    freeUserPermissions (the [F-03] solo path).
        UPDATE app_user SET org_id = new_org_id, updated_at = NOW() WHERE id = u.id;

        -- 3. Make the user ORG_ADMIN. Project-create + invite-member +
        --    settings-edit all gate on org_role = 'ORG_ADMIN'.
        INSERT INTO org_member (id, org_id, user_id, org_role, joined_at)
        VALUES (gen_random_uuid()::text, new_org_id, u.id, 'ORG_ADMIN', NOW())
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

-- Sanity check (logged via Flyway migration output, not enforced):
-- After this migration, `SELECT count(*) FROM app_user WHERE org_id IS NULL
--                       AND status='ACTIVE' AND email_verified=TRUE` MUST
-- return 0. If it doesn't, something else (a non-RUN-V163 path created
-- new orphan users between the SELECT and the INSERTs above) needs
-- investigation.
