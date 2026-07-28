-- A project collaborator executes inside the host project's tenant (`pipeline_run.org_id`) while
-- their own billing relationship and credential policy remain the payer. V348 accidentally
-- required those two organizations to be identical, making a valid external-collaborator run
-- impossible to approve. The immutable gate receipt already stores both identities separately:
-- `billing_org_id` is the payer/credential organization and the run row remains the governance and
-- data-tenant organization.
--
-- Preserve every other V348 deferred-coherence check and change only that accidental equality.
-- Fetching and replacing the immediately preceding migration's function keeps the forward fix
-- small while failing closed if the expected V348 definition is not installed verbatim enough for
-- this targeted clause to exist.
DO $$
DECLARE
    definition TEXT;
    forbidden_clause CONSTANT TEXT := 'OR gate_billing_org IS DISTINCT FROM run_org_id';
    retained_evidence_clause CONSTANT TEXT :=
        '(gate_evidence ->> ''billingOrgId'') IS DISTINCT FROM gate_billing_org';
    retained_owner_clause CONSTANT TEXT :=
        'gate_billing_owner IS DISTINCT FROM run_owner_user_id';
    occurrence_count INTEGER;
BEGIN
    SELECT pg_get_functiondef(procedure.oid)
      INTO definition
      FROM pg_proc procedure
      JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
     WHERE namespace.nspname = current_schema()
       AND procedure.proname = 'enforce_plan_gate_decision_coherence'
       AND pg_get_function_identity_arguments(procedure.oid) = '';

    IF definition IS NULL THEN
        RAISE EXCEPTION
            'V349 requires the V348 enforce_plan_gate_decision_coherence definition';
    END IF;

    occurrence_count :=
        (length(definition) - length(replace(definition, forbidden_clause, '')))
        / length(forbidden_clause);
    IF occurrence_count IS DISTINCT FROM 1
       OR position(retained_evidence_clause IN definition) = 0
       OR position(retained_owner_clause IN definition) = 0 THEN
        RAISE EXCEPTION
            'V349 found an unexpected V348 enforce_plan_gate_decision_coherence definition';
    END IF;

    definition := replace(definition, forbidden_clause, '');
    EXECUTE definition;

    SELECT pg_get_functiondef(procedure.oid)
      INTO definition
      FROM pg_proc procedure
      JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
     WHERE namespace.nspname = current_schema()
       AND procedure.proname = 'enforce_plan_gate_decision_coherence'
       AND pg_get_function_identity_arguments(procedure.oid) = '';

    IF definition IS NULL
       OR position(forbidden_clause IN definition) <> 0
       OR position(retained_evidence_clause IN definition) = 0
       OR position(retained_owner_clause IN definition) = 0 THEN
        RAISE EXCEPTION
            'V349 failed to install the separated payer/tenant coherence definition';
    END IF;
END;
$$;

COMMENT ON COLUMN gate_record.billing_org_id IS
    'Immutable payer and credential-policy org; may differ from pipeline_run.org_id for an external project collaborator.';
