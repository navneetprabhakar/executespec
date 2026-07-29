-- Activate the database half of atomic operation-attempt admission without wiring any provider.
-- Historical rows are not rewritten. This is an expand/contract migration: version-0 rows may
-- retain durable-context attribution so the previous application binary remains safe during
-- rollback, but they cannot claim any operation fence. Version 1 remains the only atomically
-- admitted operation-fenced lane.

-- V351 compared the deadline with caller-populated started_at. That is not an authority clock:
-- replace only that completeness constraint and leave temporal admission to the trigger's
-- clock_timestamp() checks below.
ALTER TABLE llm_usage_attempt_intent
  DROP CONSTRAINT ck_llm_usage_attempt_operation_fence_complete,
  ADD CONSTRAINT ck_llm_usage_attempt_operation_fence_complete
    CHECK (
      operation_fence_version = 0
      OR (
        llm_execution_context_id IS NOT NULL
        AND llm_execution_operation_id IS NOT NULL
        AND llm_execution_operation_attempt > 0
        AND llm_execution_lease_token IS NOT NULL
        AND operation_not_reclaim_before IS NOT NULL
        AND transport_deadline_at IS NOT NULL
      )
    );

CREATE OR REPLACE FUNCTION validate_llm_usage_operation_fence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  operation_row llm_execution_operation%ROWTYPE;
  database_now TIMESTAMPTZ := clock_timestamp();
BEGIN
  IF NEW.operation_fence_version = 0 THEN
    IF NEW.llm_execution_operation_id IS NOT NULL
       OR NEW.llm_execution_operation_attempt IS NOT NULL
       OR NEW.llm_execution_lease_token IS NOT NULL
       OR NEW.operation_not_reclaim_before IS NOT NULL
       OR NEW.transport_deadline_at IS NOT NULL THEN
      RAISE EXCEPTION 'Legacy LLM attempt cannot carry operation fence fields';
    END IF;
    RETURN NEW;
  END IF;

  SELECT * INTO operation_row
  FROM llm_execution_operation
  WHERE id = NEW.llm_execution_operation_id
  FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'LLM attempt operation fence references a missing operation';
  END IF;
  IF operation_row.llm_execution_context_id IS DISTINCT FROM NEW.llm_execution_context_id THEN
    RAISE EXCEPTION 'LLM attempt operation context mismatch';
  END IF;
  IF operation_row.status <> 'RUNNING'
     OR operation_row.attempt <> NEW.llm_execution_operation_attempt
     OR operation_row.lease_owner IS NULL
     OR operation_row.lease_token IS DISTINCT FROM NEW.llm_execution_lease_token THEN
    RAISE EXCEPTION 'LLM attempt operation claim mismatch';
  END IF;
  IF operation_row.lease_expires_at IS NULL
     OR operation_row.lease_expires_at <= database_now THEN
    RAISE EXCEPTION 'LLM attempt operation lease expired before admission';
  END IF;
  IF NEW.transport_deadline_at IS NULL
     OR NEW.transport_deadline_at <= database_now THEN
    RAISE EXCEPTION 'LLM attempt transport deadline expired before admission';
  END IF;
  IF operation_row.not_reclaim_before IS DISTINCT FROM NEW.operation_not_reclaim_before
     OR NEW.operation_not_reclaim_before <= database_now THEN
    RAISE EXCEPTION 'LLM attempt operation reclaim horizon mismatch';
  END IF;
  IF NEW.operation_not_reclaim_before < NEW.transport_deadline_at
     OR operation_row.lease_expires_at < NEW.transport_deadline_at THEN
    RAISE EXCEPTION 'LLM attempt transport deadline exceeds admitted lease or reclaim horizon';
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION validate_llm_usage_operation_fence() IS
  'V354 permits rollback-compatible v0 attribution and validates v1 admission against database time and the exact live operation generation';
COMMENT ON COLUMN llm_usage_attempt_intent.operation_fence_version IS
  '0=legacy attempt without operation fence (durable context allowed for rollback); 1=atomically budgeted and admitted exact operation attempt';
