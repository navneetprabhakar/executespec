-- Filesystem rollback is external to PostgreSQL, but no actor may publish rejection evidence or a
-- terminal rollback result after its exact workspace authority expired or was replaced. The
-- repository CAS remains useful diagnostics; this trigger is the authoritative database fence.
CREATE OR REPLACE FUNCTION fence_workspace_generation_rollback_authority()
RETURNS TRIGGER AS $$
DECLARE
    expected_authority VARCHAR(32);
    expected_token UUID;
    authority_current BOOLEAN;
    recovery_evidence_changed BOOLEAN;
BEGIN
    recovery_evidence_changed :=
        NEW.rejected_diff_digest IS DISTINCT FROM OLD.rejected_diff_digest
        OR NEW.rejected_diff_json IS DISTINCT FROM OLD.rejected_diff_json
        OR NEW.failure_code IS DISTINCT FROM OLD.failure_code
        OR NEW.failure_reason IS DISTINCT FROM OLD.failure_reason
        OR NEW.recovery_failure_code IS DISTINCT FROM OLD.recovery_failure_code
        OR NEW.recovery_failure_reason IS DISTINCT FROM OLD.recovery_failure_reason
        OR NEW.recovery_failure_at IS DISTINCT FROM OLD.recovery_failure_at;

    IF NEW.recovery_lease_token IS NOT NULL
       AND (NEW.state IS DISTINCT FROM OLD.state
            OR NEW.recovery_lease_token IS DISTINCT FROM OLD.recovery_lease_token
            OR recovery_evidence_changed) THEN
        expected_authority := 'RECOVERY';
        expected_token := NEW.recovery_lease_token;
    ELSIF NEW.recovery_lease_token IS NULL
       AND (NEW.state IS DISTINCT FROM OLD.state OR recovery_evidence_changed)
       AND (NEW.state = 'ROLLING_BACK'
            OR OLD.state = 'ROLLING_BACK'
            OR NEW.state IN ('ROLLED_BACK', 'RECOVERY_FAILED')) THEN
        expected_authority := 'BUILD_GENERATION';
        expected_token := NEW.workspace_lease_token;
    ELSE
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM run_workspace_authority w
         WHERE w.run_id = NEW.run_id
           AND w.authority_type = expected_authority
           AND w.owner_token = expected_token
           AND w.lease_expires_at >= NOW()
    ) INTO authority_current;

    IF NOT authority_current THEN
        RAISE EXCEPTION
            'workspace generation rollback authority is not current: authority=% attempt=%',
            expected_authority, NEW.id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workspace_generation_rollback_authority_fence
BEFORE UPDATE ON workspace_generation_attempt
FOR EACH ROW EXECUTE FUNCTION fence_workspace_generation_rollback_authority();
