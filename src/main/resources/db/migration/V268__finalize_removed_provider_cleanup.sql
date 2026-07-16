-- Final forward guard for ExecuteSpec 2.0 after the provider was removed from the runtime enum.
-- V256/V263 perform the initial cleanup; this migration is intentionally idempotent so databases
-- promoted from an intermediate release cannot retain executable provider/model state. Immutable
-- run-step, benchmark, and event history is not rewritten.

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
    reviewer_model = CASE WHEN LOWER(COALESCE(reviewer_model, '')) LIKE 'sarvam-%' THEN NULL ELSE reviewer_model END,
    batch_provider = CASE WHEN LOWER(COALESCE(batch_provider, '')) = 'sarvam' THEN NULL ELSE batch_provider END
WHERE status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL')
  AND (UPPER(COALESCE(ai_provider, '')) = 'SARVAM'
    OR LOWER(COALESCE(planner_model, '')) LIKE 'sarvam-%'
    OR LOWER(COALESCE(worker_model, '')) LIKE 'sarvam-%'
    OR LOWER(COALESCE(reviewer_model, '')) LIKE 'sarvam-%'
    OR LOWER(COALESCE(batch_provider, '')) = 'sarvam');

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
   OR LOWER(COALESCE(current_model, '')) LIKE 'sarvam-%'
   OR LOWER(COALESCE(continuation_decision_json, '')) LIKE '%sarvam%'
   OR LOWER(COALESCE(continuation_actions_json, '')) LIKE '%sarvam%';

UPDATE org
SET enabled_providers = (
  SELECT NULLIF(string_agg(trim(provider), ',' ORDER BY ordinal), '')
  FROM unnest(string_to_array(org.enabled_providers, ',')) WITH ORDINALITY AS item(provider, ordinal)
  WHERE LOWER(trim(provider)) <> 'sarvam'
)
WHERE org.enabled_providers IS NOT NULL
  AND LOWER(org.enabled_providers) LIKE '%sarvam%';

-- Model overrides are legacy TEXT and may contain malformed JSON. Invalid rows are cleared rather
-- than left executable; valid rows retain every non-retired provider/model entry.
DO $$
DECLARE
  item RECORD;
  cleaned JSONB;
BEGIN
  FOR item IN
    SELECT id, model_overrides_json
    FROM org
    WHERE model_overrides_json IS NOT NULL
      AND LOWER(model_overrides_json) LIKE '%sarvam%'
  LOOP
    BEGIN
      IF jsonb_typeof(item.model_overrides_json::jsonb) <> 'object' THEN
        UPDATE org SET model_overrides_json = NULL WHERE id = item.id;
      ELSE
        SELECT NULLIF(jsonb_object_agg(entry.key, entry.value), '{}'::jsonb)
          INTO cleaned
        FROM jsonb_each(item.model_overrides_json::jsonb) AS entry(key, value)
        WHERE LOWER(entry.key) NOT LIKE 'sarvam:%'
          AND LOWER(COALESCE(entry.value #>> '{}', '')) NOT LIKE 'sarvam-%';
        UPDATE org SET model_overrides_json = cleaned WHERE id = item.id;
      END IF;
    EXCEPTION WHEN others THEN
      UPDATE org SET model_overrides_json = NULL WHERE id = item.id;
    END;
  END LOOP;
END $$;
