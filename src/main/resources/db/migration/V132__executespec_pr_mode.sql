-- E1.4 — Enterprise readiness, Phase E1 baseline
--
-- Adds a per-repo opt-out flag for the "PR for every .executespec/ change"
-- default. PR mode (default) is the right behaviour for the picky enterprise
-- persona: every change is reviewable + auditable. DIRECT_PUSH exists so
-- trusted, high-frequency writes (codemap regen post-merge) can bypass PR
-- overhead for customers who explicitly opt in — see plan §7
-- ("Add to v1") for the bot-PR-fatigue rationale.
--
-- Default value is intentionally NOT set in SQL: the entity defaults to PR
-- when the column is null, and a future admin endpoint will let
-- PROJECT_ADMIN flip individual repos to DIRECT_PUSH.
--
-- Reverse migration:
--   ALTER TABLE project_repo DROP COLUMN IF EXISTS executespec_pr_mode;

ALTER TABLE project_repo
  ADD COLUMN IF NOT EXISTS executespec_pr_mode VARCHAR(16);
    -- NULL | PR | DIRECT_PUSH
