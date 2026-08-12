-- Expand the V380 task-transition outbox so ACCEPTED owns an exact MARK_COMPLETE intent in the
-- same row update that makes the generation terminal. This is additive and mixed-version safe:
-- pre-V384 application writers may still create an ACCEPTED row with a null transition, and the
-- existing accepted-task reconciliation fallback remains until the rollback window closes.
ALTER TABLE workspace_generation_attempt
    DROP CONSTRAINT workspace_generation_task_transition_chk,
    ADD CONSTRAINT workspace_generation_task_transition_chk CHECK (
        (task_transition_kind IS NULL
            AND task_transition_state IS NULL
            AND task_transition_expected_status IS NULL
            AND task_transition_expected_recovery_attempt IS NULL
            AND task_transition_completed_at IS NULL
            AND task_transition_failure_reason IS NULL
            AND task_transition_failure_at IS NULL)
        OR (task_transition_kind IN ('MARK_PARTIAL', 'REOPEN_PENDING', 'MARK_COMPLETE')
            AND ((task_transition_kind = 'MARK_COMPLETE' AND state = 'ACCEPTED')
                OR (task_transition_kind IN ('MARK_PARTIAL', 'REOPEN_PENDING')
                    AND state = 'ROLLED_BACK'))
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

-- V383 reinstated the mature transition trigger with a ROLLED_BACK-only receipt exemption. Extend
-- that exact exemption to ACCEPTED while preserving the pre-dispatch guard and every other V374
-- immutable-evidence check.
DROP TRIGGER workspace_generation_attempt_transition ON workspace_generation_attempt;

CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state NOT IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED')
    AND NOT (
        OLD.state IN ('ACCEPTED','ROLLED_BACK')
        AND NEW.state = OLD.state
        AND (to_jsonb(NEW) - ARRAY[
            'task_transition_state', 'task_transition_completed_at',
            'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
        ]::text[])
          = (to_jsonb(OLD) - ARRAY[
            'task_transition_state', 'task_transition_completed_at',
            'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
          ]::text[])
    )
)
EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

DROP TRIGGER workspace_generation_task_transition_receipt ON workspace_generation_attempt;

CREATE OR REPLACE FUNCTION enforce_workspace_generation_task_transition_receipt()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state NOT IN ('ACCEPTED','ROLLED_BACK')
       OR NEW.state IS DISTINCT FROM OLD.state
       OR NEW.task_transition_kind IS DISTINCT FROM OLD.task_transition_kind
       OR NEW.task_transition_expected_status IS DISTINCT FROM OLD.task_transition_expected_status
       OR NEW.task_transition_expected_recovery_attempt
            IS DISTINCT FROM OLD.task_transition_expected_recovery_attempt THEN
        RAISE EXCEPTION 'resolved workspace generation task-transition identity is immutable';
    END IF;

    IF OLD.task_transition_state = 'PENDING'
       AND NEW.task_transition_state = 'COMPLETED' THEN
        IF NEW.task_transition_completed_at IS NULL
           OR NEW.task_transition_failure_reason IS DISTINCT FROM OLD.task_transition_failure_reason
           OR NEW.task_transition_failure_at IS DISTINCT FROM OLD.task_transition_failure_at THEN
            RAISE EXCEPTION 'workspace generation task-transition completion receipt is invalid';
        END IF;
    ELSIF OLD.task_transition_state = 'PENDING'
       AND NEW.task_transition_state = 'PENDING' THEN
        IF NEW.task_transition_completed_at IS NOT NULL
           OR ((NEW.task_transition_failure_reason IS NULL)
               <> (NEW.task_transition_failure_at IS NULL)) THEN
            RAISE EXCEPTION 'workspace generation task-transition failure receipt is invalid';
        END IF;
    ELSE
        RAISE EXCEPTION 'workspace generation task-transition receipt is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_task_transition_receipt
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (
    OLD.state IN ('ACCEPTED','ROLLED_BACK')
    AND NEW.state = OLD.state
    AND (to_jsonb(NEW) - ARRAY[
        'task_transition_state', 'task_transition_completed_at',
        'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
    ]::text[])
      = (to_jsonb(OLD) - ARRAY[
        'task_transition_state', 'task_transition_completed_at',
        'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
      ]::text[])
)
EXECUTE FUNCTION enforce_workspace_generation_task_transition_receipt();
