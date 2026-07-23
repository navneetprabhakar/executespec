-- Complete the canonical LLM-attempt fact with immutable correlation and causality dimensions.
-- V283 established tenant attribution and terminal outcomes. V293 makes the ledger materially
-- append-only at the database boundary and links managed-credit projections to the exact physical
-- attempt rather than reconciling by fuzzy run/model/time joins.

ALTER TABLE run_step_usage
  ALTER COLUMN id TYPE VARCHAR(160),
  ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(96),
  ADD COLUMN IF NOT EXISTS model_tier VARCHAR(32),
  ADD COLUMN IF NOT EXISTS logical_call_id VARCHAR(96),
  ADD COLUMN IF NOT EXISTS physical_attempt_id VARCHAR(160),
  ADD COLUMN IF NOT EXISTS parent_attempt_id VARCHAR(160),
  ADD COLUMN IF NOT EXISTS attempt_kind VARCHAR(24),
  ADD COLUMN IF NOT EXISTS attempt_ordinal BIGINT;

UPDATE run_step_usage
SET correlation_id = COALESCE(correlation_id, llm_execution_context_id, source_id, run_id),
    model_tier = COALESCE(model_tier, 'UNKNOWN'),
    logical_call_id = COALESCE(logical_call_id, 'legacy:' || id),
    physical_attempt_id = COALESCE(physical_attempt_id, 'legacy:' || id),
    attempt_kind = COALESCE(attempt_kind, CASE WHEN retry_of IS NULL THEN 'PRIMARY' ELSE 'RETRY' END),
    attempt_ordinal = COALESCE(attempt_ordinal, GREATEST(attempt_number, 1));

ALTER TABLE run_step_usage
  ALTER COLUMN correlation_id SET NOT NULL,
  ALTER COLUMN model_tier SET NOT NULL,
  ALTER COLUMN logical_call_id SET NOT NULL,
  ALTER COLUMN physical_attempt_id SET NOT NULL,
  ALTER COLUMN attempt_kind SET NOT NULL,
  ALTER COLUMN attempt_ordinal SET NOT NULL;

ALTER TABLE managed_ai_usage_event
  ADD COLUMN IF NOT EXISTS usage_event_id VARCHAR(160);

CREATE TABLE IF NOT EXISTS llm_usage_attempt_intent (
  physical_attempt_id VARCHAR(160) PRIMARY KEY,
  logical_call_id VARCHAR(96) NOT NULL,
  parent_attempt_id VARCHAR(160),
  attempt_kind VARCHAR(24) NOT NULL,
  attempt_ordinal BIGINT NOT NULL,
  llm_execution_context_id VARCHAR(64),
  correlation_id VARCHAR(96) NOT NULL,
  org_id VARCHAR(64),
  user_id VARCHAR(64),
  project_id VARCHAR(64),
  feature VARCHAR(80) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(160) NOT NULL,
  run_id VARCHAR(96) NOT NULL,
  task_id VARCHAR(160),
  agent_role VARCHAR(80) NOT NULL,
  step_id VARCHAR(120),
  provider VARCHAR(32) NOT NULL,
  model VARCHAR(128) NOT NULL,
  model_tier VARCHAR(32) NOT NULL,
  key_source VARCHAR(16) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_llm_usage_attempt_intent_kind
    CHECK (attempt_kind IN ('PRIMARY', 'RETRY', 'FAILOVER', 'CACHE_MINT', 'CAPABILITY')),
  CONSTRAINT ck_llm_usage_attempt_intent_ordinal CHECK (attempt_ordinal > 0),
  CONSTRAINT fk_llm_usage_attempt_intent_context
    FOREIGN KEY (llm_execution_context_id) REFERENCES llm_execution_context(id) ON DELETE RESTRICT
);

INSERT INTO llm_usage_attempt_intent (
  physical_attempt_id, logical_call_id, parent_attempt_id, attempt_kind, attempt_ordinal,
  llm_execution_context_id, correlation_id, org_id, user_id, project_id, feature, source_type,
  source_id, run_id, task_id, agent_role, step_id, provider, model, model_tier, key_source, started_at)
SELECT physical_attempt_id, logical_call_id, parent_attempt_id, attempt_kind, attempt_ordinal,
       llm_execution_context_id, correlation_id, org_id, user_id, project_id, feature, source_type,
       source_id, run_id, task_id, agent_role, step_id, provider, model, model_tier, key_source,
       created_at
FROM run_step_usage
ON CONFLICT (physical_attempt_id) DO NOTHING;

DO $$
BEGIN
  -- Immutable audit facts cannot support a parent delete that rewrites their context foreign key.
  -- Replace V283's SET NULL action with explicit retention semantics.
  IF EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_run_step_usage_llm_execution_context'
  ) THEN
    ALTER TABLE run_step_usage DROP CONSTRAINT fk_run_step_usage_llm_execution_context;
    ALTER TABLE run_step_usage
      ADD CONSTRAINT fk_run_step_usage_llm_execution_context
      FOREIGN KEY (llm_execution_context_id)
      REFERENCES llm_execution_context(id)
      ON DELETE RESTRICT;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_attempt_kind'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_attempt_kind
      CHECK (attempt_kind IN ('PRIMARY', 'RETRY', 'FAILOVER', 'CACHE_MINT', 'CAPABILITY'));
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'ck_run_step_usage_attempt_ordinal'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT ck_run_step_usage_attempt_ordinal CHECK (attempt_ordinal > 0);
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_managed_ai_usage_event_usage_event'
  ) THEN
    ALTER TABLE managed_ai_usage_event
      ADD CONSTRAINT fk_managed_ai_usage_event_usage_event
      FOREIGN KEY (usage_event_id) REFERENCES run_step_usage(id) ON DELETE RESTRICT;
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_run_step_usage_attempt_intent'
  ) THEN
    ALTER TABLE run_step_usage
      ADD CONSTRAINT fk_run_step_usage_attempt_intent
      FOREIGN KEY (physical_attempt_id)
      REFERENCES llm_usage_attempt_intent(physical_attempt_id)
      ON DELETE RESTRICT;
  END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_run_step_usage_physical_terminal
  ON run_step_usage(physical_attempt_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_managed_ai_usage_event_usage_event
  ON managed_ai_usage_event(usage_event_id)
  WHERE usage_event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_run_step_usage_correlation_created
  ON run_step_usage(correlation_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_run_step_usage_logical_ordinal
  ON run_step_usage(logical_call_id, attempt_ordinal);

CREATE INDEX IF NOT EXISTS idx_llm_usage_attempt_intent_started
  ON llm_usage_attempt_intent(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_llm_usage_attempt_intent_org_started
  ON llm_usage_attempt_intent(org_id, started_at DESC)
  WHERE org_id IS NOT NULL;

-- Material UPDATE/DELETE is forbidden. A byte-identical ORM merge is allowed so idempotent replay
-- can safely converge without weakening append-only evidence.
CREATE OR REPLACE FUNCTION reject_llm_usage_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'run_step_usage is append-only';
  END IF;
  IF NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'run_step_usage is append-only';
  END IF;
  RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS trg_run_step_usage_append_only ON run_step_usage;
CREATE TRIGGER trg_run_step_usage_append_only
BEFORE UPDATE OR DELETE ON run_step_usage
FOR EACH ROW EXECUTE FUNCTION reject_llm_usage_mutation();

DROP TRIGGER IF EXISTS trg_llm_usage_attempt_intent_append_only ON llm_usage_attempt_intent;
CREATE TRIGGER trg_llm_usage_attempt_intent_append_only
BEFORE UPDATE OR DELETE ON llm_usage_attempt_intent
FOR EACH ROW EXECUTE FUNCTION reject_llm_usage_mutation();

CREATE OR REPLACE VIEW canonical_llm_usage_event AS
SELECT
  i.physical_attempt_id AS intent_id,
  i.started_at,
  u.*,
  COALESCE(m.ai_credits_charged, 0) AS managed_ai_credits,
  m.id AS managed_ai_event_id,
  CASE
    WHEN m.id IS NOT NULL THEN 'RECORDED'
    WHEN u.key_source IN ('USER', 'ORG') THEN 'NOT_APPLICABLE'
    ELSE 'UNRECONCILED'
  END AS managed_ai_projection_status
FROM llm_usage_attempt_intent i
LEFT JOIN run_step_usage u ON u.physical_attempt_id = i.physical_attempt_id
LEFT JOIN managed_ai_usage_event m ON m.usage_event_id = u.id;

COMMENT ON VIEW canonical_llm_usage_event IS
  'Immutable provider-attempt facts joined to their exact managed-credit projection';
