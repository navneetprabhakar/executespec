-- A restored workspace is not operationally resolved until the corresponding task-row transition
-- is durably published. Keeping the outbox on the immutable attempt makes crash recovery replayable
-- without inventing a second workspace authority.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN task_transition_kind VARCHAR(24),
    ADD COLUMN task_transition_state VARCHAR(16),
    ADD COLUMN task_transition_expected_status VARCHAR(64),
    ADD COLUMN task_transition_expected_recovery_attempt INTEGER,
    ADD COLUMN task_transition_completed_at TIMESTAMPTZ,
    ADD COLUMN task_transition_failure_reason TEXT,
    ADD COLUMN task_transition_failure_at TIMESTAMPTZ,
    ADD CONSTRAINT workspace_generation_task_transition_chk CHECK (
        (task_transition_kind IS NULL
            AND task_transition_state IS NULL
            AND task_transition_expected_status IS NULL
            AND task_transition_expected_recovery_attempt IS NULL
            AND task_transition_completed_at IS NULL
            AND task_transition_failure_reason IS NULL
            AND task_transition_failure_at IS NULL)
        OR (task_transition_kind IN ('MARK_PARTIAL', 'REOPEN_PENDING')
            AND task_transition_state IN ('PENDING', 'COMPLETED')
            AND task_transition_expected_status IS NOT NULL
            AND ((task_transition_state = 'PENDING' AND task_transition_completed_at IS NULL)
                OR (task_transition_state = 'COMPLETED'
                    AND task_transition_completed_at IS NOT NULL))
            AND ((task_transition_failure_reason IS NULL
                    AND task_transition_failure_at IS NULL)
                OR (task_transition_failure_reason IS NOT NULL
                    AND task_transition_failure_at IS NOT NULL)))
    );

-- Snapshot the exact task-row generation in the same statement that makes rollback terminal. If
-- the task row does not exist, rollback remains unresolved instead of publishing an unactionable
-- outbox receipt.
CREATE OR REPLACE FUNCTION bind_workspace_generation_task_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.task_transition_state = 'PENDING'
       AND OLD.task_transition_state IS NULL THEN
        SELECT t.status, t.recovery_attempt
          INTO NEW.task_transition_expected_status,
               NEW.task_transition_expected_recovery_attempt
          FROM agent_task t
         WHERE t.task_id = LEFT(NEW.run_id, 8) || '_' || NEW.task_id;
        IF NOT FOUND OR NEW.task_transition_expected_status IS NULL THEN
            RAISE EXCEPTION
                'workspace generation task transition target is missing: run=% task=%',
                NEW.run_id, NEW.task_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_task_transition_bind
BEFORE UPDATE OF task_transition_state ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION bind_workspace_generation_task_transition();

CREATE INDEX idx_workspace_generation_task_transition_pending
    ON workspace_generation_attempt(updated_at)
    WHERE task_transition_state = 'PENDING';
