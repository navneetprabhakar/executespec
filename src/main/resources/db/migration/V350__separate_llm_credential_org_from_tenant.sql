-- LLM execution contexts have two independent authority dimensions:
--   org_id                host data/governance tenant used for isolation and project access
--   credential_org_id     payer/provider-policy org used for key resolution and managed-AI debit
-- Existing rows predate external-collaborator support and therefore used one org for both.
ALTER TABLE pipeline_run
    ADD COLUMN billing_org_id VARCHAR(64);

UPDATE pipeline_run
   SET billing_org_id = org_id
 WHERE billing_org_id IS NULL;

COMMENT ON COLUMN pipeline_run.org_id IS
    'Host tenant for project/data isolation; may differ from billing_org_id for external collaborators.';

COMMENT ON COLUMN pipeline_run.billing_org_id IS
    'Immutable payer/provider-policy org captured from the submitting actor.';

ALTER TABLE llm_execution_context
    ADD COLUMN credential_org_id VARCHAR(64);

UPDATE llm_execution_context
   SET credential_org_id = org_id
 WHERE scope_mode = 'CUSTOMER'
   AND credential_org_id IS NULL;

ALTER TABLE llm_execution_context
    ADD CONSTRAINT ck_llm_context_customer_credential_org
    CHECK (scope_mode <> 'CUSTOMER' OR credential_org_id IS NOT NULL);

COMMENT ON COLUMN llm_execution_context.org_id IS
    'Host tenant for data isolation; may differ from credential_org_id for external collaborators.';

COMMENT ON COLUMN llm_execution_context.credential_org_id IS
    'Payer/provider-policy org resolved from key_subject_user_id and revalidated before dispatch.';

-- These values are submit/creation-time authority facts. Lifecycle updates may touch the same
-- rows, but must never retarget an existing run/context to another payer after admission or spend.
CREATE OR REPLACE FUNCTION enforce_pipeline_run_billing_org_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.billing_org_id IS DISTINCT FROM OLD.billing_org_id THEN
        RAISE EXCEPTION 'pipeline_run billing_org_id is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_llm_execution_context_credential_org_immutability()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.credential_org_id IS DISTINCT FROM OLD.credential_org_id THEN
        RAISE EXCEPTION 'llm_execution_context credential_org_id is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_pipeline_run_billing_org_immutable
BEFORE UPDATE OF billing_org_id ON pipeline_run
FOR EACH ROW EXECUTE FUNCTION enforce_pipeline_run_billing_org_immutability();

CREATE TRIGGER trg_llm_execution_context_credential_org_immutable
BEFORE UPDATE OF credential_org_id ON llm_execution_context
FOR EACH ROW EXECUTE FUNCTION enforce_llm_execution_context_credential_org_immutability();
