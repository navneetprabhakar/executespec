-- RR-090: GDPR (Article 17) erasure must COMPLETE or remain a tracked, retryable obligation —
-- never a log line. When erasure cannot confirm every one of a user's live runs has reached a
-- terminal status within the bounded settle window, it records a durable obligation here. The
-- daily retention sweep re-attempts erasure for each open obligation and clears it once no live
-- run remains, so no user's data survives erasure indefinitely.
--
-- One row per user (user_id PRIMARY KEY) makes the obligation idempotent: a re-request or a
-- scheduled retry upserts the same row rather than accumulating duplicates.

CREATE TABLE pending_erasure_obligation (
    user_id             VARCHAR(255) PRIMARY KEY,
    requested_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    attempts            INTEGER NOT NULL DEFAULT 1,
    live_runs_remaining BIGINT NOT NULL DEFAULT 0,
    last_reason         VARCHAR(255),
    CONSTRAINT ck_pending_erasure_attempts CHECK (attempts > 0),
    CONSTRAINT ck_pending_erasure_live_runs CHECK (live_runs_remaining >= 0)
);

CREATE INDEX idx_pending_erasure_last_attempt
    ON pending_erasure_obligation (last_attempt_at);

COMMENT ON TABLE pending_erasure_obligation IS
    'RR-090: durable retryable GDPR erasure obligation for users whose live runs did not '
    'terminalise within the bounded settle window; cleared when no live run remains.';
