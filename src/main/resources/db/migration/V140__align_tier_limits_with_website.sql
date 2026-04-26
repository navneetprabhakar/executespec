-- =====================================================================
-- V140: Align tier_config with website/index.html (the published source
--       of truth for tier limits).
--
-- A backend audit on 2026-04-26 found six numeric/permission mismatches
-- between what the website promises and what the backend enforces. This
-- migration fixes five of them (item #6 in the audit, PRO lifecycle
-- HIDDEN, is a deliberate strategy decision and stays as-is).
--
-- Changes (additive — no data loss):
--
--   1. PRO.monthly_run_limit       10  → 20
--      Website: "20 credits per month ($1 = 1 credit)" — assume 1 credit
--      ≈ 1 run for the typical low-band spec.
--
--   2. ENTERPRISE.monthly_run_limit 5000 → -1 (unlimited)
--      Website: "Unlimited (fair use)". -1 is the existing convention
--      (MAX uses it).
--
--   3. NEW columns on tier_config:
--        max_projects_per_org      INT NOT NULL DEFAULT -1
--        max_repos_per_project     INT NOT NULL DEFAULT -1
--      Backed by website limits:
--        FREE        1 / 1
--        PRO         2 / 2
--        TEAM        5 / 5
--        MAX        10 / 10
--        ENTERPRISE -1 / -1 (unlimited)
--      Enforced by TierEnforcementService.assertCanCreateProject /
--      assertCanLinkRepoToProject (called from ProjectService /
--      ProjectRepoService).
--
--   5. MAX.execution_view_enabled  TRUE → FALSE
--      Website: "Execution View: multi-repo, multi-solution launches
--      (Enterprise)". ENTERPRISE keeps it TRUE.
--
-- See backend audit notes in the V140 commit and CLAUDE.md "Recently
-- Resolved (2026-04-26)" for context.
-- =====================================================================

-- 1. Add the two new project / repo limit columns. Defaults to -1
--    (unlimited) so existing rows + any future tier we add without a
--    migration is safe.
ALTER TABLE tier_config
  ADD COLUMN IF NOT EXISTS max_projects_per_org   INT NOT NULL DEFAULT -1,
  ADD COLUMN IF NOT EXISTS max_repos_per_project  INT NOT NULL DEFAULT -1;

-- 2. Update the per-tier values to match the website.
--    Items #1, #2, #3 (limits), and #5 (execution_view_enabled) all in
--    one batch — same row touched, single statement per tier.

UPDATE tier_config
   SET max_projects_per_org  = 1,
       max_repos_per_project = 1
 WHERE tier = 'FREE';

UPDATE tier_config
   SET monthly_run_limit     = 20,
       max_projects_per_org  = 2,
       max_repos_per_project = 2
 WHERE tier = 'PRO';

UPDATE tier_config
   SET max_projects_per_org  = 5,
       max_repos_per_project = 5
 WHERE tier = 'TEAM';

UPDATE tier_config
   SET max_projects_per_org   = 10,
       max_repos_per_project  = 10,
       execution_view_enabled = FALSE
 WHERE tier = 'MAX';

UPDATE tier_config
   SET monthly_run_limit     = -1,
       max_projects_per_org  = -1,
       max_repos_per_project = -1
 WHERE tier = 'ENTERPRISE';
