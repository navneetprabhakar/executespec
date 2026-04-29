-- V160 — Readymades W5 (R-331): tracking columns on execution_run.
--
-- When a user invokes a Readymade Solution via
-- /api/library/{slug}/instantiate, the resulting ExecutionRun is
-- stamped with these IDs so:
--   - the runs list / project dashboard can surface "this run
--     was instantiated from solution X variant Y"
--   - the V2RunCostTracker can attribute spend per-solution for
--     the calibration loop in W6 (cost band refinement)
--   - the favourite + popularity surfaces (W9) can compute
--     "trending" / "recently used" without grepping unstructured
--     run requirements
--
-- Both nullable; legacy / non-readymade runs leave both NULL and
-- behave exactly as before.
ALTER TABLE execution_run
    ADD COLUMN IF NOT EXISTS readymade_solution_id UUID,
    ADD COLUMN IF NOT EXISTS readymade_variant_id  UUID;

COMMENT ON COLUMN execution_run.readymade_solution_id IS
    'R-331 — when this run was instantiated from a Readymade
     Solution via the library, the solution id; NULL otherwise.';

COMMENT ON COLUMN execution_run.readymade_variant_id IS
    'R-331 — the specific (language, framework, options) variant
     used.  May be NULL when the user explicitly bypassed variant
     resolution (single-variant solutions auto-resolve).';
