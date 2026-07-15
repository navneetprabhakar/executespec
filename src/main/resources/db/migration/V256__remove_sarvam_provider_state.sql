-- ExecuteSpec 2.0 removes Sarvam from the product provider/model matrix.
-- Normalize every persisted value that can be reused for a future dispatch. Historical usage and
-- benchmark evidence tables intentionally retain their provider strings as immutable audit data.

UPDATE app_user
SET preferred_ai_provider = NULL
WHERE UPPER(COALESCE(preferred_ai_provider, '')) = 'SARVAM';

UPDATE project
SET ai_provider = 'ANTHROPIC'
WHERE UPPER(COALESCE(ai_provider, '')) = 'SARVAM';

UPDATE pipeline_run
SET ai_provider = 'ANTHROPIC',
    planner_model = CASE WHEN LOWER(COALESCE(planner_model, '')) LIKE 'sarvam-%' THEN NULL ELSE planner_model END,
    worker_model = CASE WHEN LOWER(COALESCE(worker_model, '')) LIKE 'sarvam-%' THEN NULL ELSE worker_model END,
    reviewer_model = CASE WHEN LOWER(COALESCE(reviewer_model, '')) LIKE 'sarvam-%' THEN NULL ELSE reviewer_model END
WHERE UPPER(COALESCE(ai_provider, '')) = 'SARVAM'
  AND status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL');

-- Resumable rows must never retain a Sarvam model pin after the provider is removed. Terminal
-- rows remain untouched so historical run evidence stays immutable.
UPDATE pipeline_run
SET planner_model = CASE WHEN LOWER(COALESCE(planner_model, '')) LIKE 'sarvam-%' THEN NULL ELSE planner_model END,
    worker_model = CASE WHEN LOWER(COALESCE(worker_model, '')) LIKE 'sarvam-%' THEN NULL ELSE worker_model END,
    reviewer_model = CASE WHEN LOWER(COALESCE(reviewer_model, '')) LIKE 'sarvam-%' THEN NULL ELSE reviewer_model END
WHERE status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL')
  AND (LOWER(COALESCE(planner_model, '')) LIKE 'sarvam-%'
    OR LOWER(COALESCE(worker_model, '')) LIKE 'sarvam-%'
    OR LOWER(COALESCE(reviewer_model, '')) LIKE 'sarvam-%');

UPDATE pipeline_run
SET batch_provider = NULL
WHERE UPPER(COALESCE(batch_provider, '')) = 'SARVAM';

UPDATE execution_run
SET ai_provider = NULL
WHERE UPPER(COALESCE(ai_provider, '')) = 'SARVAM';

UPDATE agent_session
SET default_provider = NULL,
    default_model = NULL
WHERE UPPER(COALESCE(default_provider, '')) = 'SARVAM'
   OR LOWER(COALESCE(default_model, '')) LIKE 'sarvam-%';

UPDATE agent_task
SET current_provider = NULL,
    current_model = NULL,
    continuation_decision_json = NULL,
    continuation_actions_json = NULL
WHERE UPPER(COALESCE(current_provider, '')) = 'SARVAM'
   OR LOWER(COALESCE(current_model, '')) LIKE 'sarvam-%';

-- A blocked continuation is executable state, not immutable history. Invalidate proposals that
-- mention the removed provider/model so approval/retry cannot fail after the enum is gone.
UPDATE agent_task
SET continuation_decision_json = NULL,
    continuation_actions_json = NULL
WHERE LOWER(COALESCE(continuation_decision_json, '')) LIKE '%sarvam%'
   OR LOWER(COALESCE(continuation_actions_json, '')) LIKE '%sarvam%';

UPDATE org o
SET enabled_providers = (
  SELECT NULLIF(string_agg(trim(provider), ',' ORDER BY ordinal), '')
  FROM unnest(string_to_array(o.enabled_providers, ',')) WITH ORDINALITY AS item(provider, ordinal)
  WHERE LOWER(trim(provider)) <> 'sarvam'
)
WHERE o.enabled_providers IS NOT NULL
  AND LOWER(o.enabled_providers) LIKE '%sarvam%';

-- TEXT is legacy and may contain malformed operator-entered data. Parse each row independently:
-- valid JSON is cleaned; malformed JSON is retained (with a notice) so one bad row cannot block
-- the migration/deploy. OrgModelOverrideService already treats malformed overrides as absent.
DO $$
DECLARE
  item RECORD;
  cleaned TEXT;
BEGIN
  FOR item IN
    SELECT id, model_overrides_json
    FROM org
    WHERE model_overrides_json IS NOT NULL
      AND LOWER(model_overrides_json) LIKE '%sarvam%'
  LOOP
    BEGIN
      SELECT NULLIF(jsonb_object_agg(entry.key, entry.value)::text, '{}')
        INTO cleaned
      FROM jsonb_each(item.model_overrides_json::jsonb) AS entry(key, value)
      WHERE LOWER(entry.key) NOT LIKE 'sarvam:%'
        AND LOWER(entry.value #>> '{}') NOT LIKE 'sarvam-%';
      UPDATE org SET model_overrides_json = cleaned WHERE id = item.id;
    EXCEPTION WHEN others THEN
      RAISE NOTICE 'V256 retained malformed org.model_overrides_json for org %', item.id;
    END;
  END LOOP;
END $$;
