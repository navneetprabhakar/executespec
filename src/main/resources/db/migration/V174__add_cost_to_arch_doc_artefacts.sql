-- =====================================================================
-- V174 — add cost_usd to architecture / generated-doc artefacts.
--
-- These three tables already track input_tokens + output_tokens but not
-- the resulting USD cost. Without cost_usd, the project dashboard
-- couldn't roll up architecture / doc generation spend alongside run
-- spend (pipeline_run.actual_cost_usd) and review spend (V172 added
-- code_review_scan.cost_usd + pr_review.cost_usd, both folded into
-- the dashboard in this same change set).
--
-- Default 0.0 so historical rows render as "no recorded cost" rather
-- than null-noise; new rows populate cost at generation time using the
-- same pricing matrix the run cost tracker uses.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS so this is safe to re-apply on
-- a partially-migrated dev DB.
-- =====================================================================

ALTER TABLE repo_architecture_doc
  ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE project_architecture_doc
  ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0.0;

ALTER TABLE project_generated_doc
  ADD COLUMN IF NOT EXISTS cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0.0;
