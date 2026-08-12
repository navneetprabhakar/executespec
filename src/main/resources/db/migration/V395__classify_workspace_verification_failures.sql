-- Preserve whether deterministic acceptance failed because of product bytes, execution
-- environment, or internal evidence protocol. Historical receipts remain nullable; new protocol
-- writers populate the typed tuple and V395 makes it immutable with the rest of the receipt.
ALTER TABLE workspace_generation_verification_receipt
    ADD COLUMN failure_category VARCHAR(32),
    ADD COLUMN environment_code VARCHAR(64),
    ADD COLUMN execution_boundary VARCHAR(64);

ALTER TABLE workspace_generation_verification_receipt
    ADD CONSTRAINT workspace_generation_verification_failure_category_chk CHECK (
        failure_category IS NULL
        OR failure_category IN ('PRODUCT', 'ENVIRONMENT', 'INTERNAL_PROTOCOL')
    ),
    ADD CONSTRAINT workspace_generation_verification_boundary_chk CHECK (
        execution_boundary IS NULL
        OR execution_boundary IN ('DISPOSABLE_EXACT_GENERATION', 'VERIFICATION_SANDBOX')
    ),
    ADD CONSTRAINT workspace_generation_verification_failure_shape_chk CHECK (
        (outcome = 'PASSED' AND failure_category IS NULL AND environment_code IS NULL)
        OR (outcome = 'FAILED' AND failure_category IN ('PRODUCT', 'INTERNAL_PROTOCOL'))
        OR (outcome = 'UNAVAILABLE' AND failure_category IN ('ENVIRONMENT', 'INTERNAL_PROTOCOL'))
        OR (failure_category IS NULL AND environment_code IS NULL AND execution_boundary IS NULL)
    );

COMMENT ON COLUMN workspace_generation_verification_receipt.failure_category IS
    'Typed product/environment/internal causality; nullable only for pre-V395 history.';
COMMENT ON COLUMN workspace_generation_verification_receipt.environment_code IS
    'Safe bounded environment/protocol code, never raw command output.';
COMMENT ON COLUMN workspace_generation_verification_receipt.execution_boundary IS
    'Physical boundary that produced the deterministic receipt.';

CREATE OR REPLACE FUNCTION require_typed_workspace_verification_failure()
RETURNS TRIGGER AS $$
DECLARE
    protocol_version VARCHAR(32);
BEGIN
    SELECT build_protocol_version
      INTO protocol_version
      FROM workspace_generation_attempt
     WHERE id = NEW.attempt_id;
    IF protocol_version = 'SHARED_KERNEL_V2' THEN
        IF NEW.execution_boundary IS NULL
           OR (NEW.outcome <> 'PASSED' AND NEW.failure_category IS NULL)
           OR (NEW.outcome = 'UNAVAILABLE' AND NEW.environment_code IS NULL) THEN
            RAISE EXCEPTION 'protocol-v2 workspace verification causality is incomplete';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_workspace_verification_typed_causality
BEFORE INSERT ON workspace_generation_verification_receipt
FOR EACH ROW EXECUTE FUNCTION require_typed_workspace_verification_failure();
