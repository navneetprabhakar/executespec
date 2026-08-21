-- Recovery diagnostics are additive evidence. They must never overwrite the original task
-- rejection reason or rejected diff captured before rollback.
ALTER TABLE workspace_generation_attempt
    ADD COLUMN recovery_failure_code VARCHAR(64),
    ADD COLUMN recovery_failure_reason TEXT,
    ADD COLUMN recovery_failure_at TIMESTAMPTZ,
    ADD CONSTRAINT workspace_generation_recovery_failure_chk CHECK (
        (recovery_failure_code IS NULL
            AND recovery_failure_reason IS NULL
            AND recovery_failure_at IS NULL)
        OR (recovery_failure_code IS NOT NULL
            AND recovery_failure_reason IS NOT NULL
            AND recovery_failure_at IS NOT NULL)
    ),
    ADD CONSTRAINT workspace_generation_rejected_diff_pair_chk CHECK (
        (rejected_diff_digest IS NULL AND rejected_diff_json IS NULL)
        OR (rejected_diff_digest ~ '^[0-9a-f]{64}$' AND rejected_diff_json IS NOT NULL)
    ),
    ADD CONSTRAINT workspace_generation_failure_pair_chk CHECK (
        (failure_code IS NULL AND failure_reason IS NULL)
        OR (failure_code IS NOT NULL AND failure_reason IS NOT NULL)
    );

CREATE OR REPLACE FUNCTION preserve_workspace_generation_failure_evidence()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.rejected_diff_digest IS NOT NULL AND (
        NEW.rejected_diff_digest IS DISTINCT FROM OLD.rejected_diff_digest
        OR NEW.rejected_diff_json IS DISTINCT FROM OLD.rejected_diff_json
    ) THEN
        RAISE EXCEPTION 'workspace generation rejected diff evidence is immutable';
    END IF;
    IF OLD.failure_code IS NOT NULL AND (
        NEW.failure_code IS DISTINCT FROM OLD.failure_code
        OR NEW.failure_reason IS DISTINCT FROM OLD.failure_reason
    ) THEN
        RAISE EXCEPTION 'workspace generation original failure evidence is immutable';
    END IF;
    IF OLD.recovery_failure_code IS NOT NULL AND (
        NEW.recovery_failure_code IS DISTINCT FROM OLD.recovery_failure_code
        OR NEW.recovery_failure_reason IS DISTINCT FROM OLD.recovery_failure_reason
        OR NEW.recovery_failure_at IS DISTINCT FROM OLD.recovery_failure_at
    ) THEN
        RAISE EXCEPTION 'workspace generation recovery failure evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_failure_evidence_immutable
BEFORE UPDATE OF rejected_diff_digest, rejected_diff_json, failure_code, failure_reason,
    recovery_failure_code, recovery_failure_reason, recovery_failure_at
ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION preserve_workspace_generation_failure_evidence();
