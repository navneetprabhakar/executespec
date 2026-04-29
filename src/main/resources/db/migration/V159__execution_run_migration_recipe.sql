-- V159 — Migrations W4 (R-339): tracking columns on execution_run.
--
-- When a user invokes a Migration recipe via /api/migrations/{slug}/apply,
-- the resulting ExecutionRun is stamped with these IDs so:
--   - the runs list / project dashboard can surface "this run was a
--     migration of X recipe v.Y"
--   - the V2RunCostTracker can attribute spend per-recipe for the
--     calibration loop in W7+ (sample-diff cache, est_credits
--     refinement)
--   - the post-merge cascade (W9) can identify "this PR shipped
--     recipe X" and decide whether to suggest follow-ups
--
-- Both nullable; legacy / non-migration runs leave both NULL and
-- behave exactly as before.
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS migration_recipe_id  UUID,
    ADD COLUMN IF NOT EXISTS migration_variant_id UUID;

COMMENT ON COLUMN execution_run.migration_recipe_id IS
    'R-339 — when this run was instantiated from a Migration recipe,
     the recipe id; NULL for non-migration runs.';

COMMENT ON COLUMN execution_run.migration_variant_id IS
    'R-339 — the specific variant (options cell) of the recipe used.';
