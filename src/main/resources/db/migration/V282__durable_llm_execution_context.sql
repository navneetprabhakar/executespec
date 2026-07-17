-- Durable, tenant-scoped identity and lifecycle for every customer or explicitly-internal LLM
-- operation. Provider credentials are deliberately NOT persisted here: dispatch resolves the
-- current encrypted user/org credential after validating it against this immutable policy snapshot.

CREATE TABLE IF NOT EXISTS llm_execution_context (
  id VARCHAR(64) PRIMARY KEY,
  context_type VARCHAR(32) NOT NULL,
  feature VARCHAR(80) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(160) NOT NULL,
  parent_context_id VARCHAR(64),
  correlation_id VARCHAR(96) NOT NULL,

  scope_mode VARCHAR(24) NOT NULL,
  isolation_key VARCHAR(160) NOT NULL,
  org_id VARCHAR(64),
  user_id VARCHAR(64),
  project_id VARCHAR(64),
  repo_id UUID,

  key_source_mode VARCHAR(16) NOT NULL,
  key_subject_user_id VARCHAR(64),
  allowed_providers_json JSONB NOT NULL,
  allowed_provider_models_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  provider_key_sources_json JSONB NOT NULL,
  model_overrides_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  requested_provider VARCHAR(32),
  requested_model VARCHAR(160),
  routing_mode VARCHAR(32) NOT NULL,
  route_pinned BOOLEAN NOT NULL DEFAULT FALSE,
  allow_provider_failover BOOLEAN NOT NULL DEFAULT FALSE,
  route_snapshot_version INTEGER NOT NULL,

  max_input_tokens BIGINT,
  max_output_tokens BIGINT,
  max_total_tokens BIGINT,
  max_cost_usd NUMERIC(14, 6),
  max_provider_calls INTEGER,

  status VARCHAR(24) NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL DEFAULT 1,
  idempotency_key VARCHAR(160) NOT NULL,
  request_fingerprint VARCHAR(64) NOT NULL,
  lease_owner VARCHAR(128),
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  cancellation_requested_at TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  last_error_code VARCHAR(64),
  last_error_redacted TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,

  CONSTRAINT ck_llm_execution_context_scope
    CHECK (
      (scope_mode = 'CUSTOMER'
        AND org_id IS NOT NULL
        AND user_id IS NOT NULL
        AND isolation_key = ('org:' || org_id))
      OR
      (scope_mode = 'PLATFORM_INTERNAL'
        AND org_id IS NULL
        AND isolation_key = 'platform:internal')
    ),
  CONSTRAINT ck_llm_execution_context_type
    CHECK (context_type IN (
      'PIPELINE_RUN', 'AGENT_SESSION', 'AGENT_TURN', 'PROJECT_JOB', 'REVIEW_JOB',
      'EMBEDDING_JOB', 'BENCHMARK', 'INTERNAL_TEST'
    )),
  CONSTRAINT ck_llm_execution_context_status
    CHECK (status IN (
      'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'
    )),
  CONSTRAINT ck_llm_execution_context_key_source
    CHECK (key_source_mode IN ('SYSTEM', 'OWN_KEYS', 'HYBRID')),
  CONSTRAINT ck_llm_execution_context_routing
    CHECK (routing_mode IN ('PROVIDER_PREFERRED', 'EXACT')),
  CONSTRAINT ck_llm_execution_context_attempts
    CHECK (attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts),
  CONSTRAINT ck_llm_execution_context_route_snapshot
    CHECK (route_snapshot_version > 0),
  CONSTRAINT ck_llm_execution_context_json_shapes
    CHECK (
      jsonb_typeof(allowed_providers_json) = 'array'
      AND jsonb_typeof(allowed_provider_models_json) = 'object'
      AND jsonb_typeof(provider_key_sources_json) = 'object'
      AND jsonb_typeof(model_overrides_json) = 'object'
    ),
  CONSTRAINT ck_llm_execution_context_customer_providers
    CHECK (scope_mode <> 'CUSTOMER' OR jsonb_array_length(allowed_providers_json) > 0),
  CONSTRAINT ck_llm_execution_context_exact_route
    CHECK (
      NOT route_pinned
      OR (
        routing_mode = 'EXACT'
        AND requested_provider IS NOT NULL
        AND requested_model IS NOT NULL
        AND NOT allow_provider_failover
      )
    ),
  CONSTRAINT ck_llm_execution_context_budget
    CHECK (
      (max_input_tokens IS NULL OR max_input_tokens > 0)
      AND (max_output_tokens IS NULL OR max_output_tokens > 0)
      AND (max_total_tokens IS NULL OR max_total_tokens > 0)
      AND (max_cost_usd IS NULL OR max_cost_usd > 0)
      AND (max_provider_calls IS NULL OR max_provider_calls > 0)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_llm_execution_context_idempotency
  ON llm_execution_context (
    isolation_key, feature, source_type, source_id, idempotency_key
  );

CREATE INDEX IF NOT EXISTS idx_llm_execution_context_due
  ON llm_execution_context (status, next_attempt_at, lease_expires_at)
  WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX IF NOT EXISTS idx_llm_execution_context_org_created
  ON llm_execution_context (org_id, created_at DESC)
  WHERE org_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_llm_execution_context_project_created
  ON llm_execution_context (project_id, created_at DESC)
  WHERE project_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_llm_execution_context_source
  ON llm_execution_context (source_type, source_id, created_at DESC);
