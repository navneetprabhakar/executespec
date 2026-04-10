-- Side-effect outbox for reliable delivery of integration actions
-- and saga compensation on run failure.
CREATE TABLE side_effect_outbox (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         VARCHAR(255) NOT NULL,
    effect_type    VARCHAR(60)  NOT NULL,   -- GITHUB_PR | CLOUD_RUN_DEPLOY | JIRA_COMMENT | CONFLUENCE_PAGE | GCS_ARCHIVE | COMPENSATE_*
    payload        JSONB        NOT NULL DEFAULT '{}',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',   -- PENDING | PROCESSING | COMPLETED | FAILED
    attempt        INT          NOT NULL DEFAULT 0,
    max_attempts   INT          NOT NULL DEFAULT 3,
    next_retry_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error     TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

CREATE INDEX idx_side_effect_outbox_pending
    ON side_effect_outbox (status, next_retry_at)
    WHERE status IN ('PENDING', 'PROCESSING');

CREATE INDEX idx_side_effect_outbox_run_id
    ON side_effect_outbox (run_id);
