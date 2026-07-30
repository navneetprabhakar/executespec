ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS continuation_approval_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS continuation_approval_proposal_digest VARCHAR(64),
    ADD COLUMN IF NOT EXISTS continuation_approval_status VARCHAR(24),
    ADD COLUMN IF NOT EXISTS continuation_approval_provider VARCHAR(40),
    ADD COLUMN IF NOT EXISTS continuation_approval_model VARCHAR(128),
    ADD COLUMN IF NOT EXISTS continuation_approval_action VARCHAR(40),
    ADD COLUMN IF NOT EXISTS continuation_approval_post_review BOOLEAN;

ALTER TABLE agent_task
    ADD CONSTRAINT chk_agent_task_continuation_approval_status
    CHECK (
        continuation_approval_status IS NULL
        OR continuation_approval_status IN (
            'CLAIMED',
            'QUEUED',
            'COMPLETED',
            'REJECTED',
            'FAILED'
        )
    );

ALTER TABLE agent_task
    ADD CONSTRAINT chk_agent_task_continuation_approval_identity
    CHECK (
        (
            continuation_approval_id IS NULL
            AND continuation_approval_proposal_digest IS NULL
            AND continuation_approval_status IS NULL
            AND continuation_approval_provider IS NULL
            AND continuation_approval_model IS NULL
            AND continuation_approval_action IS NULL
            AND continuation_approval_post_review IS NULL
        )
        OR (
            continuation_approval_id IS NOT NULL
            AND continuation_approval_proposal_digest IS NOT NULL
            AND continuation_approval_status IS NOT NULL
            AND continuation_approval_provider IS NOT NULL
            AND continuation_approval_model IS NOT NULL
            AND continuation_approval_action IS NOT NULL
            AND continuation_approval_post_review IS NOT NULL
        )
    );

CREATE INDEX IF NOT EXISTS idx_agent_task_continuation_approval
    ON agent_task (run_id, task_id, continuation_approval_proposal_digest)
    WHERE continuation_approval_proposal_digest IS NOT NULL;
