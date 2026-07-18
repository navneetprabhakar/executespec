-- Cross-JVM durable recovery for exact terminal LLM usage facts.
--
-- V293 writes an immutable provider-attempt intent before transport. A terminal fact used to fall
-- back directly to host-local JSON when run_step_usage could not be written. This outbox commits
-- the exact, secret-free terminal payload independently before materializing the canonical ledger,
-- so any JVM can resume after a crash or a transient ledger failure.

ALTER TABLE run_step_usage
  ADD COLUMN terminal_payload_sha256 CHAR(64);

CREATE TABLE llm_usage_terminal_outbox (
  physical_attempt_id VARCHAR(160) PRIMARY KEY,
  payload JSONB NOT NULL,
  payload_sha256 CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt BIGINT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  lease_owner VARCHAR(160),
  lease_token BIGINT NOT NULL DEFAULT 0,
  lease_expires_at TIMESTAMPTZ,
  heartbeat_at TIMESTAMPTZ,
  last_error VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT fk_llm_usage_terminal_outbox_intent
    FOREIGN KEY (physical_attempt_id)
      REFERENCES llm_usage_attempt_intent(physical_attempt_id) ON DELETE RESTRICT,
  CONSTRAINT ck_llm_usage_terminal_outbox_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
  CONSTRAINT ck_llm_usage_terminal_outbox_attempt CHECK (attempt >= 0),
  CONSTRAINT ck_llm_usage_terminal_outbox_payload_object
    CHECK (jsonb_typeof(payload) = 'object'),
  CONSTRAINT ck_llm_usage_terminal_outbox_lease
    CHECK (
      (status = 'PROCESSING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
      OR
      (status <> 'PROCESSING' AND lease_owner IS NULL AND lease_expires_at IS NULL)
    ),
  CONSTRAINT ck_llm_usage_terminal_outbox_completion
    CHECK (
      (status IN ('COMPLETED', 'DEAD') AND completed_at IS NOT NULL)
      OR
      (status IN ('PENDING', 'PROCESSING') AND completed_at IS NULL)
    )
);

CREATE INDEX idx_llm_usage_terminal_outbox_due
  ON llm_usage_terminal_outbox(next_retry_at, created_at)
  WHERE status = 'PENDING';

CREATE INDEX idx_llm_usage_terminal_outbox_stale_lease
  ON llm_usage_terminal_outbox(lease_expires_at, created_at)
  WHERE status = 'PROCESSING';

COMMENT ON TABLE llm_usage_terminal_outbox IS
  'Exact secret-free terminal usage payload staged before canonical ledger materialization';
COMMENT ON COLUMN llm_usage_terminal_outbox.payload_sha256 IS
  'Application-computed SHA-256 used to reject physical-attempt id reuse with different facts';
COMMENT ON COLUMN run_step_usage.terminal_payload_sha256 IS
  'V298+ canonical terminal-payload checksum; NULL only for facts predating the durable outbox';
