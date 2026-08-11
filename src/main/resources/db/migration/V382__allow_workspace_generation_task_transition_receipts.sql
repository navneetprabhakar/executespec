-- V374 correctly made resolved generation evidence immutable, but that blanket trigger also
-- rejects the post-resolution outbox acknowledgement introduced by V380. Preserve the V374
-- function unchanged for every generation/evidence mutation and exempt only a byte-identical
-- ROLLED_BACK row whose differences are limited to the task-transition receipt fields. A dedicated
-- trigger below validates that narrow state machine.
DROP TRIGGER workspace_generation_attempt_transition ON workspace_generation_attempt;

CREATE TRIGGER workspace_generation_attempt_transition
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW
WHEN (NOT (
    OLD.state = 'ROLLED_BACK'
    AND NEW.state = 'ROLLED_BACK'
    AND (to_jsonb(NEW) - ARRAY[
        'task_transition_state', 'task_transition_completed_at',
        'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
    ]::text[])
      = (to_jsonb(OLD) - ARRAY[
        'task_transition_state', 'task_transition_completed_at',
        'task_transition_failure_reason', 'task_transition_failure_at', 'updated_at'
      ]::text[])
))
EXECUTE FUNCTION enforce_workspace_generation_attempt_transition();

CREATE OR REPLACE FUNCTION enforce_workspace_generation_task_transition_receipt()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.state <> 'ROLLED_BACK' OR NEW.state <> 'ROLLED_BACK'
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
    OLD.state = 'ROLLED_BACK'
    AND NEW.state = 'ROLLED_BACK'
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
