-- Canonical, tenant-attributed ledger for every physical provider attempt.
--
-- Existing run_step_usage rows represent successful calls only. Preserve them as SUCCESS while
-- adding the durable execution-context link, tenant dimensions, actual key source, failure
-- visibility, latency, and attempt sequence needed for Enterprise audit/FinOps. Provider payloads,
-- prompts, request bodies, and credentials are deliberately not stored: provider_usage_raw_json is
-- restricted by application code to a small allow-list of numeric usage/cost fields.

ALTER TABLE run_step_usage
  ADD COLUMN IF NOT EXISTS llm_execution_context_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS org_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS user_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS project_id VARCHAR(64),
  ADD COLUMN IF NOT EXISTS repo_id UUID,
  ADD COLUMN IF NOT EXISTS feature VARCHAR(80),
  ADD COLUMN IF NOT EXISTS source_type VARCHAR(32),
  ADD COLUMN IF NOT EXISTS source_id VARCHAR(160),
  ADD COLUMN IF NOT EXISTS key_source VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
  ADD COLUMN IF NOT EXISTS attempt_status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
  ADD COLUMN IF NOT EXISTS error_redacted TEXT,
  ADD COLUMN IF NOT EXISTS attempt_number INTEGER NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS latency_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE run_step_usage
  ALTER COLUMN call_index TYPE BIGINT USING call_index::BIGINT;

-- V243 never mapped this column in the entity, so no legitimate application path populated it.
-- Treat any historical value as untrusted and retain only numeric usage/cost keys from the new
-- explicit allow-list. This also removes any manually inserted prompt/response/request content.
UPDATE run_step_usage u
SET provider_usage_raw_json = CASE
  WHEN jsonb_typeof(u.provider_usage_raw_json) = 'object' THEN (
    SELECT jsonb_object_agg(entry.key, entry.value)
    FROM jsonb_each(u.provider_usage_raw_json) entry
    WHERE entry.key IN (
      'cache_read_input_tokens',
      'cache_creation_input_tokens',
      'thinking_tokens',
      'reasoning_tokens',
      'provider_reported_cost_usd',
      'provider_upstream_cost_usd',
      'accounted_cost_usd'
    )
    AND jsonb_typeof(entry.value) = 'number'
  )
  ELSE NULL
END
WHERE u.provider_usage_raw_json IS NOT NULL;

-- Safe historical backfill: pipeline_run.run_id is a primary key. No source/correlation fuzzy
-- matching is allowed because those values are not tenant-global identities.
UPDATE run_step_usage u
SET org_id = COALESCE(u.org_id, r.org_id),
    user_id = COALESCE(u.user_id, r.created_by_user_id),
    project_id = COALESCE(u.project_id, r.project_id),
    feature = COALESCE(u.feature, u.agent_role, 'LEGACY_LLM_CALL'),
    source_type = COALESCE(u.source_type, 'PIPELINE_RUN'),
    source_id = COALESCE(u.source_id, u.run_id)
FROM pipeline_run r
WHERE r.run_id = u.run_id
  AND (u.org_id IS NULL OR u.user_id IS NULL OR u.source_type IS NULL);

UPDATE run_step_usage
SET feature = COALESCE(feature, agent_role, 'LEGACY_LLM_CALL'),
    source_type = COALESCE(source_type, 'LEGACY_RUN_SCOPE'),
    source_id = COALESCE(source_id, run_id),
    agent_role = COALESCE(agent_role, 'UNKNOWN'),
    provider = COALESCE(provider, 'unknown'),
    model = COALESCE(model, 'unknown')
WHERE feature IS NULL OR source_type IS NULL OR source_id IS NULL
   OR agent_role IS NULL OR provider IS NULL OR model IS NULL;

ALTER TABLE run_step_usage
  ALTER COLUMN feature SET NOT NULL,
  ALTER COLUMN source_type SET NOT NULL,
  ALTER COLUMN source_id SET NOT NULL,
  ALTER COLUMN agent_role SET NOT NULL,
  ALTER COLUMN provider SET NOT NULL,
  ALTER COLUMN model SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_run_step_usage_llm_execution_context'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT fk_run_step_usage_llm_execution_context
      FOREIGN KEY (llm_execution_context_id)
      REFERENCES llm_execution_context(id)
      ON DELETE SET NULL;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_attempt_status'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_attempt_status
      CHECK (attempt_status IN ('SUCCESS', 'FAILED'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_attempt_values'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_attempt_values
      CHECK (attempt_number > 0 AND latency_ms >= 0 AND call_index >= 0);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_error_visibility'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_error_visibility
      CHECK (
        (attempt_status = 'SUCCESS' AND error_code IS NULL AND error_redacted IS NULL)
        OR
        (attempt_status = 'FAILED' AND error_code IS NOT NULL AND error_redacted IS NOT NULL)
      );
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_provider_usage_shape'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_provider_usage_shape
      CHECK (
        provider_usage_raw_json IS NULL
        OR jsonb_typeof(provider_usage_raw_json) = 'object'
      );
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_context_created
  ON run_step_usage(llm_execution_context_id, created_at)
  WHERE llm_execution_context_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_org_created
  ON run_step_usage(org_id, created_at DESC)
  WHERE org_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_user_created
  ON run_step_usage(user_id, created_at DESC)
  WHERE user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_project_created
  ON run_step_usage(project_id, created_at DESC)
  WHERE project_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_provider_model_created
  ON run_step_usage(provider, model, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_run_step_usage_role_created
  ON run_step_usage(agent_role, created_at DESC)
  WHERE agent_role IS NOT NULL;
