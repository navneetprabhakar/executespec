-- Dormant per-operation lease/attempt foundation for shared LLM execution contexts.
-- This migration does not activate dispatch fencing. A later wiring slice must atomically validate
-- these claims with budget reservation and physical-attempt intent insertion before transport.

CREATE TABLE llm_execution_operation (
  id VARCHAR(64) PRIMARY KEY,
  llm_execution_context_id VARCHAR(64) NOT NULL,
  operation_key VARCHAR(192) NOT NULL,
  operation_kind VARCHAR(40) NOT NULL,
  invocation INTEGER NOT NULL,
  operation_fingerprint VARCHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  attempt INTEGER NOT NULL DEFAULT 0,
  max_attempts INTEGER NOT NULL,
  lease_owner VARCHAR(128),
  lease_token UUID,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  not_reclaim_before TIMESTAMPTZ,
  next_attempt_at TIMESTAMPTZ,
  cancellation_requested_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  last_error_code VARCHAR(64),
  last_error_redacted TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,

  CONSTRAINT fk_llm_execution_operation_context
    FOREIGN KEY (llm_execution_context_id)
    REFERENCES llm_execution_context(id)
    ON DELETE RESTRICT,
  CONSTRAINT uq_llm_execution_operation_invocation
    UNIQUE (llm_execution_context_id, operation_key, invocation),
  CONSTRAINT ck_llm_execution_operation_status
    CHECK (status IN (
      'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'
    )),
  CONSTRAINT ck_llm_execution_operation_attempts
    CHECK (invocation > 0 AND attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts),
  CONSTRAINT ck_llm_execution_operation_fingerprint
    CHECK (operation_fingerprint ~ '^[a-f0-9]{64}$'),
  CONSTRAINT ck_llm_execution_operation_state
    CHECK (
      (status = 'QUEUED'
        AND lease_owner IS NULL
        AND lease_token IS NULL
        AND lease_expires_at IS NULL
        AND completed_at IS NULL)
      OR
      (status = 'RUNNING'
        AND attempt > 0
        AND lease_owner IS NOT NULL
        AND lease_token IS NOT NULL
        AND lease_expires_at IS NOT NULL
        AND heartbeat_at IS NOT NULL
        AND completed_at IS NULL)
      OR
      (status = 'CANCEL_REQUESTED'
        AND attempt > 0
        AND lease_owner IS NOT NULL
        AND lease_token IS NOT NULL
        AND lease_expires_at IS NOT NULL
        AND cancellation_requested_at IS NOT NULL
        AND completed_at IS NULL)
      OR
      (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
        AND lease_owner IS NULL
        AND lease_token IS NULL
        AND lease_expires_at IS NULL
        AND completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_llm_execution_operation_nonterminal
  ON llm_execution_operation(llm_execution_context_id, operation_key)
  WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX idx_llm_execution_operation_due
  ON llm_execution_operation(status, next_attempt_at, lease_expires_at, not_reclaim_before)
  WHERE status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED');

CREATE INDEX idx_llm_execution_operation_context_created
  ON llm_execution_operation(llm_execution_context_id, created_at DESC);

CREATE OR REPLACE FUNCTION enforce_llm_execution_operation_identity()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.llm_execution_context_id IS DISTINCT FROM OLD.llm_execution_context_id
     OR NEW.operation_key IS DISTINCT FROM OLD.operation_key
     OR NEW.operation_kind IS DISTINCT FROM OLD.operation_kind
     OR NEW.invocation IS DISTINCT FROM OLD.invocation
     OR NEW.operation_fingerprint IS DISTINCT FROM OLD.operation_fingerprint
     OR NEW.max_attempts IS DISTINCT FROM OLD.max_attempts
     OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'llm_execution_operation immutable identity cannot change';
  END IF;
  IF NEW.attempt < OLD.attempt THEN
    RAISE EXCEPTION 'llm_execution_operation attempt cannot decrease';
  END IF;
  IF OLD.not_reclaim_before IS NOT NULL
     AND (NEW.not_reclaim_before IS NULL OR NEW.not_reclaim_before < OLD.not_reclaim_before) THEN
    RAISE EXCEPTION 'llm_execution_operation reclaim horizon cannot decrease';
  END IF;
  IF OLD.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND NEW IS DISTINCT FROM OLD THEN
    RAISE EXCEPTION 'terminal llm_execution_operation is immutable';
  END IF;

  IF OLD.status = 'QUEUED' THEN
    IF NEW.status = 'RUNNING' THEN
      IF NEW.attempt <> OLD.attempt + 1
         OR NEW.lease_token IS NULL
         OR (OLD.next_attempt_at IS NOT NULL AND OLD.next_attempt_at > clock_timestamp())
         OR (OLD.not_reclaim_before IS NOT NULL
             AND OLD.not_reclaim_before > clock_timestamp()) THEN
        RAISE EXCEPTION 'illegal QUEUED to RUNNING operation claim';
      END IF;
    ELSIF NEW.status IN ('FAILED', 'CANCELLED') THEN
      IF NEW.attempt <> OLD.attempt THEN
        RAISE EXCEPTION 'illegal QUEUED terminal operation transition';
      END IF;
    ELSE
      RAISE EXCEPTION 'illegal llm_execution_operation transition % to %', OLD.status, NEW.status;
    END IF;
  ELSIF OLD.status = 'RUNNING' THEN
    IF NEW.status = 'RUNNING' AND NEW.attempt = OLD.attempt THEN
      IF NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
         OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
         OR NEW.heartbeat_at < OLD.heartbeat_at
         OR NEW.lease_expires_at < OLD.lease_expires_at THEN
        RAISE EXCEPTION 'illegal RUNNING operation heartbeat';
      END IF;
    ELSIF NEW.status = 'RUNNING' AND NEW.attempt = OLD.attempt + 1 THEN
      IF OLD.lease_expires_at > clock_timestamp()
         OR (OLD.not_reclaim_before IS NOT NULL AND OLD.not_reclaim_before > clock_timestamp())
         OR NEW.lease_token IS NULL
         OR NEW.lease_token IS NOT DISTINCT FROM OLD.lease_token
         OR NEW.lease_owner IS NOT DISTINCT FROM OLD.lease_owner THEN
        RAISE EXCEPTION 'illegal RUNNING operation reclaim';
      END IF;
    ELSIF NEW.status = 'QUEUED' THEN
      IF NEW.attempt <> OLD.attempt THEN
        RAISE EXCEPTION 'illegal RUNNING retry transition';
      END IF;
    ELSIF NEW.status = 'CANCEL_REQUESTED' THEN
      IF NEW.attempt <> OLD.attempt
         OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
         OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
         OR NEW.cancellation_requested_at IS NULL THEN
        RAISE EXCEPTION 'illegal RUNNING cancellation request';
      END IF;
    ELSIF NEW.status IN ('SUCCEEDED', 'FAILED') THEN
      IF NEW.attempt <> OLD.attempt THEN
        RAISE EXCEPTION 'illegal RUNNING terminal operation transition';
      END IF;
    ELSIF NEW.status = 'CANCELLED' THEN
      IF NEW.attempt <> OLD.attempt
         OR OLD.lease_expires_at > clock_timestamp()
         OR (OLD.not_reclaim_before IS NOT NULL AND OLD.not_reclaim_before > clock_timestamp()) THEN
        RAISE EXCEPTION 'illegal RUNNING cancellation recovery';
      END IF;
    ELSE
      RAISE EXCEPTION 'illegal llm_execution_operation transition % to %', OLD.status, NEW.status;
    END IF;
  ELSIF OLD.status = 'CANCEL_REQUESTED' THEN
    IF NEW.status = 'CANCEL_REQUESTED' THEN
      IF NEW.attempt <> OLD.attempt
         OR NEW.lease_owner IS DISTINCT FROM OLD.lease_owner
         OR NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN
        RAISE EXCEPTION 'illegal CANCEL_REQUESTED operation mutation';
      END IF;
    ELSIF NEW.status = 'CANCELLED' THEN
      IF NEW.attempt <> OLD.attempt THEN
        RAISE EXCEPTION 'illegal cancellation acknowledgement';
      END IF;
    ELSE
      RAISE EXCEPTION 'illegal llm_execution_operation transition % to %', OLD.status, NEW.status;
    END IF;
  ELSE
    RAISE EXCEPTION 'illegal llm_execution_operation transition % to %', OLD.status, NEW.status;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_llm_execution_operation_identity
BEFORE UPDATE ON llm_execution_operation
FOR EACH ROW EXECUTE FUNCTION enforce_llm_execution_operation_identity();

ALTER TABLE llm_usage_attempt_intent
  ADD COLUMN operation_fence_version SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN llm_execution_operation_id VARCHAR(64),
  ADD COLUMN llm_execution_operation_attempt INTEGER,
  ADD COLUMN llm_execution_lease_token UUID,
  ADD COLUMN operation_not_reclaim_before TIMESTAMPTZ,
  ADD COLUMN transport_deadline_at TIMESTAMPTZ,
  ADD CONSTRAINT ck_llm_usage_attempt_operation_fence_version
    CHECK (operation_fence_version IN (0, 1)),
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
        AND transport_deadline_at > started_at
      )
    ),
  ADD CONSTRAINT fk_llm_usage_attempt_operation
    FOREIGN KEY (llm_execution_operation_id)
    REFERENCES llm_execution_operation(id)
    ON DELETE RESTRICT;

CREATE INDEX idx_llm_usage_attempt_operation
  ON llm_usage_attempt_intent(llm_execution_operation_id, llm_execution_operation_attempt)
  WHERE llm_execution_operation_id IS NOT NULL;

CREATE OR REPLACE FUNCTION validate_llm_usage_operation_fence()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  operation_row llm_execution_operation%ROWTYPE;
BEGIN
  IF NEW.operation_fence_version = 0 THEN
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
     OR operation_row.lease_token IS DISTINCT FROM NEW.llm_execution_lease_token THEN
    RAISE EXCEPTION 'LLM attempt operation claim mismatch';
  END IF;
  IF operation_row.not_reclaim_before IS DISTINCT FROM NEW.operation_not_reclaim_before THEN
    RAISE EXCEPTION 'LLM attempt operation reclaim horizon mismatch';
  END IF;
  IF operation_row.lease_expires_at <= NEW.started_at THEN
    RAISE EXCEPTION 'LLM attempt operation lease expired before admission';
  END IF;
  IF NEW.operation_not_reclaim_before < NEW.transport_deadline_at THEN
    RAISE EXCEPTION 'LLM attempt transport deadline exceeds admitted reclaim horizon';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_llm_usage_attempt_operation_fence
BEFORE INSERT ON llm_usage_attempt_intent
FOR EACH ROW EXECUTE FUNCTION validate_llm_usage_operation_fence();

COMMENT ON TABLE llm_execution_operation IS
  'Dormant independently leased operation attempts under a shared LLM policy/budget context';
COMMENT ON COLUMN llm_execution_operation.not_reclaim_before IS
  'Monotonic safety horizon for an admitted external attempt; reclaim requires both lease expiry and this timestamp';
COMMENT ON COLUMN llm_usage_attempt_intent.operation_fence_version IS
  '0=legacy/unwired attempt, 1=attempt atomically admitted against an exact operation lease';
