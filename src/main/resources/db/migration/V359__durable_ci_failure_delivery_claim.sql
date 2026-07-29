CREATE TABLE ci_failure_delivery_claim (
  finding_key VARCHAR(128) PRIMARY KEY,
  pull_request_record_id UUID NOT NULL REFERENCES pull_request_record(id) ON DELETE CASCADE,
  check_run_id VARCHAR(128) NOT NULL,
  head_sha VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  claim_token BIGINT NOT NULL DEFAULT 1,
  lease_expires_at TIMESTAMPTZ,
  run_id VARCHAR(255),
  error_code VARCHAR(96),
  created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT ci_failure_delivery_claim_status_ck
    CHECK (status IN ('CLAIMED','DISPATCHED','FAILED')),
  CONSTRAINT ci_failure_delivery_claim_terminal_ck CHECK (
    (status='CLAIMED' AND completed_at IS NULL AND run_id IS NULL
      AND lease_expires_at IS NOT NULL)
    OR
    (status='DISPATCHED' AND completed_at IS NOT NULL AND run_id IS NOT NULL
      AND lease_expires_at IS NULL)
    OR
    (status='FAILED' AND completed_at IS NOT NULL AND run_id IS NULL
      AND lease_expires_at IS NULL)
  ),
  CONSTRAINT ci_failure_delivery_claim_error_ck CHECK (
    (status='FAILED' AND error_code IS NOT NULL)
    OR
    (status<>'FAILED' AND error_code IS NULL)
  )
);

CREATE INDEX ix_ci_failure_delivery_claim_pr_created
  ON ci_failure_delivery_claim(pull_request_record_id, created_at DESC);

CREATE INDEX ix_ci_failure_delivery_claim_run
  ON ci_failure_delivery_claim(run_id)
  WHERE run_id IS NOT NULL;

COMMENT ON TABLE ci_failure_delivery_claim IS
  'V359 first-writer-wins admission for immutable GitHub check_run failure observations. '
  'CLAIMED and its PR-attempt reservation commit before PATCH submission; DISPATCHED binds the '
  'canonical run in a later fenced transaction. Expired CLAIMED and FAILED rows are recoverable.';
