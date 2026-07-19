-- Preserve the human authorization role that applied when each physical provider attempt began.
-- Historical audit exports must not change after an org membership/global role is later edited.

ALTER TABLE llm_usage_attempt_intent
  ADD COLUMN IF NOT EXISTS human_role_snapshot VARCHAR(40);

-- V293 made this table append-only. Temporarily remove the guard for this forward-only backfill,
-- then restore the exact immutable trigger before accepting application traffic.
DROP TRIGGER IF EXISTS trg_llm_usage_attempt_intent_append_only ON llm_usage_attempt_intent;

UPDATE llm_usage_attempt_intent i
SET human_role_snapshot = COALESCE(
  (
    SELECT pm.project_role
    FROM project_member pm
    WHERE pm.project_id = i.project_id AND pm.user_id = i.user_id
  ),
  (
    SELECT om.org_role
    FROM org_member om
    WHERE om.org_id = i.org_id AND om.user_id = i.user_id
  ),
  (
    SELECT a.global_role
    FROM app_user a
    WHERE a.id = i.user_id
  ),
  'UNKNOWN'
)
WHERE i.human_role_snapshot IS NULL;

ALTER TABLE llm_usage_attempt_intent
  ALTER COLUMN human_role_snapshot SET NOT NULL;

CREATE TRIGGER trg_llm_usage_attempt_intent_append_only
BEFORE UPDATE OR DELETE ON llm_usage_attempt_intent
FOR EACH ROW EXECUTE FUNCTION reject_llm_usage_mutation();

COMMENT ON COLUMN llm_usage_attempt_intent.human_role_snapshot IS
  'Immutable project/org/global human role captured before provider dispatch; legacy rows backfilled at V301 upgrade time';
