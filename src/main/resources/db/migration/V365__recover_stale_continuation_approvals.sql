ALTER TABLE agent_task
    ADD COLUMN IF NOT EXISTS continuation_approval_updated_at TIMESTAMPTZ;

UPDATE agent_task
SET continuation_approval_updated_at = COALESCE(completed_at, started_at, NOW())
WHERE continuation_approval_id IS NOT NULL
  AND continuation_approval_updated_at IS NULL;

ALTER TABLE agent_task
    ADD CONSTRAINT chk_agent_task_continuation_approval_timestamp
    CHECK (
        (continuation_approval_id IS NULL AND continuation_approval_updated_at IS NULL)
        OR
        (continuation_approval_id IS NOT NULL AND continuation_approval_updated_at IS NOT NULL)
    );

CREATE INDEX IF NOT EXISTS idx_agent_task_continuation_approval_recovery
    ON agent_task (continuation_approval_status, continuation_approval_updated_at)
    WHERE continuation_approval_status IN ('CLAIMED', 'QUEUED');
