-- Validation v3 / Pillar 1 — Planner Requirement Graph.
--
-- Today PlanV2 / PlannedTaskV2 carry no `requirements` or `meetsRequirements`
-- fields: the LLM infers requirements from the spec prose and maps them to
-- tasks implicitly. A dropped requirement survives to the reviewer as an
-- UNRESOLVED issue with no paper trail.
--
-- This migration adds columns to persist:
--   - The planner's enumerated spec requirements (one row on pipeline_run
--     holding the full JSON list of RequirementSpec{id,text,kind}).
--   - Per-task coverage (each agent_task carries the requirement IDs it
--     claims to implement so the reviewer and UI can verify coverage end-
--     to-end).
--
-- Gated behind the feature flag scoring.v2-verified's successor
-- `validation.v3-strict`. When the flag is OFF, columns stay NULL and
-- legacy pipelines remain byte-for-byte unchanged.

ALTER TABLE pipeline_run ADD COLUMN plan_requirements_json TEXT;
ALTER TABLE agent_task   ADD COLUMN meets_requirements_json TEXT;

COMMENT ON COLUMN pipeline_run.plan_requirements_json IS
  'v2.5.11 — JSON array of RequirementSpec{id,text,kind} extracted by the planner. NULL for legacy rows.';
COMMENT ON COLUMN agent_task.meets_requirements_json IS
  'v2.5.11 — JSON array of RequirementSpec.id values this task claims to implement. NULL for legacy rows.';

-- Flag gating the whole v3 strict-validation rollout. Default OFF in prod;
-- admin turns on per canary via POST /api/feature-flags/validation.v3-strict.
-- All v3 columns populate regardless of flag state so toggling on later
-- is instant for new runs.
INSERT INTO feature_flag (flag_key, flag_value, value_type, scope, description)
VALUES ('validation.v3-strict', 'false', 'BOOLEAN', 'PLATFORM',
        'v2.5.11 — Validation v3: evidence-based CLEAN verdict + sync test gate + requirement graph. Default OFF in prod.');
